package com.grinningfrog.atlas.workspace.application

import android.content.Context
import android.content.ContentValues
import com.grinningfrog.atlas.data.AtlasDatabase
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.selects.select

/** DOM behavior evidence for immutable candidate bytes. No live records, radio or workspace grants. */
internal class WorkspaceUiSimulation(private val context: Context, private val database: AtlasDatabase) {
    internal class Radio : WorkspaceBleAdapter {
        var failingScans=0
        val calls=linkedMapOf<String,Int>()
        private var connected=false
        private var subscribed=false
        private var emitted=false
        var delivered=false
            private set
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
        override fun events(): JSONObject { call("events");return JSONObject().put("events",JSONArray().apply { if(connected && subscribed && !emitted) { delivered=true;emitted=true;put(JSONObject().put("characteristic_id","sim-char").put("hex","2b").put("truncated",false).put("received_at_ms",System.currentTimeMillis())) } }).put("dropped",0) }
        override fun close() { connected=false;subscribed=false;emitted=false }
    }
    suspend fun test(workspaceId: String, realmId: String, candidateId: String, steps: JSONArray, verifyStorage: Boolean = false, randomValues: List<Double>? = null, committed: (JSONObject)->Unit = {}): JSONObject = coroutineScope {
        require(steps.length() in 1..32)
        for(i in 0 until steps.length()) {
            val step=steps.getJSONObject(i)
            WorkspaceApplicationContract.exactKeys(step,setOf("action","selector","value","criterion"),setOf("action","selector"))
            require(step.getString("action") in setOf("click","input","select","assert_text","wait_text","assert_value","assert_visible","assert_hidden","assert_absent","assert_count","reload","assert_record","set_checked","drag","drag_cancel","assert_attribute","assert_text_exact"))
            if(step.has("criterion"))require(step.getString("criterion").trim().length in 1..200 && step.getString("action") in setOf("assert_text","wait_text","assert_value","assert_visible","assert_hidden","assert_absent","assert_count","assert_record","assert_attribute","assert_text_exact")) { "Criteria require an assertion, not merely a click/input" }
            require(step.getString("selector").length in 0..128 && step.optString("value").length<=2000)
            if(step.getString("action")=="assert_attribute") {
                val attribute=JSONObject(step.getString("value"))
                WorkspaceApplicationContract.exactKeys(attribute,setOf("name","equals"),setOf("name","equals"))
                require(attribute.getString("name").matches(Regex("[A-Za-z][A-Za-z0-9_-]{0,63}")) && attribute.get("equals") is String) { "ATTRIBUTE_ASSERTION_INVALID" }
            }
            if(step.getString("action")=="set_checked")require(step.optString("value") in setOf("true","false")) { "set_checked value must be true or false" }
            require(step.has("value") || step.getString("action") in setOf("click","reload","assert_visible","assert_hidden","assert_absent")) { "This action requires value" }
        }
        val repository=WorkspacePackageRepository(database)
        val candidate=repository.inspect(workspaceId,realmId,candidateId)
        val base=candidate.getString("base_revision_id");val hash=candidate.getString("content_hash");val version=WorkspaceApplicationData(database).version(workspaceId)
        // A changed test cannot repair hung application bytes. Require a new content hash.
        database.readableDatabase.rawQuery("SELECT result_json FROM workspace_application_checks WHERE workspace_id=? AND content_hash=? AND kind='ui_simulation' ORDER BY created_at DESC",arrayOf(workspaceId,hash)).use { cursor ->
            while(cursor.moveToNext()) {
                val previous=JSONObject(cursor.getString(0))
                if(previous.optString("failure_kind")=="execution_timeout") {
                    return@coroutineScope previous.put("retry_suppressed",true).put("candidate_id",candidateId)
                        .put("recovery_guidance","This application content hung or lost its renderer. Inspect and change the source before testing again; changing test arguments is insufficient.")
                }
            }
        }
        val bundle=candidate.getJSONObject("bundle")
        val requested=WorkspaceCapabilityRegistry.requested(bundle.getJSONObject("manifest"))[WorkspaceBleContract.CAPABILITY].orEmpty()
        var terminalFailure: String? = null
        val reports=JSONArray();val radio=Radio();var runtimeEvidence:JSONObject?=null
        val succeeded=linkedMapOf<String,Int>();val failed=JSONArray()
        val isolated=withContext(Dispatchers.IO) {WorkspaceTestStorage(context,bundle)}
        val bridgeFailures=JSONArray();var hostFailureCount=0;var reloads=0;var recordAfterReload=false;var commits=0
        try {
        withContext(Dispatchers.Main.immediate) {
            var ready=CompletableDeferred<Unit>();var rendererFailure=CompletableDeferred<String>();var activeStep = -1;var executionFailure: String? = null;val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
            val principal=isolated.principal()
            fun makeBroker()=if(requested.isEmpty())null else WorkspaceDeviceBroker(principal,listOf(WorkspaceBleCapability(requested,radio,simulated=true)),{true},{true},{true},scope,isSimulation=true, audit={ event ->
                val details=event.getJSONObject("details");val method=details.optString("method").substringAfter('.')
                when(event.getString("kind")) {
                    "operation_succeeded" -> succeeded[method]=(succeeded[method] ?: 0)+1
                    "operation_failed", "request_rejected" -> failed.put(JSONObject(event.toString()))
                }
            })
            var broker: WorkspaceDeviceBroker?=null
            var presentation: WorkspaceWebPresentation?=null
            suspend fun evaluate(script: String): String = withTimeout(3_000) {
                val response=CompletableDeferred<String>()
                presentation!!.view.evaluateJavascript(script) { response.complete(it) }
                select { response.onAwait { it }; rendererFailure.onAwait { error(it) } }
            }
                fun collect() {
                    val diagnostic=presentation?.diagnostics() ?: return
                    hostFailureCount+=diagnostic.optInt("host_failure_count")
                    val errors=diagnostic.optJSONArray("host_failures") ?: JSONArray()
                    for(j in 0 until errors.length()) {if(bridgeFailures.length()>=16)bridgeFailures.remove(0);bridgeFailures.put(errors.getJSONObject(j))}
                    val calls=diagnostic.optJSONArray("host_calls") ?: JSONArray()
                    for(j in 0 until calls.length()) {val c=calls.getJSONObject(j);if(c.optString("method")=="data.commit" && c.optString("status")=="succeeded")commits++}
                }
            try {
              withTimeout(30_000) {
                suspend fun open() {
                    ready=CompletableDeferred()
                    rendererFailure=CompletableDeferred()
                    broker=makeBroker()
                    val surface=WorkspaceWebPresentation(context,bundle,principal,{true},{ready.complete(Unit)},{rendererFailure.complete(it);ready.completeExceptionally(IllegalStateException(it))},data=isolated.data,devices=broker,testRandomValues=randomValues)
                    presentation=surface;surface.view.layout(0,0,1080,900);surface.load()
                    withTimeout(8_000) {ready.await()}
                }
                open()
                for(i in 0 until steps.length()) {
                    activeStep=i
                    val step=steps.getJSONObject(i);val action=step.getString("action");val selector=JSONObject.quote(step.getString("selector"));val value=JSONObject.quote(step.optString("value"))
                    val script="""(()=>{const matches=document.querySelectorAll($selector);const e=matches[0];if(${"\"$action\""}==='assert_absent')return matches.length===0?'ok':'present';if(${"\"$action\""}==='assert_count')return JSON.stringify({status:matches.length===Number($value)?'ok':'count_mismatch',expected_count:Number($value),actual_count:matches.length});if(!e)return 'missing';if(${"\"$action\""}==='set_checked'){if(e.disabled)return 'disabled';if(e.tagName!=='INPUT'||!['checkbox','radio'].includes(e.type))return 'not_checkable';e.checked=($value==='true');e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));return 'ok';}if(${"\"$action\""}==='click'){if(e.disabled)return 'disabled';if(typeof e.click==='function')e.click();else e.dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true,view:window}));return 'ok';}if(${"\"$action\""}==='input'||${"\"$action\""}==='select'){if(e.disabled)return 'disabled';if(${"\"$action\""}==='select'&&e.tagName!=='SELECT')return 'not_select';e.value=$value;e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));return e.value===$value?'ok':'value_mismatch';}if(${"\"$action\""}==='assert_value')return e.value===$value?'ok':'value_mismatch';if(${"\"$action\""}==='assert_visible'||${"\"$action\""}==='assert_hidden'){const shown=!e.hidden&&e.getClientRects().length>0&&getComputedStyle(e).visibility!=='hidden';return shown===(${"\"$action\""}==='assert_visible')?'ok':'visibility_mismatch';}if(${"\"$action\""}==='assert_text_exact')return (e.textContent||'').trim()===$value?'ok':'mismatch';if(${"\"$action\""}==='assert_attribute'){const a=JSON.parse($value);return e.getAttribute(a.name)===a.equals?'ok':'attribute_mismatch';}return (e.textContent||'').includes($value)?'ok':'mismatch';})()"""
                    var outcome=when(action) {
                        "drag", "drag_cancel" -> WorkspacePointerGesture.drag(presentation!!.view,step.getString("selector"),step.getString("value"),action=="drag_cancel")
                        "reload" -> {collect();presentation?.dispose();presentation=null;reloads++;open();"\"ok\""}
                        "assert_record" -> {
                            val expected=JSONObject(step.getString("value"))
                            WorkspaceApplicationContract.exactKeys(expected,setOf("collection","data"),setOf("collection","data"))
                            val records=withContext(Dispatchers.IO) {isolated.data.query(principal,JSONObject().put("collection",expected.getString("collection")).put("limit",100)).getJSONArray("records")}
                            val found=(0 until records.length()).any {WorkspaceApplicationContract.canonical(records.getJSONObject(it).getJSONObject("data"))==WorkspaceApplicationContract.canonical(expected.getJSONObject("data"))}
                            if(found && reloads>0 && commits>0)recordAfterReload=true
                            if(found)"\"ok\"" else "\"record_missing\""
                        }
                        else -> evaluate(script)
                    }
                    if(action=="wait_text") { val end=android.os.SystemClock.elapsedRealtime()+3_000;while(outcome!="\"ok\"" && android.os.SystemClock.elapsedRealtime()<end) { delay(100);outcome=evaluate(script) } }
                    val countEvidence=if(action=="assert_count") runCatching {JSONObject(JSONArray("[$outcome]").getString(0))}.getOrNull() else null
                    val passed=outcome=="\"ok\"" || countEvidence?.optString("status")=="ok"
                    reports.put(JSONObject().put("index",i).put("action",action).put("selector",step.getString("selector")).put("passed",passed).put("outcome",outcome.take(100)))
                    countEvidence?.let { evidence ->
                        reports.getJSONObject(reports.length()-1).put("expected_count",evidence.getInt("expected_count")).put("actual_count",evidence.getInt("actual_count"))
                        if(!passed) reports.getJSONObject(reports.length()-1).put("message","Expected ${evidence.getInt("expected_count")} matching elements; found ${evidence.getInt("actual_count")}")
                    }
                    if(!passed) {
                        val report=reports.getJSONObject(reports.length()-1)
                        report.put("actual_text",evaluate("(()=>{if(!$selector)return 'No matching isolated record';return document.querySelector($selector)?.textContent?.slice(0,500)||'';})()"))
                        val snapshot=evaluate("""JSON.stringify({controls:Array.from(document.querySelectorAll('button,input,select')).slice(0,32).map(e=>({tag:e.tagName,id:e.id,label:(e.getAttribute('aria-label')||e.textContent||e.getAttribute('placeholder')||'').slice(0,120),disabled:!!e.disabled,value:(e.value||'').slice(0,100),options:e.tagName==='SELECT'?Array.from(e.options).slice(0,8).map(o=>({value:o.value,label:o.text.slice(0,80)})):undefined})),status:(document.querySelector('[role=status]')?.textContent||'').slice(0,300)})""")
                        runCatching { report.put("ui_state",JSONObject(JSONArray("[$snapshot]").getString(0))) }
                        report.put("failure_kind",if(outcome in setOf("null","\"not_select\"","\"not_checkable\"","\"outside_viewport\"","\"unsupported_pointer_events\""))"unsupported_interaction" else "assertion_or_precondition_failed")
                        report.put("recovery_guidance","Compare the failed action with controls and prerequisite selection/input state. A disabled control is not a radio failure. Correct the test or implementation, then rerun; assert requested behavior, not merely generic success labels.")
                        break
                    }
                    // Let asynchronous UI handlers dispatch their host requests before the next assertion/action.
                    delay(300)
                }
                if(requested.contains("subscribe")) { val end=android.os.SystemClock.elapsedRealtime()+2_000;while((radio.calls["events"] ?: 0)==0 && (radio.calls["subscribe"] ?: 0)>0 && android.os.SystemClock.elapsedRealtime()<end)delay(100) }
              }
            } catch (timeout: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive() // Caller cancellation must remain cancellation.
                executionFailure="execution_timeout"
            } catch (error: IllegalStateException) {
                if(!rendererFailure.isCompleted) throw error
                executionFailure=if(error.message?.contains("unresponsive")==true)"execution_timeout" else "renderer_failure"
            } finally {
                // Cleanup is required even when the caller cancelled during a suspended WebView callback.
                val interrupted = !currentCoroutineContext().isActive
                withContext(NonCancellable) {
                    if(executionFailure!=null || interrupted) presentation?.terminateRenderer()
                    collect();runtimeEvidence=presentation?.diagnostics();presentation?.dispose();broker?.close();scope.cancel()
                }
            }
            if(executionFailure!=null) {
                val step=steps.optJSONObject(activeStep)
                reports.put(JSONObject().put("index",activeStep).put("action",step?.optString("action") ?: "startup")
                    .put("selector",step?.optString("selector") ?: "").put("passed",false).put("outcome",executionFailure)
                    .put("failure_kind",executionFailure))
            }
            terminalFailure=executionFailure
        }
        val required=requested+if("subscribe" in requested)setOf("events") else emptySet()
        val missing=required.filter { (succeeded[it] ?: 0)==0 }
        val notificationAsserted = (0 until reports.length()).any { index ->
            val step=steps.optJSONObject(index) ?: return@any false
            reports.getJSONObject(index).optBoolean("passed") && step.getString("action") in setOf("assert_text","wait_text") &&
                step.getString("value").contains("2b", ignoreCase=true)
        }
        val notificationVerified = "subscribe" !in requested || radio.delivered && notificationAsserted
        val hostFailures=(0 until bridgeFailures.length()).map {bridgeFailures.getJSONObject(it)}
        val storageVerified=recordAfterReload
        val passed=terminalFailure==null && (!verifyStorage || storageVerified) && hostFailureCount==0 && reports.length()==steps.length() && (0 until reports.length()).all { reports.getJSONObject(it).getBoolean("passed") } && missing.isEmpty() && notificationVerified
        val verified=if(passed)(0 until steps.length()).mapNotNull {steps.getJSONObject(it).optString("criterion").trim().takeIf(String::isNotBlank)}.distinct() else emptyList()
        val id=UUID.randomUUID().toString()
        val result=JSONObject().put("check_id",id).put("candidate_id",candidateId).put("content_hash",hash).put("base_revision_id",base).put("data_version",version)
            .put("storage_verified",storageVerified).put("storage_scope","isolated_test_database").put("storage_evidence",JSONObject().put("commits",commits).put("reloads",reloads).put("record_asserted_after_reload",recordAfterReload)).put("host_failure_count",hostFailureCount).put("host_failures",JSONArray(hostFailures)).put("random_values",randomValues?.let(::JSONArray)).put("runtime",runtimeEvidence ?: JSONObject.NULL).put("kind","ui_simulation").put("passed",passed).put("steps",reports).put("operation_counts",JSONObject(radio.calls as Map<*,*>)).put("missing_operations",JSONArray(missing))
            .put("successful_operation_counts",JSONObject(succeeded as Map<*,*>)).put("failed_operations",failed)
            .put("criterion_evidence",JSONArray((0 until reports.length()).mapNotNull {i -> steps.optJSONObject(i)?.optString("criterion")?.takeIf(String::isNotBlank)?.let {JSONObject().put("criterion",it).put("assertion",JSONObject().put("action",steps.getJSONObject(i).getString("action")).put("selector",steps.getJSONObject(i).getString("selector")).put("expected_preview",steps.getJSONObject(i).optString("value").take(200)).put("expected_sha256",WorkspaceApplicationContract.hash(steps.getJSONObject(i).optString("value")))).put("passed",reports.getJSONObject(i).optBoolean("passed"))}}))
            .put("failure_kind",if(terminalFailure!=null)terminalFailure else if(hostFailureCount>0)"application_host_error" else if((0 until reports.length()).any {reports.getJSONObject(it).optString("failure_kind")=="unsupported_interaction"})"unsupported_interaction" else if(!passed)"assertion_or_coverage_failed" else JSONObject.NULL).put("gesture_scope","synthetic_dom_pointer_events_with_simulated_capture; physical touch unverified").put("semantic_correctness_verified",false).put("verified_criteria",JSONArray(verified)).put("verification_version",3).put("notification_verified",notificationVerified).put("notification_delivered",radio.delivered)
            .put("evidence_scope","DOM controls, isolated SQLite storage when exercised, and simulated BLE responses. storage_verified requires commit, page reload and exact record assertion. No live phone records, physical device, Android/native consent dialog or painted pixels were verified.")
        currentCoroutineContext().ensureActive()
        WorkspaceApplicationData(database).atomic {
            require(database.loadComposableWorkspace(workspaceId)?.liveRevisionId==base && WorkspaceApplicationData(database).version(workspaceId)==version) { "REVISION_CONFLICT" }
            require(repository.inspect(workspaceId,realmId,candidateId).getString("content_hash")==hash) { "PACKAGE_CORRUPT" }
            val count=rawQuery("SELECT COUNT(*) FROM workspace_application_checks WHERE workspace_id=?",arrayOf(workspaceId)).use { it.moveToFirst();it.getInt(0) };require(count<256) { "QUOTA_EXCEEDED" }
            insertOrThrow("workspace_application_checks",null,ContentValues().apply { put("check_id",id);put("workspace_id",workspaceId);put("candidate_id",candidateId);put("content_hash",hash);put("kind","ui_simulation");put("passed",if(passed)1 else 0);put("base_revision_id",base);put("data_version",version);put("result_json",result.toString());put("created_at",System.currentTimeMillis()) })
            committed(result)
        }
        result
        } finally {withContext(NonCancellable+Dispatchers.IO) {isolated.close()}}
    }
}
