package com.grinningfrog.atlas.workspace.visual

import android.content.ContentValues
import android.graphics.BitmapFactory
import com.grinningfrog.atlas.data.AtlasDatabase
import com.grinningfrog.atlas.media.MediaRepository
import com.grinningfrog.atlas.model.*
import com.grinningfrog.atlas.runtime.*
import com.grinningfrog.atlas.workspace.WorkspaceStatus
import com.grinningfrog.atlas.workspace.application.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

/** Stable observations/inputs only. The replaceable runtime decides whether and how to test. */
internal object WorkspaceVisualTools {
    fun create(database:AtlasDatabase,media:MediaRepository,client:WorkspaceVisualClient,cache:File):List<AtlasToolAdapter> =
        listOf(Status(database,client), Test(database,media,client,cache))
    private fun owner(database:AtlasDatabase,call:AtlasToolCall):Pair<AtlasSession,String> {
        val original=database.loadSession(call.sessionId) ?: error("SESSION_UNAVAILABLE")
        val tasks=ExecutionTaskStore(database);val task=tasks.forTurn(call.turnId) ?: error("VISUAL_TEST_REQUIRES_DURABLE_TASK")
        require(tasks.currentTurn(task.id)==call.turnId) {"STALE_VISUAL_SLICE"}
        require(task.sessionId==original.id && task.state !in setOf(ExecutionState.CANCELLED,ExecutionState.COMPLETED,ExecutionState.FAILED,ExecutionState.PAUSED))
        return tasks.workspaceSession(original,call.turnId) to "${original.id}:${task.id}"
    }
    private class Status(private val database:AtlasDatabase,private val client:WorkspaceVisualClient):AtlasToolAdapter {
        override val supportsLocalDiagnostics=true
        override val definition=ToolDefinition("workspace_visual_status","Discover optional autonomous workspace visual testing and the actual phone display profile. Use when rendered layout, canvas, touch behavior or visual verification matters. Does not open the workspace, start a WebView, or make visual testing a default activation gate.","""{"type":"object","properties":{},"additionalProperties":false}""",ToolRisk.READ_ONLY,maxCallsPerTurn=1)
        override suspend fun execute(call:AtlasToolCall):ToolExecutionResult {
            WorkspaceApplicationContract.exactKeys(JSONObject(call.argumentsJson),emptySet())
            val (_,owner)=owner(database,call)
            val profile=withContext(Dispatchers.Main.immediate) {runCatching {WorkspaceViewport.snapshot()}}
            return ToolExecutionResult(JSONObject().put("available",profile.isSuccess && android.os.Build.VERSION.SDK_INT>=29).put("renderer_verification","requires_private_display_calibration_on_open").put("device_profile",profile.getOrNull()).put("unavailable_reason",profile.exceptionOrNull()?.message)
                .put("tool","workspace_visual_test").put("instances",client.records(owner)).put("optional",true).put("activation_requires_visual_testing",false)
                .put("budgets",JSONObject().put("max_pixels",WorkspaceVisualLimits.MAX_PIXELS).put("max_instances",1).put("idle_ms",WorkspaceVisualLimits.IDLE_MS).put("lifetime_ms",WorkspaceVisualLimits.LIFETIME_MS).put("operations",WorkspaceVisualLimits.MAX_OPERATIONS))
                .put("workflow","When useful: open an immutable staged candidate, inspect/capture, interact with assertions, capture the resulting state, finish for a bound receipt, close. Reuse the same handle across task slices. Static/pure/UI gates remain unchanged. Do not claim unrun visual or physical-device tests.")
                .put("limitations","Atlas must remain visible and unlocked. Private-display calibration runs on this device at open. Test storage is isolated; BLE is simulated, text/keys are synthetic DOM input; native taps/swipes hit only the test WebView. Scrollable overflow is not automatically a defect. Alternative viewports are responsive probes, not the actual device profile.").toString())
        }
    }
    private class Test(private val database:AtlasDatabase,private val media:MediaRepository,private val client:WorkspaceVisualClient,private val cache:File):AtlasToolAdapter {
        override val supportsLocalMutation=true
        override val definition=ToolDefinition("workspace_visual_test","Optionally render, inspect and exercise a staged workspace independently of the visible UI. open requires workspace_id and candidate_id; later actions require instance_id. inspect returns geometry/errors; capture attaches real private-window pixels to inference; interact accepts up to16 fixed steps (DOM click/input/select/assertions or native WebView tap/swipe in CSS coordinates). finish persists optional revision/data-bound evidence: requires a screenshot after the latest interactions, at least one passing assertion, and no recorded test/runtime errors. close releases resources. No live storage, radio, OS input or native consent access. Choose testing by need; no mandatory visual activation gate.",SCHEMA,ToolRisk.SESSION_WRITE,maxCallsPerTurn=10)
        override fun evaluate(session:AtlasSession,proposal:ToolCallProposal):ToolPolicyDecision {
            val args=runCatching {JSONObject(proposal.argumentsJson)}.getOrNull()
            val action=args?.optString("action")
            val allowed=session.status==SessionStatus.ACTIVE && (action!="capture" || session.permissions.observe)
            return ToolPolicyDecision(allowed,false,ToolRisk.SESSION_WRITE,"Isolated task-owned local testing; pixels require observation permission")
        }
        override suspend fun execute(call:AtlasToolCall):ToolExecutionResult = withContext(Dispatchers.Main.immediate) { executeMain(call) }
        private suspend fun executeMain(call:AtlasToolCall):ToolExecutionResult {
            val args=JSONObject(call.argumentsJson)
            WorkspaceApplicationContract.exactKeys(args,setOf("action","workspace_id","candidate_id","instance_id","width_css","height_css","steps"),setOf("action"))
            val action=args.getString("action");require(action in setOf("open","inspect","capture","interact","finish","close"))
            val (session,owner)=owner(database,call)
            val execution=ExecutionTaskStore(database)
            if(action=="finish")execution.receipt(call)?.let {return ToolExecutionResult(it)}
            val task=execution.forTurn(call.turnId)!!
            val lease=ExecutionLease(task.id,requireNotNull(task.owner),task.generation)
            execution.check(lease)
            if(action=="open")return open(call,session,owner,args,lease)
            require(!args.has("workspace_id") && !args.has("candidate_id") && !args.has("width_css") && !args.has("height_css")) {"Use only instance_id after open"}
            require(args.has("steps")== (action=="interact"))
            val record=client.owned(args.getString("instance_id"),owner,allowClosed=action=="close")
            if(action=="close") {client.close(record,"agent_closed");return ToolExecutionResult(JSONObject().put("closed",true).put("instance_id",args.getString("instance_id")).toString())}
            assertCurrent(record,session)
            require(action!="capture" || database.loadSession(session.id)?.permissions?.observe==true) {"OBSERVATION_DENIED"}
            val currentProfile=withContext(Dispatchers.Main.immediate) {WorkspaceViewport.snapshot()}
            for(key in listOf("body_width_px","body_height_px","density_dpi","font_scale","orientation","display_id")) {
                require(currentProfile.get(key).toString()==record.getJSONObject("device_profile").get(key).toString()) {"VISUAL_VIEWPORT_CHANGED: close and reopen against the current device layout"}
            }
            val request=JSONObject().put("action",action).put("owner",owner).put("instance_id",record.getString("instance_id"))
            if(action=="interact") {
                val steps=args.getJSONArray("steps");require(steps.length() in 1..16)
                for(i in 0 until steps.length())WorkspaceVisualInteractions.validate(steps.getJSONObject(i))
                request.put("steps",steps)
            }
            val started=System.currentTimeMillis()
            val response=try {client.request(request,call.id)} catch(error:Throwable) {client.retire(record,"operation_failed_or_cancelled");throw error}
            try {
                execution.check(lease);assertCurrent(record,session)
                val result=response.json.put("candidate_id",record.getString("candidate_id")).put("workspace_id",record.getString("workspace_id"))
                    .put("content_hash",record.getString("content_hash")).put("base_revision_id",record.getString("base_revision_id")).put("data_version",record.getLong("data_version"))
                if(action=="interact") {record.remove("observation_id");client.update(record)}
                if(action=="capture") {
                    val bitmap=withContext(Dispatchers.IO) {android.os.ParcelFileDescriptor.AutoCloseInputStream(requireNotNull(response.image)).use {BitmapFactory.decodeStream(it)}} ?: error("VISUAL_CAPTURE_DECODE_FAILED")
                    val ref=media.ingestWorkspaceBitmap(bitmap,session.id)
                    try {
                        require(database.loadSession(session.id)?.permissions?.observe==true) {"OBSERVATION_DENIED"};execution.check(lease);assertCurrent(record,session)
                    } catch(error:Throwable) {media.deleteStorageKeys(listOf(ref.storageKey));throw error}
                    val observation=VisualObservation(sessionId=session.id,media=ref,observedAtMs=result.getLong("captured_at_ms"),availableAtMs=System.currentTimeMillis(),timing=ObservationTiming(ref.processingMs,0,ref.processingMs),summary="Isolated candidate workspace pixels; candidate=${record.getString("candidate_id")}; content=${record.getString("content_hash")}. Mock devices; not a physical camera observation or active workspace.")
                    database.saveObservation(observation)
                    record.put("observation_id",observation.id);client.update(record)
                    result.put("observation_id",observation.id).put("source","isolated_workspace_ui").put("screenshot_width",ref.width).put("screenshot_height",ref.height)
                    event(call,action,result,started)
                    return ToolExecutionResult(result.toString(),observation)
                }
                if(action=="finish") {
                    val passed=result.optBoolean("passed") && record.has("observation_id")
                    result.put("passed",passed).put("check_id",UUID.randomUUID().toString()).put("kind","visual_render").put("observation_id",record.optString("observation_id"))
                        .put("device_profile",record.getJSONObject("device_profile")).put("profile",record.getJSONObject("profile")).put("visual_design_verified_by_host",false)
                    if(!passed)result.put("verified_criteria",JSONArray())
                    WorkspaceApplicationData(database).atomic {
                        assertCurrent(record,session)
                        val count=rawQuery("SELECT COUNT(*) FROM workspace_application_checks WHERE workspace_id=?",arrayOf(record.getString("workspace_id"))).use {it.moveToFirst();it.getInt(0)};require(count<256)
                        insertOrThrow("workspace_application_checks",null,ContentValues().apply {
                            put("check_id",result.getString("check_id"));put("workspace_id",record.getString("workspace_id"));put("candidate_id",record.getString("candidate_id"));put("content_hash",record.getString("content_hash"));put("kind","visual_render");put("passed",if(passed)1 else 0);put("base_revision_id",record.getString("base_revision_id"));put("data_version",record.getLong("data_version"));put("result_json",result.toString());put("created_at",System.currentTimeMillis())
                        })
                        // Full geometry stays in the durable check; inspect is the opt-in detailed tool.
                        result.optJSONObject("layout")?.optJSONObject("viewport")?.let {result.put("viewport",it)}
                        result.remove("layout")
                        execution.localOperation(call,lease) {ToolExecutionResult(result.toString())}
                    }
                }
                event(call,action,result,started)
                return ToolExecutionResult(result.toString())
            } finally {response.image?.close()}
        }
        private suspend fun open(call:AtlasToolCall,session:AtlasSession,owner:String,args:JSONObject,lease:ExecutionLease):ToolExecutionResult {
            require(!args.has("steps") && !args.has("instance_id"))
            val id=args.getString("workspace_id")
            val workspace=database.loadComposableWorkspace(id) ?: error("WORKSPACE_UNAVAILABLE")
            require(workspace.realmId==session.workspaceId && workspace.status==WorkspaceStatus.ACTIVE)
            val target=ExecutionTaskStore(database).forTurn(call.turnId)?.document?.optJSONObject("workspace_target")?.optString("workspace_id")
            require(target.isNullOrEmpty() || target==id) {"TASK_TARGET_CONFLICT"}
            val candidate=WorkspacePackageRepository(database).inspect(id,session.workspaceId,args.getString("candidate_id"));WorkspaceCandidateState.requireTestable(candidate)
            val profile=withContext(Dispatchers.Main.immediate) {WorkspaceViewport.snapshot()}
            val render=JSONObject(profile.toString())
            val density=profile.getDouble("density")
            val width=if(args.has("width_css")) {require(args.get("width_css") is Int && args.getInt("width_css") in 64..2048);(args.getInt("width_css")*density).roundToInt()} else profile.getInt("body_width_px")
            val height=if(args.has("height_css")) {require(args.get("height_css") is Int && args.getInt("height_css") in 64..2048);render.put("alternative_height_css",args.getInt("height_css"));((args.getInt("height_css")+384*profile.getDouble("font_scale"))*density).roundToInt()} else profile.getInt("body_height_px")
            WorkspaceVisualLimits.dimensions(width,height,profile.getInt("density_dpi"))
            render.put("render_width_px",width).put("render_height_px",height).put("viewport_kind",if(args.has("width_css") || args.has("height_css"))"explicit_alternative" else "actual_device_workspace")
            val source=candidate.getJSONObject("bundle").toString()
            val request=JSONObject().put("action","open").put("owner",owner).put("profile",render).put("title",workspace.name).put("base_revision_id",candidate.getString("base_revision_id")).put("package_sha256",WorkspaceApplicationContract.hash(source))
            val file=withContext(Dispatchers.IO) {File.createTempFile("visual-candidate-",".json",cache).also {it.writeText(source)}}
            val version=WorkspaceApplicationData(database).version(id)
            val started=System.currentTimeMillis()
            val response=try {client.request(request,call.id,file)} finally {file.delete()}
            response.image?.close()
            val record=JSONObject().put("instance_id",response.json.getString("instance_id")).put("owner",owner).put("session_id",session.id).put("task_id",ExecutionTaskStore(database).forTurn(call.turnId)!!.id)
                .put("realm_id",session.workspaceId).put("workspace_id",id).put("candidate_id",candidate.getString("candidate_id")).put("content_hash",candidate.getString("content_hash")).put("base_revision_id",candidate.getString("base_revision_id"))
                .put("data_version",version).put("device_profile",profile).put("profile",render).put("state","open").put("opened_at_ms",started).put("touched_at_ms",System.currentTimeMillis())
            client.register(record)
            try {ExecutionTaskStore(database).check(lease);WorkspaceViewport.snapshot();assertCurrent(record,session)} catch(error:Throwable) {withContext(NonCancellable) {client.close(record,"candidate_changed_during_open")};throw error}
            val result=response.json.put("candidate_id",record.getString("candidate_id")).put("workspace_id",id).put("base_revision_id",record.getString("base_revision_id")).put("content_hash",record.getString("content_hash")).put("render_startup_ms",System.currentTimeMillis()-started)
            event(call,"open",result,started)
            return ToolExecutionResult(result.toString())
        }
        private fun assertCurrent(record:JSONObject,session:AtlasSession) {
            require(database.loadSession(session.id)?.status==SessionStatus.ACTIVE) {"SESSION_STOPPED"}
            val task=ExecutionTaskStore(database).get(record.getString("task_id")) ?: error("TASK_UNAVAILABLE")
            require(task.state !in setOf(ExecutionState.CANCELLED,ExecutionState.COMPLETED,ExecutionState.FAILED,ExecutionState.PAUSED)) {"TASK_STOPPED"}
            val workspace=database.loadComposableWorkspace(record.getString("workspace_id")) ?: error("WORKSPACE_UNAVAILABLE")
            require(workspace.realmId==session.workspaceId && workspace.status==WorkspaceStatus.ACTIVE)
            val candidate=WorkspacePackageRepository(database).inspect(workspace.id,session.workspaceId,record.getString("candidate_id"));WorkspaceCandidateState.requireTestable(candidate)
            require(candidate.getString("content_hash")==record.getString("content_hash") && workspace.liveRevisionId==record.getString("base_revision_id") && WorkspaceApplicationData(database).version(workspace.id)==record.getLong("data_version")) {"VISUAL_EVIDENCE_STALE: reopen the current candidate"}
        }
        private fun event(call:AtlasToolCall,action:String,result:JSONObject,started:Long) {database.appendEvent(call.sessionId,"workspace.visual_$action",JSONObject().put("call_id",call.id).put("duration_ms",System.currentTimeMillis()-started).put("candidate_id",result.optString("candidate_id")).put("instance_id",result.optString("instance_id",result.optJSONObject("instance")?.optString("instance_id"))).put("passed",result.opt("passed")).put("observation_id",result.optString("observation_id")).put("startup",result.optJSONObject("startup")).put("service_pid",result.optInt("service_pid")).put("service_pss_kb",result.optInt("service_pss_kb")))}
    }
    private val STEP=JSONObject().put("anyOf",JSONArray().apply {
        val variants=WorkspaceUiStepSchema.parameters().getJSONObject("properties").getJSONObject("steps").getJSONObject("items").getJSONArray("anyOf")
        for(i in 0 until variants.length()) {
            val variant=variants.getJSONObject(i)
            if(variant.getJSONObject("properties").getJSONObject("action").getJSONArray("enum").getString(0) in WorkspaceVisualInteractions.actions)put(variant)
        }
        for(action in listOf("tap","swipe","scroll","key")) {
            val properties=JSONObject().put("action",JSONObject().put("type","string").put("enum",JSONArray(listOf(action))))
            val value=if(action=="key")JSONObject().put("type","string").put("enum",JSONArray(listOf("ArrowUp","ArrowDown","ArrowLeft","ArrowRight","Enter","Escape"," ","w","a","s","d")))
            else {
                val names=if(action=="tap")listOf("x","y") else if(action=="swipe")listOf("x","y","to_x","to_y") else listOf("dx","dy")
                val fields=JSONObject();for(name in names)fields.put(name,JSONObject().put("type","number").put("minimum",if(action=="scroll")-8192 else 0).put("maximum",8192))
                if(action=="swipe")fields.put("duration_ms",JSONObject().put("type","integer").put("minimum",50).put("maximum",1000))
                JSONObject().put("type","object").put("properties",fields).put("required",JSONArray(names)).put("additionalProperties",false)
            }
            properties.put("value",value)
            put(JSONObject().put("type","object").put("properties",properties).put("required",JSONArray(listOf("action","value"))).put("additionalProperties",false))
        }
    })
    private val SCHEMA=JSONObject("""{"type":"object","properties":{"action":{"type":"string","enum":["open","inspect","capture","interact","finish","close"]},"workspace_id":{"type":"string"},"candidate_id":{"type":"string"},"instance_id":{"type":"string"},"width_css":{"type":"integer","minimum":64,"maximum":2048},"height_css":{"type":"integer","minimum":64,"maximum":2048},"steps":{"type":"array","minItems":1,"maxItems":16}},"required":["action"],"additionalProperties":false}""").apply {getJSONObject("properties").getJSONObject("steps").put("items",STEP)}.toString()
}
