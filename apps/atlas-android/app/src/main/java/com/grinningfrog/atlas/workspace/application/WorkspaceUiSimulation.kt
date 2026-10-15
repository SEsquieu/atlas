package com.grinningfrog.atlas.workspace.application

import android.content.Context
import android.content.ContentValues
import com.grinningfrog.atlas.data.AtlasDatabase
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.coroutines.resume

/** DOM behavior evidence for immutable candidate bytes. No live records, radio or workspace grants. */
internal class WorkspaceUiSimulation(private val context: Context, private val database: AtlasDatabase) {
    internal class Radio : WorkspaceBleAdapter {
        var failingScans=0
        val calls=linkedMapOf<String,Int>()
        private var connected=false
        private var subscribed=false
        private var delivered=false
        private fun call(name: String) { calls[name]=(calls[name] ?: 0)+1 }
        override fun permitted()=true
        override fun status()=JSONObject().put("installed",true).put("os_permission_granted",true).put("platform_enabled",true).put("connected",connected).put("device_label",if(connected)"Simulated bench device" else JSONObject.NULL)
        override suspend fun scan(): JSONArray { call("scan"); if(failingScans-->0)error("BLE_FAILED"); return JSONArray().put(JSONObject().put("device_id","sim-device").put("name","Simulated bench device").put("rssi",-45).put("service_uuids",JSONArray().put("0000180f-0000-1000-8000-00805f9b34fb"))) }
        override fun deviceLabel(id: String): String { require(id=="sim-device") { "DEVICE_UNAVAILABLE" };return "Simulated bench device" }
        override suspend fun connect(id: String): JSONObject { deviceLabel(id);call("connect");connected=true;return JSONObject().put("connected",true).put("name","Simulated bench device") }
        override fun services(): JSONArray {
            require(connected) { "DEVICE_UNAVAILABLE" };call("services")
            return JSONArray().put(JSONObject().put("uuid","0000180f-0000-1000-8000-00805f9b34fb").put("characteristics",JSONArray().put(JSONObject().put("characteristic_id","sim-char").put("uuid","00002a19-0000-1000-8000-00805f9b34fb").put("readable",true).put("writable",true).put("notifiable",true).put("properties",26))))
        }
        private fun characteristic(id: String) { require(connected && id=="sim-char") { "DEVICE_UNAVAILABLE" } }
        override suspend fun read(id: String): JSONObject { characteristic(id);call("read");return JSONObject().put("hex","2a").put("truncated",false).put("gatt_status",0) }
        override suspend fun write(id: String, hex: String): JSONObject { characteristic(id);call("write");return JSONObject().put("acknowledged",true).put("gatt_status",0) }
        override suspend fun subscribe(id: String, enabled: Boolean): JSONObject { characteristic(id);call("subscribe");subscribed=enabled;return JSONObject().put("enabled",enabled).put("gatt_status",0) }
        override fun events(): JSONObject { call("events");return JSONObject().put("events",JSONArray().apply { if(connected && subscribed && !delivered) { delivered=true;put(JSONObject().put("characteristic_id","sim-char").put("hex","2b").put("truncated",false).put("received_at_ms",System.currentTimeMillis())) } }).put("dropped",0) }
        override fun close() { connected=false;subscribed=false }
    }
    suspend fun test(workspaceId: String, realmId: String, candidateId: String, steps: JSONArray, committed: (JSONObject)->Unit = {}): JSONObject = withTimeout(30_000) {
        require(steps.length() in 1..32)
        for(i in 0 until steps.length()) {
            val step=steps.getJSONObject(i)
            WorkspaceApplicationContract.exactKeys(step,setOf("action","selector","value"),setOf("action","selector","value"))
            require(step.getString("action") in setOf("click","input","assert_text","wait_text"))
            require(step.getString("selector").length in 1..128 && step.getString("value").length<=500)
        }
        val repository=WorkspacePackageRepository(database)
        val candidate=repository.inspect(workspaceId,realmId,candidateId)
        val base=candidate.getString("base_revision_id");val hash=candidate.getString("content_hash");val version=WorkspaceApplicationData(database).version(workspaceId)
        val bundle=candidate.getJSONObject("bundle")
        val requested=WorkspaceCapabilityRegistry.requested(bundle.getJSONObject("manifest"))[WorkspaceBleContract.CAPABILITY].orEmpty()
        val reports=JSONArray();val radio=Radio();var runtimeEvidence:JSONObject?=null
        withContext(Dispatchers.Main.immediate) {
            val ready=CompletableDeferred<Unit>();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
            val principal=WorkspacePrincipal(realmId,workspaceId,"simulation-$candidateId",UUID.randomUUID().toString(),0,WorkspaceInstanceMode.TEST)
            val broker=if(requested.isEmpty())null else WorkspaceDeviceBroker(principal,listOf(WorkspaceBleCapability(requested,radio,simulated=true)),{true},{true},{true},scope,isSimulation=true)
            var presentation: WorkspaceWebPresentation?=null
            suspend fun evaluate(script: String): String = suspendCancellableCoroutine { c -> presentation!!.view.evaluateJavascript(script) { if(c.isActive)c.resume(it) } }
            try {
                val surface=WorkspaceWebPresentation(context,bundle,principal,{true},{ready.complete(Unit)},{ready.completeExceptionally(IllegalStateException(it))},devices=broker)
                presentation=surface;surface.view.layout(0,0,1080,900);surface.load()
                withTimeout(8_000) { ready.await() }
                for(i in 0 until steps.length()) {
                    val step=steps.getJSONObject(i);val action=step.getString("action");val selector=JSONObject.quote(step.getString("selector"));val value=JSONObject.quote(step.getString("value"))
                    val script="""(()=>{const e=document.querySelector($selector);if(!e)return 'missing';if(${"\"$action\""}==='click'){if(e.disabled)return 'disabled';e.click();return 'ok';}if(${"\"$action\""}==='input'){e.value=$value;e.dispatchEvent(new Event('input',{bubbles:true}));return 'ok';}return (e.textContent||'').includes($value)?'ok':'mismatch';})()"""
                    var outcome=evaluate(script)
                    if(action=="wait_text") { val end=android.os.SystemClock.elapsedRealtime()+3_000;while(outcome!="\"ok\"" && android.os.SystemClock.elapsedRealtime()<end) { delay(100);outcome=evaluate(script) } }
                    val passed=outcome=="\"ok\""
                    reports.put(JSONObject().put("index",i).put("action",action).put("selector",step.getString("selector")).put("passed",passed).put("outcome",outcome.take(100)))
                    if(!passed) { reports.getJSONObject(reports.length()-1).put("actual_text",evaluate("(()=>document.querySelector($selector)?.textContent?.slice(0,500)||'')()"));break }
                    // Let asynchronous UI handlers dispatch their host requests before the next assertion/action.
                    delay(300)
                }
                if(requested.contains("subscribe")) { val end=android.os.SystemClock.elapsedRealtime()+2_000;while((radio.calls["events"] ?: 0)==0 && (radio.calls["subscribe"] ?: 0)>0 && android.os.SystemClock.elapsedRealtime()<end)delay(100) }
            } finally { runtimeEvidence=presentation?.diagnostics();presentation?.dispose();broker?.close();scope.cancel() }
        }
        val required=requested+if("subscribe" in requested)setOf("events") else emptySet()
        val missing=required.filter { (radio.calls[it] ?: 0)==0 }
        val passed=reports.length()==steps.length() && (0 until reports.length()).all { reports.getJSONObject(it).getBoolean("passed") } && missing.isEmpty()
        val id=UUID.randomUUID().toString()
        val result=JSONObject().put("check_id",id).put("candidate_id",candidateId).put("content_hash",hash).put("base_revision_id",base).put("data_version",version)
            .put("runtime",runtimeEvidence ?: JSONObject.NULL).put("kind","ui_simulation").put("passed",passed).put("steps",reports).put("operation_counts",JSONObject(radio.calls as Map<*,*>)).put("missing_operations",JSONArray(missing))
            .put("evidence_scope","DOM controls and simulated BLE responses only. No physical device, Android/native consent dialog, painted pixels, or saved records were verified.")
        currentCoroutineContext().ensureActive()
        WorkspaceApplicationData(database).atomic {
            require(database.loadComposableWorkspace(workspaceId)?.liveRevisionId==base && WorkspaceApplicationData(database).version(workspaceId)==version) { "REVISION_CONFLICT" }
            require(repository.inspect(workspaceId,realmId,candidateId).getString("content_hash")==hash) { "PACKAGE_CORRUPT" }
            val count=rawQuery("SELECT COUNT(*) FROM workspace_application_checks WHERE workspace_id=?",arrayOf(workspaceId)).use { it.moveToFirst();it.getInt(0) };require(count<256) { "QUOTA_EXCEEDED" }
            insertOrThrow("workspace_application_checks",null,ContentValues().apply { put("check_id",id);put("workspace_id",workspaceId);put("candidate_id",candidateId);put("content_hash",hash);put("kind","ui_simulation");put("passed",if(passed)1 else 0);put("base_revision_id",base);put("data_version",version);put("result_json",result.toString());put("created_at",System.currentTimeMillis()) })
            committed(result)
        }
        result
    }
}
