package com.grinningfrog.atlas.workspace.visual

import android.app.Service
import android.content.Intent
import android.os.*
import com.grinningfrog.atlas.workspace.application.WorkspaceApplicationContract
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Trusted host process with a separate WebView profile/renderer. No live database or device adapter. */
internal class WorkspaceVisualService : Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val mutex=Mutex()
    private val jobs=mutableMapOf<String,Job>()
    private data class CachedReply(val argumentsHash:String,val result:JSONObject)
    private val replies=linkedMapOf<String,CachedReply>()
    private var instance: Instance?=null
    private val nonce=UUID.randomUUID().toString()
    private data class Instance(val id:String,val owner:String,val openRequestId:String,val window:WorkspaceVisualWindow,val opened:Long,var touched:Long,var operations:Int=0,var generation:Int=0,var capturedGeneration:Int = -1,val assertions:JSONArray=JSONArray(),var failed:Boolean=false)
    private val messenger=Messenger(object:Handler(Looper.getMainLooper()) {
        override fun handleMessage(message:Message) {
            val b=message.data
            if(message.what==2) {
                val running=jobs[b.getString("request_id")]
                if(running!=null)running.cancel() else scope.launch {mutex.withLock {
                    instance?.takeIf {it.owner==b.getString("owner") && (it.id==b.getString("instance_id") || b.getString("action")=="open" && it.openRequestId==b.getString("request_id"))}?.let {it.window.close();instance=null}
                }}
                return
            }
            val reply=message.replyTo ?: return
            val id=b.getString("request_id") ?: return
            val fd=b.getParcelable<ParcelFileDescriptor>("package")
            if(jobs.containsKey(id)) {fd?.close();return}
            jobs[id]=scope.launch {
                var capture:ParcelFileDescriptor?=null
                try {
                    val result=mutex.withLock {
                        withTimeout(30_000) {
                            val args=JSONObject(b.getString("json") ?: "{}").put("host_request_id",id)
                            require(args.toString().length<=96*1024)
                            // Only mutations need replay suppression. Captures are safe to repeat.
                            val cacheKey=args.getString("owner")+":"+id
                            val argumentsHash=WorkspaceApplicationContract.hash(WorkspaceApplicationContract.canonical(args))
                            if(args.getString("action")=="interact")replies[cacheKey]?.let {previous ->
                                require(previous.argumentsHash==argumentsHash) {"VISUAL_REQUEST_ID_CONFLICT"}
                                return@withTimeout JSONObject(previous.result.toString())
                            }
                            val result=perform(args,fd) {capture=it}
                            if(args.getString("action")=="interact") { if(replies.size>=128)replies.remove(replies.keys.first());replies[cacheKey]=CachedReply(argumentsHash,JSONObject(result.toString())) }
                            result
                        }
                    }
                    val memory=android.os.Debug.MemoryInfo();android.os.Debug.getMemoryInfo(memory)
                    result.put("service_pid",Process.myPid()).put("process_nonce",nonce).put("service_pss_kb",memory.totalPss)
                    check(result.toString().length<=128*1024)
                    reply.send(Message.obtain(null,1).apply { data=Bundle().apply {putString("request_id",id);putString("json",result.toString());capture?.let {putParcelable("capture",it)} } })
                } catch(error:Throwable) {
                    // Callback/JS timeout is not evidence; retire this isolated renderer and handle.
                    val supplied=runCatching {JSONObject(b.getString("json") ?: "{}")}.getOrDefault(JSONObject())
                    withContext(NonCancellable) { mutex.withLock { instance?.takeIf {it.owner==supplied.optString("owner") && (it.id==supplied.optString("instance_id") || supplied.optString("action")=="open" && it.openRequestId==id)}?.let { runCatching {it.window.terminate()};it.window.close();instance=null } } }
                    runCatching { reply.send(Message.obtain(null,1).apply {data=Bundle().apply {putString("request_id",id);putString("error",error.message?.take(500) ?: "Visual test interrupted; reopen the candidate")}}) }
                } finally {capture?.close();fd?.close();jobs.remove(id)}
            }
        }
    })
    override fun onCreate() {
        super.onCreate()
        // No handles or capture files survive process death. The main-process journal records expiry.
        File(cacheDir,"workspace-visual").apply {mkdirs();listFiles()?.forEach {it.delete()}}
        scope.launch {while(isActive) { delay(5_000);mutex.withLock {
            instance?.let { test ->
                val now=SystemClock.elapsedRealtime()
                if(now-test.touched>WorkspaceVisualLimits.IDLE_MS || now-test.opened>WorkspaceVisualLimits.LIFETIME_MS) {test.window.close();instance=null}
                else if(now-test.touched>30_000)test.window.pauseRendering()
            }
        }}}
    }
    private suspend fun perform(args:JSONObject, fd:ParcelFileDescriptor?, captured:(ParcelFileDescriptor)->Unit):JSONObject {
        val action=args.getString("action");val owner=args.getString("owner");require(owner.length in 1..240)
        val now=SystemClock.elapsedRealtime()
        if(action=="status")return JSONObject().put("instance_id",instance?.takeIf {it.owner==owner}?.id).put("active_instances",if(instance==null)0 else 1)
        if(action=="open") {
            instance?.let { previous ->
                WorkspaceVisualLimits.owns(previous.owner,owner)
                require(args.optString("instance_id")==previous.id) {"VISUAL_INSTANCE_BUSY: close the previous test before opening another"}
                return metadata(previous)
            }
            val source=withContext(Dispatchers.IO) {ParcelFileDescriptor.AutoCloseInputStream(requireNotNull(fd)).use { input ->
                val buffer=java.io.ByteArrayOutputStream();val chunk=ByteArray(8192)
                while(buffer.size()<=WorkspaceApplicationContract.MAX_PACKAGE_BYTES) {val read=input.read(chunk);if(read<0)break;buffer.write(chunk,0,read)}
                val bytes=buffer.toByteArray()
                require(bytes.size<=WorkspaceApplicationContract.MAX_PACKAGE_BYTES);bytes.toString(Charsets.UTF_8)
            }}
            require(WorkspaceApplicationContract.hash(source)==args.getString("package_sha256"))
            val bundle=JSONObject(source);require(WorkspaceApplicationContract.validate(bundle).isEmpty())
            val renderer=WorkspaceVisualWindow(this,args.getJSONObject("profile"),args.getString("title"),args.getString("base_revision_id"),bundle)
            val test=Instance(UUID.randomUUID().toString(),owner,args.getString("host_request_id"),renderer,now,now)
            instance=test
            renderer.start()
            return metadata(test).put("layout",renderer.inspect()).put("calibration","passed_actual_window_pixelcopy")
        }
        val test=instance ?: error("VISUAL_INSTANCE_EXPIRED: reopen the candidate; DOM state is not recoverable after expiry or process death")
        WorkspaceVisualLimits.owns(test.owner,owner)
        require(test.id==args.getString("instance_id")) {"VISUAL_INSTANCE_EXPIRED"}
        if(action=="close") {test.window.close();instance=null;return JSONObject().put("closed",true).put("instance_id",test.id)}
        require(now-test.opened<=WorkspaceVisualLimits.LIFETIME_MS && now-test.touched<=WorkspaceVisualLimits.IDLE_MS && ++test.operations<=WorkspaceVisualLimits.MAX_OPERATIONS) {"VISUAL_INSTANCE_BUDGET_EXPIRED"}
        test.touched=now;test.window.resumeRendering()
        val result=when(action) {
            "inspect" -> test.window.inspect()
            "capture" -> {
                val started=SystemClock.elapsedRealtime();val bitmap=test.window.capture()
                val file=File(File(cacheDir,"workspace-visual"),"${UUID.randomUUID()}.png")
                try {
                    withContext(Dispatchers.IO) { file.outputStream().use {check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))} }
                    captured(ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY))
                    test.capturedGeneration=test.generation
                    JSONObject().put("captured_at_ms",System.currentTimeMillis()).put("capture_ms",SystemClock.elapsedRealtime()-started).put("width",bitmap.width).put("height",bitmap.height)
                } finally {bitmap.recycle();file.delete()}
            }
            "interact" -> {
                val steps=args.getJSONArray("steps");require(steps.length() in 1..16)
                for(i in 0 until steps.length())WorkspaceVisualInteractions.validate(steps.getJSONObject(i))
                val reports=JSONArray();test.generation++
                for(i in 0 until steps.length()) {
                    val step=steps.getJSONObject(i)
                    val report=WorkspaceVisualInteractions.run(test.window.surface.view,step);reports.put(report)
                    if(step.getString("action").startsWith("assert_") || step.getString("action")=="wait_text")test.assertions.put(report)
                    if(!report.getBoolean("passed")) {test.failed=true;break}
                    delay(100)
                }
                JSONObject().put("steps",reports).put("passed",reports.length()==steps.length() && (0 until reports.length()).all {reports.getJSONObject(it).getBoolean("passed")})
            }
            "finish" -> {
                val layout=test.window.inspect();val runtime=layout.getJSONObject("runtime")
                val errors=runtime.optInt("runtime_error_count")+runtime.optInt("resource_error_count")+runtime.optInt("host_failure_count")
                val passed=!test.failed && test.assertions.length()>0 && test.capturedGeneration==test.generation && errors==0 && layout.optJSONObject("viewport")?.let {it.optDouble("width")>0 && it.optDouble("height")>0}==true
                val criteria=(0 until test.assertions.length()).mapNotNull {test.assertions.getJSONObject(it).optString("criterion").trim().takeIf(String::isNotEmpty)}.distinct()
                JSONObject().put("passed",passed).put("assertions",test.assertions).put("verified_criteria",if(passed)JSONArray(criteria) else JSONArray())
                    .put("screenshot_after_latest_interaction",test.capturedGeneration==test.generation).put("runtime_error_count",errors).put("layout",layout)
                    .put("evidence_scope","Private-display compositor pixels, bounded geometry and declared assertions with isolated SQLite/mock device capabilities. Design quality is agent-assessed; physical peripherals unverified.")
            }
            else -> error("UNSUPPORTED_VISUAL_OPERATION")
        }
        return result.put("instance",metadata(test))
    }
    private fun metadata(test:Instance)=JSONObject().put("instance_id",test.id).put("display_id",test.window.displayId).put("profile",test.window.profile)
        .put("operations",test.operations).put("remaining_operations",WorkspaceVisualLimits.MAX_OPERATIONS-test.operations).put("idle_timeout_ms",WorkspaceVisualLimits.IDLE_MS)
    override fun onBind(intent:Intent):IBinder=messenger.binder
    override fun onUnbind(intent:Intent):Boolean {scope.launch {mutex.withLock {instance?.window?.close();instance=null}};return false}
    override fun onDestroy() {scope.cancel();instance?.window?.close();instance=null;super.onDestroy()}
}
