package com.grinningfrog.atlas.workspace.visual

import android.content.*
import android.os.*
import android.util.AtomicFile
import com.grinningfrog.atlas.data.AtlasDatabase
import com.grinningfrog.atlas.model.SessionStatus
import com.grinningfrog.atlas.runtime.ExecutionState
import com.grinningfrog.atlas.runtime.ExecutionTaskStore
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Task-owned RPC and durable handle journal. Never replay authored UI state after process death. */
internal class WorkspaceVisualClient(private val context:Context,private val database:AtlasDatabase) {
    data class Response(val json:JSONObject,val image:ParcelFileDescriptor?=null)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val pending=mutableMapOf<String,CompletableDeferred<Response>>()
    private val bindMutex=Mutex()
    private var remote:Messenger?=null
    private var connected=CompletableDeferred<Messenger>()
    private var bound=false
    private val directory=File(context.filesDir,"workspace-visual-handles").apply {mkdirs()}
    private val live=mutableMapOf<String,JSONObject>()
    private val connection=object:ServiceConnection {
        override fun onServiceConnected(name:ComponentName,binder:IBinder) {remote=Messenger(binder);connected.complete(remote!!)}
        override fun onServiceDisconnected(name:ComponentName) {lost("renderer_process_disconnected")}
        override fun onBindingDied(name:ComponentName) {lost("renderer_binding_died");if(bound)runCatching {context.unbindService(this)};bound=false}
        override fun onNullBinding(name:ComponentName) {lost("renderer_binding_unavailable")}
    }
    private val receiver=Messenger(object:Handler(Looper.getMainLooper()) {
        override fun handleMessage(message:Message) {
            val b=message.data;val id=b.getString("request_id") ?: return
            val image=b.getParcelable<ParcelFileDescriptor>("capture")
            val waiter=pending.remove(id)
            if(waiter==null || !waiter.isActive) {image?.close();return}
            val error=b.getString("error")
            if(error!=null) {image?.close();waiter.completeExceptionally(IllegalStateException(error))}
            else waiter.complete(Response(JSONObject(b.getString("json") ?: "{}"),image))
        }
    })
    init {
        context.cacheDir.listFiles()?.filter {it.name.startsWith("visual-candidate-") && it.extension=="json"}?.forEach {it.delete()}
        // A new host process never claims an old DOM is alive.
        directory.listFiles()?.filter {it.extension=="json"}?.forEach {file -> runCatching {
            val record=JSONObject(file.readText());if(record.optString("state")=="open")write(record.put("state","expired").put("reason","host_process_restarted"))
        }}
        scope.launch {while(isActive) {delay(2_000)
            for(record in live.values.toList()) {
                val task=ExecutionTaskStore(database).get(record.getString("task_id"))
                val session=database.loadSession(record.getString("session_id"))
                if(task==null || task.state in setOf(ExecutionState.COMPLETED,ExecutionState.CANCELLED,ExecutionState.FAILED,ExecutionState.PAUSED) || session?.status!=SessionStatus.ACTIVE)close(record,"task_or_session_stopped")
                else if(System.currentTimeMillis()-record.getLong("touched_at_ms")>WorkspaceVisualLimits.IDLE_MS)close(record,"idle_timeout")
            }
        }}
    }
    private fun lost(reason:String) {
        remote=null;connected.completeExceptionally(IllegalStateException("VISUAL_PROCESS_LOST"));connected=CompletableDeferred()
        pending.values.forEach {it.completeExceptionally(IllegalStateException("VISUAL_PROCESS_LOST: reopen candidate"))};pending.clear()
        live.values.toList().forEach {retire(it,reason)}
    }
    private suspend fun bind():Messenger=bindMutex.withLock {
        remote?.let {return@withLock it}
        if(!bound) {
            connected=CompletableDeferred()
            bound=context.bindService(Intent(context,WorkspaceVisualService::class.java),connection,Context.BIND_AUTO_CREATE)
            check(bound) {"VISUAL_SERVICE_UNAVAILABLE"}
        }
        withTimeout(5_000) {connected.await()}
    }
    suspend fun request(args:JSONObject,id:String=UUID.randomUUID().toString(),packageFile:File?=null):Response {
        var delivered:Response?=null
        try { return withContext(Dispatchers.Main.immediate) {
        val target=bind();val deferred=CompletableDeferred<Response>();check(!pending.containsKey(id));pending[id]=deferred
        var descriptor:ParcelFileDescriptor?=null
        try {
            descriptor=packageFile?.let {ParcelFileDescriptor.open(it,ParcelFileDescriptor.MODE_READ_ONLY)}
            target.send(Message.obtain(null,1).apply {replyTo=receiver;data=Bundle().apply {putString("request_id",id);putString("json",args.toString());descriptor?.let {putParcelable("package",it)}}})
            withTimeout(35_000) {deferred.await().also {delivered=it}}
        } catch(error:Throwable) {
            runCatching {target.send(Message.obtain(null,2).apply {data=Bundle().apply {putString("request_id",id);putString("owner",args.getString("owner"));putString("action",args.optString("action"));putString("instance_id",args.optString("instance_id"))}})}
            if(error is TimeoutCancellationException) {
                // A wedged remote UI thread cannot process cancellation. Kill only our named service process.
                val processes=(context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).runningAppProcesses.orEmpty()
                processes.firstOrNull {it.uid==Process.myUid() && it.processName==context.packageName+":workspace_visual" && it.pid!=Process.myPid()}?.let {Process.killProcess(it.pid)}
                lost("renderer_rpc_timeout")
            }
            throw error
        } finally {
            pending.remove(id);descriptor?.close()
            if(!deferred.isCompleted)deferred.cancel()
            // A cancellation racing a completed Binder reply must not leak its image descriptor.
            if(!currentCoroutineContext().isActive && deferred.isCompleted && !deferred.isCancelled)runCatching {deferred.await().image?.close()}
        }
    }
        } catch(error:Throwable) {delivered?.image?.close();throw error}
    }
    fun register(record:JSONObject) {live[record.getString("instance_id")]=record;write(record)}
    fun owned(id:String,owner:String,allowClosed:Boolean=false):JSONObject {
        require(id.matches(Regex("[a-f0-9-]{36}")))
        val record=live[id] ?: (if(allowClosed)runCatching {JSONObject(File(directory,"$id.json").readText())}.getOrNull() else null)
            ?: error("VISUAL_INSTANCE_EXPIRED: reopen the candidate; previous state is unavailable")
        WorkspaceVisualLimits.owns(record.getString("owner"),owner)
        record.put("touched_at_ms",System.currentTimeMillis())
        return record
    }
    fun records(owner:String)=JSONArray(directory.listFiles().orEmpty().filter {it.extension=="json"}.sortedByDescending {it.lastModified()}.take(128)
        .mapNotNull {runCatching {JSONObject(it.readText())}.getOrNull()}.filter {it.optString("owner")==owner}.take(16))
    fun update(record:JSONObject) {write(record)}
    private fun write(record:JSONObject) {
        val file=AtomicFile(File(directory,"${record.getString("instance_id")}.json"));val out=file.startWrite()
        try {out.write(record.toString().toByteArray());file.finishWrite(out)} catch(e:Throwable) {file.failWrite(out);throw e}
        directory.listFiles()?.filter {it.extension=="json"}?.sortedByDescending {it.lastModified()}?.drop(128)?.forEach {it.delete()}
    }
    fun retire(record:JSONObject,reason:String) {live.remove(record.getString("instance_id"));write(record.put("state","closed").put("reason",reason).put("closed_at_ms",System.currentTimeMillis()))}
    suspend fun close(record:JSONObject,reason:String) {
        if(record.optString("state")!="open")return
        try {request(JSONObject().put("action","close").put("owner",record.getString("owner")).put("instance_id",record.getString("instance_id"))).image?.close()}
        catch(_:Exception) { /* Remote process death already releases the OS display and renderer. */ }
        finally {retire(record,reason)}
    }
    fun closeAll(reason:String) {scope.launch {for(record in live.values.toList())close(record,reason)}}
    fun shutdown() {closeAll("host_service_stopped");scope.launch {delay(500);if(bound)runCatching {context.unbindService(connection)};bound=false;lost("host_service_stopped");scope.cancel()}}
}
