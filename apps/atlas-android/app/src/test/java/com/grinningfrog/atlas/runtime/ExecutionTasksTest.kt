package com.grinningfrog.atlas.runtime

import android.app.Application
import com.grinningfrog.atlas.data.AtlasDatabase
import com.grinningfrog.atlas.data.ArchiveCodec
import com.grinningfrog.atlas.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ExecutionTasksTest {
    private lateinit var db: AtlasDatabase
    private lateinit var store: ExecutionTaskStore
    private lateinit var session: AtlasSession
    private var clock = 100_000L
    @Before fun setup() {
        RuntimeEnvironment.getApplication().deleteDatabase("atlas.db")
        db = AtlasDatabase(RuntimeEnvironment.getApplication())
        store = ExecutionTaskStore(db) { clock }
        session = db.createSession("Execution", "Finish the objective")
        db.updateSessionStatus(session.id, SessionStatus.ACTIVE)
        session = db.loadSession(session.id)!!
    }
    @After fun cleanup() { db.close() }
    private fun task(budget: ExecutionBudget = ExecutionBudget(), atomicTools: List<String> = listOf("local")): ExecutionTask =
        store.create(session, "Finish the objective", db.createTurn(session.id,"text").id, budget, atomicTools)
    private fun call(task: ExecutionTask, name: String = "local", args: String = "{}") = AtlasToolCall(UUID.randomUUID().toString(),session.id,store.currentTurn(task.id),name,args,ToolCallStatus.PROPOSED,ToolRisk.SESSION_WRITE,false,UUID.randomUUID().toString(),createdAtMs=clock,updatedAtMs=clock)
    private fun executor() = DurableTaskExecutor(store, {clock}, { clock += it })

    private fun delivered(t: ExecutionTask, lease: ExecutionLease, workspaceId: String, revisionId: String) {
        val c=call(t,"workspace_package_activate",JSONObject().put("workspace_id",workspaceId).put("candidate_id","candidate").toString())
        db.insertToolCall(c)
        store.localOperation(c,lease) {ToolExecutionResult(JSONObject().put("workspace_id",workspaceId).put("candidate_id","candidate").put("revision_id",revisionId).put("activated",true).toString())}
    }
    @Test fun obsoletePreExecutionBaseConflictRetiresOnlyAfterDeliveryOnSameWorkspace() {
        val w=db.createComposableWorkspace("Fresh delivery","")
        val t=task();val lease=store.claim(t.id,"runner")!!
        store.bindWorkspace(store.currentTurn(t.id),w.id,"activation")
        val failed=call(t,"workspace_package_ui_test",JSONObject().put("workspace_id",w.id).put("candidate_id","historical").toString())
        db.insertToolCall(failed)
        db.updateToolCall(failed.id,ToolCallStatus.FAILED,error="REVISION_CONFLICT",resultJson="""{"ok":false,"error_code":"CANDIDATE_BASE_CONFLICT"}""")
        assertEquals(1,store.outstanding(t.id).length())
        delivered(t,lease,w.id,w.liveRevisionId)
        assertEquals(0,store.outstanding(t.id).length())
        assertEquals(ToolCallStatus.FAILED,db.loadToolCall(failed.id)!!.status)
    }
    @Test fun refusedReplacementCreationRetiresOnlyAfterDeliveryToItsBoundTarget() {
        val workspace=db.createComposableWorkspace("Original","")
        val other=db.createComposableWorkspace("Other","")
        val t=task();val lease=store.claim(t.id,"runner")!!
        store.bindWorkspace(store.currentTurn(t.id),workspace.id,"activation")
        val failed=call(t,"workspace_package_create","""{"name":"Replacement"}""")
        db.insertToolCall(failed)
        db.updateToolCall(failed.id,ToolCallStatus.FAILED,error="EXISTING_TARGET_BOUND",resultJson=JSONObject()
            .put("error_code","EXISTING_TARGET_BOUND").put("recovery",JSONObject().put("workspace_id",workspace.id)).toString())
        assertEquals(1,store.outstanding(t.id).length())
        delivered(t,lease,other.id,other.liveRevisionId)
        assertEquals(1,store.outstanding(t.id).length())
        delivered(t,lease,workspace.id,workspace.liveRevisionId)
        assertEquals(0,store.outstanding(t.id).length())
        assertEquals(ToolCallStatus.FAILED,db.loadToolCall(failed.id)!!.status)
        db.updateToolCall(failed.id,ToolCallStatus.UNKNOWN,error="Unknown outcome")
        assertEquals("unknown_operation_outcome",store.finish(lease,SliceOutcome.RESPONDED,1)!!.waitReason)
    }
    @Test fun failedRuntimeProposalRetiresAfterApprovedReplacementButUnknownDoesNot() {
        val t=task();val lease=store.claim(t.id,"runner")!!
        val failed=call(t,"atlas_runtime_stage","""{"parent_runtime_id":"parent"}""")
        db.insertToolCall(failed);db.updateToolCall(failed.id,ToolCallStatus.FAILED,error="Bad proposal")
        assertEquals(1,store.outstanding(t.id).length())
        val activated=call(t,"atlas_runtime_activate")
        db.insertToolCall(activated)
        db.updateToolCall(activated.id,ToolCallStatus.COMPLETED,resultJson="""{"selected_for_new_tasks":true,"current_task_changed":false,"parent_runtime_id":"parent"}""")
        assertEquals(0,store.outstanding(t.id).length())
        db.updateToolCall(failed.id,ToolCallStatus.UNKNOWN,error="Unknown operation")
        assertTrue(store.pending(t.id).any {it.id==failed.id && it.status==ToolCallStatus.UNKNOWN})
        val blocked=store.finish(lease,SliceOutcome.RESPONDED,1)!!
        assertEquals(ExecutionState.WAITING,blocked.state)
        assertEquals("unknown_operation_outcome",blocked.waitReason)
    }
    @Test fun sourceReadRecoveryUsesDurableOperationsAndExcludesChangedCandidateBytes() {
        val bundle=com.grinningfrog.atlas.workspace.application.WorkspaceApplicationContract.example()
        val w=db.createComposableWorkspace("Source cache","",bundle.toString(),realmId=session.workspaceId)
        val t=task();store.claim(t.id,"runner")!!;store.bindWorkspace(store.currentTurn(t.id),w.id,"activation")
        val source=bundle.getJSONObject("files").getString("ui/app.js")
        val read=call(t,"workspace_package_read_file")
        db.insertToolCall(read);db.updateToolCall(read.id,ToolCallStatus.COMPLETED,resultJson=JSONObject().put("workspace_id",w.id).put("revision_id",w.liveRevisionId)
            .put("path","ui/app.js").put("start_char",0).put("source",source).put("source_sha256",com.grinningfrog.atlas.workspace.application.WorkspaceApplicationContract.hash(source)).toString())
        assertEquals(source,WorkspaceSourceContext.load(db,ExecutionTaskStore(db),t.id).single().getString("source"))
        val staged=com.grinningfrog.atlas.workspace.application.WorkspacePackageRepository(db).stage(w.id,w.realmId,w.liveRevisionId,null,null,
            JSONObject().put("ui/app.js",source+"\n// changed"),session.id,store.currentTurn(t.id))
        val stage=call(t,"workspace_package_stage");db.insertToolCall(stage);db.updateToolCall(stage.id,ToolCallStatus.COMPLETED,resultJson=staged.toString())
        assertTrue(WorkspaceSourceContext.load(db,store,t.id).isEmpty())
    }
    @Test fun runtimePinSurvivesSelectionChangeSliceAndStoreRecreation() {
        val t = task()
        assertEquals("core-a", store.pinRuntime(t.id, "core-a"))
        assertEquals("core-a", store.pinRuntime(t.id, "core-b"))
        val lease = store.claim(t.id, "runner")!!
        store.finish(lease, SliceOutcome.YIELDED, 1)
        assertEquals("core-a", ExecutionTaskStore(db).pinRuntime(t.id, "native"))
        assertEquals("core-b", store.pinRuntime(task().id, "core-b"))
    }
    @Test fun unavailableOptionalCaptureDoesNotBlockCommittedDeliveryButRequiredCaptureDoes() {
        for(required in listOf(false,true)) {
            val w=db.createComposableWorkspace("Capture $required", "")
            val t=task();val lease=store.claim(t.id,"owner")!!
            store.bindWorkspace(store.currentTurn(t.id),w.id,"activation",requirePixels=required)
            delivered(t,lease,w.id,w.liveRevisionId)
            val capture=call(t,"workspace_capture_view",JSONObject().put("workspace_id",w.id).toString());db.insertToolCall(capture)
            db.updateToolCall(capture.id,ToolCallStatus.FAILED,error="WORKSPACE_NOT_OPEN: Target workspace is not open for pixel capture")
            val result=store.finish(lease,SliceOutcome.RESPONDED,1)!!
            assertEquals(if(required) ExecutionState.WAITING else ExecutionState.COMPLETED,result.state)
            assertEquals(ToolCallStatus.FAILED,db.loadToolCall(capture.id)!!.status)
            assertEquals(if(required)1 else 0,store.outstanding(t.id).length())
            if(required)store.control(t.id,ExecutionState.CANCELLED,"test complete")
        }
    }
    @Test fun argumentBindingUsesTaskTargetAndDurableStageWithoutRepairingConflicts() {
        val workspace = db.createComposableWorkspace("Bound", "")
        val t = task(); val lease = store.claim(t.id, "owner")!!
        val turn = store.currentTurn(t.id)
        val sequence = store.bindWorkspace(turn, workspace.id, "activation")
        val stage = call(t, "workspace_package_stage", JSONObject().put("workspace_id", workspace.id).toString())
        db.insertToolCall(stage)
        store.localOperation(stage, lease) { ToolExecutionResult("""{"candidate_id":"exact-candidate"}""") }
        val user = AtlasMessage(sessionId=session.id, turnId=turn, role=MessageRole.USER, content="Test it", createdAtMs=clock)
        db.insertMessage(user)
        val bound = JSONObject(store.bindWorkspaceArguments(turn, ToolCallProposal("p", "workspace_accept_unverified", "{} ")).argumentsJson)
        assertEquals(workspace.id, bound.getString("workspace_id"))
        assertEquals("exact-candidate", bound.getString("candidate_id"))
        assertEquals(sequence, bound.getLong("navigation_sequence"))
        assertEquals(user.id, bound.getString("user_message_id"))
        assertEquals(user.content, bound.getString("user_quote"))
        val conflict = """{"workspace_id":"other","candidate_id":"explicit","navigation_sequence":-1}"""
        assertEquals(conflict, store.bindWorkspaceArguments(turn, ToolCallProposal("p", "workspace_package_activate", conflict)).argumentsJson)
        val explicit = JSONObject(store.bindWorkspaceArguments(turn, ToolCallProposal("p", "workspace_package_test", """{"candidate_id":"explicit"}""")).argumentsJson)
        assertEquals("explicit", explicit.getString("candidate_id"))
        val read = ToolCallProposal("p", "workspace_package_read_file", "{}")
        assertEquals(read, store.bindWorkspaceArguments(turn, read))
    }

    @Test fun manualAcceptanceRequiresNativeApprovalAndDoesNotTransferToNewBytes() {
        val w=db.createComposableWorkspace("Manual", "")
        val t=task();store.claim(t.id,"owner")!!
        val turn=store.currentTurn(t.id)
        val criterion="Drag the graph point"
        store.bindWorkspace(turn,w.id,"activation",org.json.JSONArray(listOf(criterion)))
        val quote="Push the change so I can test it myself"
        db.insertMessage(AtlasMessage(sessionId=session.id,turnId=turn,role=MessageRole.USER,content=quote,createdAtMs=clock))
        val bundle=com.grinningfrog.atlas.workspace.application.WorkspaceApplicationContract.example()
        val repo=com.grinningfrog.atlas.workspace.application.WorkspacePackageRepository(db)
        val candidate=repo.stage(w.id,w.realmId,w.liveRevisionId,null,bundle.getJSONObject("manifest"),bundle.getJSONObject("files"),session.id,turn).getString("candidate_id")
        val proposed=call(t,"workspace_accept_unverified")
        assertTrue(runCatching {store.acceptUnverified(proposed,candidate,org.json.JSONArray(listOf(criterion)),quote)}.isFailure)
        val approved=proposed.copy(status=ToolCallStatus.APPROVED,requiresConfirmation=true)
        assertTrue(runCatching {store.acceptUnverified(approved,candidate,org.json.JSONArray(listOf(criterion)),"invented approval")}.isFailure)
        val stale = approved.copy(argumentsJson=JSONObject().put("user_message_id", "older-user-message").toString())
        assertTrue(runCatching {store.acceptUnverified(stale,candidate,org.json.JSONArray(listOf(criterion)),quote)}.isFailure)
        val receipt=store.acceptUnverified(approved,candidate,org.json.JSONArray(listOf(criterion)),quote)
        assertFalse(receipt.getBoolean("tested"));assertTrue(store.missingAcceptance(turn,candidate).isEmpty())
        bundle.getJSONObject("files").put("ui/extra.js","export const changed=true;")
        val next=repo.stage(w.id,w.realmId,w.liveRevisionId,null,bundle.getJSONObject("manifest"),bundle.getJSONObject("files"),session.id,turn).getString("candidate_id")
        assertEquals(listOf(criterion),store.missingAcceptance(turn,next))
        assertEquals(1,ExecutionTaskStore(db).manualAcceptance(turn,candidate).length())
    }
    @Test fun rejectedOptionalHandoffCannotPoisonDeliveredTask() {
        val w=db.createComposableWorkspace("Delivered", "")
        val t=task();val lease=store.claim(t.id,"owner")!!
        store.bindWorkspace(store.currentTurn(t.id),w.id,"activation")
        delivered(t,lease,w.id,w.liveRevisionId)
        val handoff=call(t,"atlas_task_handoff_pixels", "{\"user_quote\":\"invented instruction\"}")
        db.insertToolCall(handoff);db.updateToolCall(handoff.id,ToolCallStatus.FAILED,error="user_quote must quote the latest user instruction in this task")
        assertEquals(ExecutionState.COMPLETED,store.finish(lease,SliceOutcome.RESPONDED,1)!!.state)
        assertEquals(ToolCallStatus.FAILED,db.loadToolCall(handoff.id)!!.status)
    }
    @Test fun captureFailureWithoutDeliveryOrWithOtherErrorRemainsBlocking() {
        for(error in listOf("WORKSPACE_NOT_OPEN","Workspace changed during screenshot capture; retry")) {
            val w=db.createComposableWorkspace("Capture", "")
            val t=task();val lease=store.claim(t.id,"owner")!!
            store.bindWorkspace(store.currentTurn(t.id),w.id)
            if(error!="WORKSPACE_NOT_OPEN")delivered(t,lease,w.id,w.liveRevisionId)
            val capture=call(t,"workspace_capture_view",JSONObject().put("workspace_id",w.id).toString());db.insertToolCall(capture)
            db.updateToolCall(capture.id,ToolCallStatus.FAILED,error=error)
            assertEquals(ExecutionState.WAITING,store.finish(lease,SliceOutcome.RESPONDED,1)!!.state)
            store.control(t.id,ExecutionState.CANCELLED,"test complete")
        }
    }
    @Test fun conversationalFollowUpPreservesOriginalTaskAndRequiredFailure() {
        val t=task();val lease=store.claim(t.id,"owner")!!
        val failed=call(t,"workspace_package_activate");db.insertToolCall(failed);db.updateToolCall(failed.id,ToolCallStatus.FAILED,error="STARTUP_FAILED")
        store.finish(lease,SliceOutcome.RESPONDED,1)
        assertEquals(t.id,store.followUpTask(session.id)?.id)
        val reply=db.createTurn(session.id,"text")
        db.insertMessage(AtlasMessage(sessionId=session.id,turnId=reply.id,role=MessageRole.USER,content="Why is it blocked?",createdAtMs=clock))
        val attached=store.attachReply(t.id,reply.id)
        assertEquals(t.id,attached.id);assertEquals("Finish the objective",attached.objective)
        assertEquals(reply.id,store.currentTurn(t.id));assertEquals(1,store.list(session.id).size)
        assertTrue(store.context(t.id).contains("STARTUP_FAILED"))
        assertEquals(ExecutionState.WAITING,store.finish(store.claim(t.id,"followup")!!,SliceOutcome.RESPONDED,1)!!.state)
        val other=task()
        assertNull(store.followUpTask(session.id))
        store.control(other.id,ExecutionState.CANCELLED,"test complete")
    }
    @Test fun userVisualHandoffIsDurableWithoutWaivingExecutionFailures() {
        val w=db.createComposableWorkspace("Handoff", "")
        val t=task();val lease=store.claim(t.id,"owner")!!
        store.bindWorkspace(store.currentTurn(t.id),w.id,"activation",requirePixels=true)
        delivered(t,lease,w.id,w.liveRevisionId)
        val capture=call(t,"workspace_capture_view",JSONObject().put("workspace_id",w.id).toString());db.insertToolCall(capture);db.updateToolCall(capture.id,ToolCallStatus.FAILED,error="WORKSPACE_NOT_OPEN")
        store.finish(lease,SliceOutcome.RESPONDED,1)
        val turn=db.createTurn(session.id,"text");val quote="I can verify it myself no need to worry about pixel capture"
        db.insertMessage(AtlasMessage(sessionId=session.id,turnId=turn.id,role=MessageRole.USER,content=quote,createdAtMs=clock))
        store.attachReply(t.id,turn.id);val next=store.claim(t.id,"reply")!!
        val handoff=call(t,"atlas_task_handoff_pixels");db.insertToolCall(handoff)
        assertTrue(runCatching {store.handOffPixels(handoff,"Fabricated instruction")}.isFailure)
        val receipt=store.localOperation(handoff,next) {ToolExecutionResult(store.handOffPixels(handoff,quote).toString())}
        assertFalse(JSONObject(receipt.resultJson).getBoolean("pixels_verified"))
        val failedCheck=call(t,"workspace_package_test");db.insertToolCall(failedCheck);db.updateToolCall(failedCheck.id,ToolCallStatus.COMPLETED,resultJson="""{"passed":false,"candidate_id":"candidate"}""")
        assertEquals(ExecutionState.READY,store.finish(next,SliceOutcome.RESPONDED,1)!!.state)
        store.nextTurn(t.id);val corrected=store.claim(t.id,"corrected")!!
        val passedCheck=call(t,"workspace_package_test");db.insertToolCall(passedCheck);db.updateToolCall(passedCheck.id,ToolCallStatus.COMPLETED,resultJson="""{"passed":true,"candidate_id":"candidate"}""")
        assertEquals(ExecutionState.COMPLETED,store.finish(corrected,SliceOutcome.RESPONDED,1)!!.state)
        assertEquals("user",store.get(t.id)!!.document.getJSONObject("completion_requirement").getString("pixels_owner"))
        assertEquals(ToolCallStatus.FAILED,db.loadToolCall(capture.id)!!.status)
    }
    @Test fun permissionAndUnknownWaitsDoNotAcceptImplicitFollowUps() {
        for(outcome in listOf(SliceOutcome.WAITING_PERMISSION,SliceOutcome.WAITING_PROVIDER,SliceOutcome.WAITING_USER)) {
            val t=task();val lease=store.claim(t.id,"owner")!!;store.finish(lease,outcome,1)
            assertNull(store.followUpTask(session.id));store.control(t.id,ExecutionState.CANCELLED,"test complete")
        }
    }
    @Test fun acceptanceCriteriaSurviveSlicesAndRequirePassingAssertionsForExactCandidate() {
        val w=db.createComposableWorkspace("Acceptance", "")
        val t=task();val lease=store.claim(t.id,"owner")!!
        store.bindWorkspace(store.currentTurn(t.id),w.id,"activation",org.json.JSONArray(listOf("Errors filter hides success events")))
        store.bindWorkspace(store.currentTurn(t.id),w.id,"activation",org.json.JSONArray())
        fun evidence(candidate:String,passed:Boolean) {
            clock++
            val c=call(t,"workspace_package_ui_test",JSONObject().put("workspace_id",w.id).put("candidate_id",candidate).toString());db.insertToolCall(c)
            db.updateToolCall(c.id,ToolCallStatus.COMPLETED,resultJson=JSONObject().put("candidate_id",candidate).put("passed",passed).put("verified_criteria",org.json.JSONArray(listOf("Errors filter hides success events"))).toString())
        }
        evidence("other",true);evidence("candidate",false)
        assertEquals(1,store.missingAcceptance(store.currentTurn(t.id),"candidate").size)
        store.finish(lease,SliceOutcome.YIELDED,1);val next=store.nextTurn(t.id);store.claim(t.id,"next")!!
        assertEquals(1,store.missingAcceptance(next,"candidate").size)
        evidence("candidate",true)
        assertTrue(store.missingAcceptance(next,"candidate").isEmpty())
    }
    @Test fun staleDeliveredWaitIsReconciledWithoutAnotherInferenceSlice() {
        val w=db.createComposableWorkspace("Delivered", "")
        val t=task();val lease=store.claim(t.id,"owner")!!;store.bindWorkspace(store.currentTurn(t.id),w.id,"activation")
        delivered(t,lease,w.id,w.liveRevisionId)
        val capture=call(t,"workspace_capture_view",JSONObject().put("workspace_id",w.id).toString());db.insertToolCall(capture);db.updateToolCall(capture.id,ToolCallStatus.FAILED,error="WORKSPACE_NOT_OPEN")
        store.finish(lease,SliceOutcome.RESPONDED,1)
        // Simulate an alpha.26 persisted WAITING row with a now-classified optional capture.
        db.writableDatabase.update("execution_tasks",android.content.ContentValues().apply {put("state","WAITING");put("document_json",store.get(t.id)!!.document.put("wait_reason","unresolved_tool_failure").toString())},"task_id=?",arrayOf(t.id))
        val before=store.turns(t.id).size
        assertEquals(ExecutionState.COMPLETED,store.reconcileDelivered(t.id)!!.state)
        assertEquals(before,store.turns(t.id).size)
        assertEquals(0,store.get(t.id)!!.document.getJSONObject("checkpoint").getJSONArray("unresolved_operation_ids").length())
        assertEquals(capture.id,store.get(t.id)!!.document.getJSONObject("checkpoint").getJSONArray("diagnostic_operation_ids").getString(0))
    }
    @Test fun resumeCannotEraseAnUnperformedRequiredPixelCheck() {
        val w=db.createComposableWorkspace("Required pixels", "")
        val t=task();val lease=store.claim(t.id,"owner")!!;store.bindWorkspace(store.currentTurn(t.id),w.id,"activation",requirePixels=true)
        delivered(t,lease,w.id,w.liveRevisionId)
        store.finish(lease,SliceOutcome.RESPONDED,1)
        store.control(t.id,ExecutionState.WAITING,"repeated_failure")
        assertEquals(ExecutionState.WAITING,store.reconcileDelivered(t.id)!!.state)
    }

    @Test fun isolatedPixelsRequireNativeFinishAndMatchingCurrentActivation() {
        val w=db.createComposableWorkspace("Isolated pixels", "")
        val t=task();val lease=store.claim(t.id,"owner")!!
        store.bindWorkspace(store.currentTurn(t.id),w.id,"activation",requirePixels=true)
        val capture=call(t,"workspace_visual_test","{\"action\":\"capture\",\"instance_id\":\"handle\"}");db.insertToolCall(capture)
        db.updateToolCall(capture.id,ToolCallStatus.COMPLETED,resultJson=JSONObject().put("workspace_id",w.id).put("candidate_id","candidate").put("observation_id","pixels").toString())
        delivered(t,lease,w.id,w.liveRevisionId)
        assertEquals(ExecutionState.READY,store.finish(lease,SliceOutcome.RESPONDED,1)!!.state)
        val next=store.claim(t.id,"owner")!!
        val finish=call(t,"workspace_visual_test","{\"action\":\"finish\",\"instance_id\":\"handle\"}");db.insertToolCall(finish)
        val result=JSONObject().put("workspace_id",w.id).put("candidate_id","candidate").put("kind","visual_render").put("check_id","visual-check").put("passed",true).put("observation_id","pixels")
        store.localOperation(finish,next) {ToolExecutionResult(result.toString())}
        // A finish alone cannot attest the current active revision.
        assertEquals(ExecutionState.READY,store.finish(next,SliceOutcome.RESPONDED,1)!!.state)
        db.writableDatabase.insertOrThrow("workspace_application_activations",null,android.content.ContentValues().apply {
            put("workspace_id",w.id);put("candidate_id","candidate");put("check_id","pure-check");put("created_at",clock)
            put("result_json",JSONObject().put("revision_id",w.liveRevisionId).put("visual_check_id","other-check").toString())
        })
        val wrong=store.claim(t.id,"owner")!!;assertEquals(ExecutionState.READY,store.finish(wrong,SliceOutcome.RESPONDED,1)!!.state)
        db.writableDatabase.update("workspace_application_activations",android.content.ContentValues().apply {put("result_json",JSONObject().put("revision_id",w.liveRevisionId).put("visual_check_id","visual-check").toString())},"workspace_id=?",arrayOf(w.id))
        val matching=store.claim(t.id,"owner")!!;assertEquals(ExecutionState.COMPLETED,store.finish(matching,SliceOutcome.RESPONDED,1)!!.state)
    }
    @Test fun visualCloseCannotHideAnUnrepairedFailedInteraction() {
        val w=db.createComposableWorkspace("Visual failure", "")
        val t=task();val lease=store.claim(t.id,"owner")!!;store.bindWorkspace(store.currentTurn(t.id),w.id,"activation")
        val failed=call(t,"workspace_visual_test",JSONObject().put("action","interact").put("workspace_id",w.id).toString());db.insertToolCall(failed)
        db.updateToolCall(failed.id,ToolCallStatus.COMPLETED,resultJson=JSONObject().put("workspace_id",w.id).put("passed",false).toString())
        val close=call(t,"workspace_visual_test","{\"action\":\"close\"}");db.insertToolCall(close);db.updateToolCall(close.id,ToolCallStatus.COMPLETED,resultJson="{\"closed\":true}")
        assertEquals(1,store.outstanding(t.id).length())
        val fixed=call(t,"workspace_visual_test","{\"action\":\"finish\"}");db.insertToolCall(fixed)
        store.localOperation(fixed,lease) {ToolExecutionResult(JSONObject().put("kind","visual_render").put("workspace_id",w.id).put("candidate_id","fixed").put("passed",true).toString())}
        assertEquals(0,store.outstanding(t.id).length())
        val unknown=call(t,"workspace_visual_test","{\"action\":\"interact\"}");db.insertToolCall(unknown);db.updateToolCall(unknown.id,ToolCallStatus.UNKNOWN)
        assertEquals(ExecutionState.WAITING,store.finish(lease,SliceOutcome.RESPONDED,1)!!.state)
    }

    @Test fun declaredPersistenceCannotBeSatisfiedByPassingDomOnlyChecks() {
        val w=db.createComposableWorkspace("Saved app", "")
        val t=task();store.claim(t.id,"owner")!!
        store.bindWorkspace(store.currentTurn(t.id),w.id,"activation",requirePersistence=true)
        fun check(candidate:String,persistent:Boolean) {
            clock++
            val c=call(t,"workspace_package_ui_test");db.insertToolCall(c)
            db.updateToolCall(c.id,ToolCallStatus.COMPLETED,resultJson=JSONObject().put("candidate_id",candidate).put("passed",true).put("storage_verified",persistent).put("storage_scope","isolated_test_database").toString())
        }
        check("candidate",false);assertEquals(1,store.missingAcceptance(store.currentTurn(t.id),"candidate").size)
        check("other",true);assertEquals(1,store.missingAcceptance(store.currentTurn(t.id),"candidate").size)
        check("candidate",true);assertTrue(store.missingAcceptance(store.currentTurn(t.id),"candidate").isEmpty())
        store.bindWorkspace(store.currentTurn(t.id),w.id,"activation")
        assertTrue(store.get(t.id)!!.document.getJSONObject("completion_requirement").getBoolean("require_persistence"))
    }
    @Test fun emptyDraftReadAndStagingValidationRecoverOnTheSameTask() {
        val w=db.createComposableWorkspace("Draft", "")
        val t=task();val lease=store.claim(t.id,"owner")!!
        store.bindWorkspace(store.currentTurn(t.id),w.id)
        val read=call(t,"workspace_package_read_file",JSONObject().put("workspace_id",w.id).toString());db.insertToolCall(read);db.updateToolCall(read.id,ToolCallStatus.FAILED,error="EMPTY_PACKAGE_DRAFT: stage a package")
        val bad=call(t,"workspace_package_stage",JSONObject().put("workspace_id",w.id).toString());db.insertToolCall(bad);db.updateToolCall(bad.id,ToolCallStatus.COMPLETED,resultJson="""{"ok":false,"error":"STAGING_ARGUMENTS_INVALID"}""")
        assertEquals(ExecutionState.READY,store.finish(lease,SliceOutcome.RESPONDED,1)!!.state)
        store.nextTurn(t.id);val next=store.claim(t.id,"corrected")!!
        val good=call(t,"workspace_package_stage",JSONObject().put("workspace_id",w.id).toString());db.insertToolCall(good);db.updateToolCall(good.id,ToolCallStatus.COMPLETED,resultJson=JSONObject().put("workspace_id",w.id).put("candidate_id","candidate").put("structurally_valid",true).toString())
        assertEquals(ExecutionState.COMPLETED,store.finish(next,SliceOutcome.RESPONDED,1)!!.state)
        assertEquals(ToolCallStatus.FAILED,db.loadToolCall(read.id)!!.status)
    }

    @Test fun writerAndWorkspaceSwitchUseOneLockOrder() {
        val w = db.createComposableWorkspace("Concurrent", "")
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val writer = pool.submit<Boolean> {
                db.writeTransaction {
                    check(Thread.holdsLock(db))
                    entered.countDown()
                    check(release.await(3, java.util.concurrent.TimeUnit.SECONDS))
                    db.appendEvent(session.id, "test.concurrent_writer")
                }
                true
            }
            assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS))
            val navigation = pool.submit<Long> { db.activateComposableWorkspace(session.id, w.id) }
            release.countDown()
            assertTrue(writer.get(3, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(navigation.get(3, java.util.concurrent.TimeUnit.SECONDS) > 0)
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test fun workStateSurvivesRestartAndReadsDoNotAdvanceTheFrontier() {
        val w = db.createComposableWorkspace("Repair", "")
        val t = task(); val lease = store.claim(t.id, "owner")!!
        store.bindWorkspace(store.currentTurn(t.id), w.id)
        val staged = call(t, "workspace_package_stage", JSONObject().put("workspace_id",w.id).put("files",JSONObject().put("ui/app.js","source")).toString())
        db.insertToolCall(staged);db.updateToolCall(staged.id,ToolCallStatus.COMPLETED,resultJson="""{"candidate_id":"exact-candidate","content_hash":"hash","workspace_id":"${w.id}"}""")
        val test = call(t,"workspace_package_ui_test",JSONObject().put("workspace_id",w.id).put("candidate_id","exact-candidate").toString())
        db.insertToolCall(test);db.updateToolCall(test.id,ToolCallStatus.COMPLETED,resultJson="""{"candidate_id":"exact-candidate","passed":false,"steps":[{"passed":false,"selector":"#label","actual_text":"actual label"}],"missing_operations":["events"]}""")
        store.finish(lease,SliceOutcome.YIELDED,1)
        val before=store.get(t.id)!!.document.getJSONObject("work_state").getString("frontier")
        val next=store.nextTurn(t.id);val nextLease=store.claim(t.id,"interrupted")!!
        repeat(104) {
            clock++
            val read=call(t,"workspace_package_read_file",JSONObject().put("workspace_id",w.id).put("candidate_id","exact-candidate").put("path","ui/app.js").toString())
            db.insertToolCall(read);db.updateToolCall(read.id,ToolCallStatus.COMPLETED,resultJson="""{"source":"same source"}""")
        }
        store.finish(nextLease,SliceOutcome.YIELDED,1)
        val after=store.get(t.id)!!.document.getJSONObject("work_state")
        assertEquals(before,after.getString("frontier"));assertEquals(1,after.getInt("no_progress_slices"))
        assertEquals(104,after.getInt("repeated_identical_reads"))
        store.nextTurn(t.id);store.claim(t.id,"crashed")
        store.recover(session.id)
        val context=ExecutionTaskStore(db).context(t.id)
        assertTrue(context.contains("exact-candidate"));assertTrue(context.contains("actual label"));assertTrue(context.contains("events"))
        assertFalse(context.contains("\"passed\":true"))
        assertEquals(ExecutionState.READY,store.get(t.id)!!.state)
    }

    @Test fun retainedCandidateChecksSurviveWithoutStagingInThisTask() {
        val w=db.createComposableWorkspace("Retained", "")
        val t=task();val lease=store.claim(t.id,"owner")!!;store.bindWorkspace(store.currentTurn(t.id),w.id)
        fun receipt(name:String,args:JSONObject,result:JSONObject) {
            clock++
            val c=call(t,name,args.put("workspace_id",w.id).toString());db.insertToolCall(c)
            db.updateToolCall(c.id,ToolCallStatus.COMPLETED,resultJson=result.toString())
        }
        receipt("workspace_package_inspect",JSONObject(),JSONObject("""{"candidates":[{"candidate_id":"retained","base_revision_id":"base","parent_candidate_id":"parent","content_hash":"hash"}]}"""))
        receipt("workspace_package_ui_test",JSONObject().put("candidate_id","retained"),JSONObject("""{"candidate_id":"retained","passed":false,"missing_operations":["write"]}"""))
        store.finish(lease,SliceOutcome.YIELDED,1)
        val state=store.get(t.id)!!.document.getJSONObject("work_state")
        assertEquals("retained",state.getJSONObject("latest_candidate").getString("candidate_id"))
        assertEquals("parent",state.getJSONObject("latest_candidate").getString("parent_candidate_id"))
        assertEquals(1,state.getJSONArray("latest_checks").length())
        assertTrue(ExecutionTaskStore(db).context(t.id).contains("write"))
        val before=state.getString("frontier")
        store.nextTurn(t.id);val next=store.claim(t.id,"next")!!
        receipt("workspace_package_ui_test",JSONObject().put("candidate_id","retained"),JSONObject("""{"candidate_id":"retained","passed":true,"missing_operations":[]}"""))
        store.finish(next,SliceOutcome.YIELDED,1)
        assertNotEquals(before,store.get(t.id)!!.document.getJSONObject("work_state").getString("frontier"))
        assertEquals(0,store.get(t.id)!!.document.optInt("no_progress_slices"))
    }

    @Test fun acceptedClarificationResolvesRejectedControlWithoutRewritingAudit() {
        val t=task();val lease=store.claim(t.id,"owner")!!
        val rejected=call(t,ToolHarness.CLARIFICATION_TOOL,"""{"action":"request","question":"Which?"}""")
        db.insertToolCall(rejected);db.updateToolCall(rejected.id,ToolCallStatus.REJECTED,error="Clarification request requires a question and reason")
        clock++
        val accepted=call(t,ToolHarness.CLARIFICATION_TOOL,"""{"action":"request","question":"Which?","reason":"Ambiguous target"}""")
        db.insertToolCall(accepted);db.updateToolCall(accepted.id,ToolCallStatus.COMPLETED,resultJson="""{"accepted":true,"action":"request"}""")
        val finished=store.finish(lease,SliceOutcome.RESPONDED,1)!!
        assertEquals(ExecutionState.COMPLETED,finished.state)
        assertEquals(0,finished.document.getJSONObject("checkpoint").getJSONArray("unresolved_operation_ids").length())
        assertTrue(finished.document.getJSONObject("checkpoint").getJSONArray("resolved_operations").toString().contains(rejected.id))
        assertEquals(ToolCallStatus.REJECTED,db.loadToolCall(rejected.id)!!.status)
    }

    @Test fun lateInferenceUsageIsReconciledOnceWithoutBorrowingANewLease() {
        val t=task();val lease=store.claim(t.id,"owner")!!;val turn=store.currentTurn(t.id)
        assertTrue(store.reserveInference(lease,100,85,"late-request"))
        store.finish(lease,SliceOutcome.WAITING_PROVIDER,1)
        val before=store.get(t.id)!!
        store.reconcileLateInference(turn,"late-request",400,3308)
        store.reconcileLateInference(turn,"late-request",400,3308)
        store.reconcileLateInference(turn,"unknown-request",999,999)
        val after=store.get(t.id)!!
        assertEquals(before.state,after.state);assertEquals(before.generation,after.generation);assertEquals(before.owner,after.owner)
        assertEquals(400L,after.usage.getLong("input_tokens"));assertEquals(3308L,after.usage.getLong("output_tokens"))
    }
    @Test fun thousandSlicesContinueWithOneTaskAndNoAdditionalUserMessage() = runTest {
        val t=task();var slices=0
        val result=executor().run(t.id) { lease, turn ->
            assertTrue(store.reserveInference(lease,100,100));store.reconcileInference(lease,75,25)
            assertTrue(store.reserveTool(lease))
            val c=call(store.get(t.id)!!);db.insertToolCall(c)
            store.localOperation(c,lease) { ToolExecutionResult("{\"ok\":true,\"ordinal\":${++slices}}") }
            db.updateTurn(turn, if(slices==1000) TurnStatus.COMPLETED else TurnStatus.YIELDED, stepCount=8)
            if(slices==1000) SliceOutcome.RESPONDED else SliceOutcome.YIELDED
        }!!
        assertEquals(ExecutionState.COMPLETED,result.state)
        assertEquals(1000,store.turns(t.id).size)
        assertEquals(1000L,result.usage.getLong("model_steps"))
        assertEquals(75000L,result.usage.getLong("input_tokens"))
        assertEquals(25000L,result.usage.getLong("output_tokens"))
        assertTrue(db.loadMessages(session.id).isEmpty())
    }
    @Test fun resolvedAuthorizationRetiresOldTurnWithoutExecutingApprovedCall() {
        val t=task();val old=store.currentTurn(t.id)
        val c=call(t);db.insertToolCall(c);db.updateToolCall(c.id,ToolCallStatus.APPROVED)
        db.updateTurn(old,TurnStatus.WAITING_FOR_CONFIRMATION)
        store.nextTurn(t.id)
        assertEquals(TurnStatus.INTERRUPTED,db.loadTurn(old)!!.status)
        assertEquals(ToolCallStatus.APPROVED,db.loadToolCall(c.id)!!.status)
        assertNotEquals(old,db.loadActiveTurn(session.id)!!.id)
    }
    @Test fun matchingDeliverySupersedesFailedAcceptanceButNotAnotherCandidateOrUnknownOutcome() {
        for(mode in listOf("matching","other_candidate","unknown")) {
            val t=task(atomicTools=listOf("workspace_package_activate"));val lease=store.claim(t.id,"owner")!!
            val w=db.createComposableWorkspace("Acceptance $mode", "")
            val failed=call(t,"workspace_accept_unverified",JSONObject().put("workspace_id",w.id).put("candidate_id",if(mode=="other_candidate")"different" else "candidate").toString())
            db.insertToolCall(failed);db.updateToolCall(failed.id,if(mode=="unknown")ToolCallStatus.UNKNOWN else ToolCallStatus.FAILED,error="Quote invalid")
            delivered(t,lease,w.id,w.liveRevisionId)
            val result=store.finish(lease,SliceOutcome.RESPONDED,1)!!
            assertEquals(mode,if(mode=="matching")ExecutionState.COMPLETED else ExecutionState.WAITING,result.state)
            assertEquals(mode,if(mode=="matching")1 else 0,result.document.getJSONObject("checkpoint").getJSONArray("resolved_operations").length())
        }
    }
    @Test fun committedActivationSupersedesOnlyCandidateFailuresForItsWorkspaceAndBase() {
        val t=task(atomicTools=listOf("workspace_package_activate"));val lease=store.claim(t.id,"owner")!!
        val w=db.createComposableWorkspace("App", "")
        val failed=call(t,"workspace_package_stage",JSONObject().put("workspace_id",w.id).put("base_revision_id",w.liveRevisionId).toString())
        db.insertToolCall(failed);db.updateToolCall(failed.id,ToolCallStatus.FAILED,error="Invalid discarded candidate")
        val activation=call(t,"workspace_package_activate",JSONObject().put("workspace_id",w.id).toString());db.insertToolCall(activation)
        store.localOperation(activation,lease) { ToolExecutionResult(JSONObject().put("activated",true).put("workspace_id",w.id).put("previous_revision_id",w.liveRevisionId).put("revision_id",w.liveRevisionId).toString()) }
        val result=store.finish(lease,SliceOutcome.RESPONDED,1)!!
        assertEquals(ExecutionState.COMPLETED,result.state)
        assertEquals(1,result.document.getJSONObject("checkpoint").getJSONArray("resolved_operations").length())
        assertEquals(ToolCallStatus.FAILED,db.loadToolCall(failed.id)!!.status)
    }
    @Test fun otherWorkspaceActivationCannotEraseCandidateFailure() {
        val t=task(atomicTools=listOf("workspace_package_activate"));val lease=store.claim(t.id,"owner")!!
        val w=db.createComposableWorkspace("Other", "")
        val failed=call(t,"workspace_package_stage","{\"workspace_id\":\"first\",\"base_revision_id\":\"base\"}")
        db.insertToolCall(failed);db.updateToolCall(failed.id,ToolCallStatus.FAILED,error="Still broken")
        val activation=call(t,"workspace_package_activate");db.insertToolCall(activation)
        store.localOperation(activation,lease) { ToolExecutionResult(JSONObject().put("activated",true).put("workspace_id",w.id).put("previous_revision_id","base").put("revision_id",w.liveRevisionId).toString()) }
        val result=store.finish(lease,SliceOutcome.RESPONDED,1)!!
        assertEquals(ExecutionState.WAITING,result.state)
        assertEquals("unresolved_tool_failure",result.waitReason)
    }
    @Test fun completedToolWithFailingFixtureContinuesRecovery() {
        val t=task();val lease=store.claim(t.id,"owner")!!;val c=call(t,"workspace_package_test")
        db.insertToolCall(c);db.updateToolCall(c.id,ToolCallStatus.COMPLETED,resultJson="{\"passed\":false}")
        assertEquals(ExecutionState.READY,store.finish(lease,SliceOutcome.RESPONDED,1)!!.state)
    }
    @Test fun onlyOneOwnerAndStaleLeaseCannotMutateAfterPause() {
        val t=task();val lease=store.claim(t.id,"first")!!
        assertNull(store.claim(t.id,"second"))
        assertNull(store.claim(task().id,"other-task"))
        val c=call(t);db.insertToolCall(c)
        store.control(t.id,ExecutionState.PAUSED,"user pause")
        var effects=0
        assertTrue(runCatching { store.localOperation(c,lease) {effects++;ToolExecutionResult("{}") } }.exceptionOrNull() is CancellationException)
        assertEquals(0,effects)
    }
    @Test fun liveOwnerCannotBeHiddenByMoreThanOneHundredNewerTasks() {
        val older=task();store.claim(older.id,"older owner")!!
        repeat(100) { task() }
        val newer=task()
        assertFalse(store.list(session.id).any {it.id==older.id})
        assertNull(store.claim(newer.id,"second owner"))
        db.recoverInterruptedRuntime(session.id);store.recover(session.id)
        assertEquals(ExecutionState.READY,store.get(older.id)!!.state)
    }
    @Test fun committedReceiptIsReplayableWithoutRepeatingEffectAndRejectsChangedArguments() {
        val t=task();val lease=store.claim(t.id,"owner")!!;val c=call(t);db.insertToolCall(c)
        var effects=0
        val first=store.localOperation(c,lease) {effects++;ToolExecutionResult("{\"ok\":true}")}
        val second=store.localOperation(c,lease) {effects++;error("Must not execute")}
        assertEquals(first.resultJson,second.resultJson);assertEquals(1,effects)
        assertEquals(ToolCallStatus.COMPLETED,db.loadToolCall(c.id)!!.status)
        assertTrue(runCatching { store.receipt(c.copy(argumentsJson="{\"x\":1}")) }.isFailure)
    }
    @Test fun cancellationReconcilesAnEffectThatCommittedBeforeTheInterruptionMark() {
        val t=task();val lease=store.claim(t.id,"owner")!!;val c=call(t);db.insertToolCall(c)
        store.localOperation(c,lease) {ToolExecutionResult("{\"ok\":true}")}
        // Simulate a cancellation observer that read RUNNING just before the atomic commit.
        db.updateToolCall(c.id,ToolCallStatus.UNKNOWN,error="Interruption observed")
        store.control(t.id,ExecutionState.CANCELLED,"user cancelled")
        assertEquals(ToolCallStatus.COMPLETED,db.loadToolCall(c.id)!!.status)
        assertNull(db.loadToolCall(c.id)!!.error)
        assertNotNull(store.receipt(c));assertEquals(ExecutionState.CANCELLED,store.get(t.id)!!.state)
    }
    @Test fun failedAtomicEffectRollsBackMemoryAndReceiptTogether() {
        val t=task();val lease=store.claim(t.id,"owner")!!;val c=call(t);db.insertToolCall(c)
        assertTrue(runCatching { store.localOperation(c,lease) {
            db.writableDatabase.execSQL("UPDATE sessions SET name='Uncommitted' WHERE session_id=?",arrayOf(session.id))
            error("Injected failure before receipt")
        } }.isFailure)
        assertEquals("Execution",db.loadSession(session.id)!!.name)
        assertNull(store.receipt(c))
    }
    @Test fun restartResumesRolledBackLocalOperationButBlocksUnknownExternalEffect() {
        val t=task();store.claim(t.id,"crashed")!!;val c=call(t);db.insertToolCall(c);db.updateToolCall(c.id,ToolCallStatus.RUNNING)
        db.recoverInterruptedRuntime(session.id);store.recover(session.id)
        assertEquals(ExecutionState.READY,store.get(t.id)!!.state)
        assertEquals(ToolCallStatus.FAILED,db.loadToolCall(c.id)!!.status)
        val external=task();store.claim(external.id,"crashed")!!;val e=call(external,"external");db.insertToolCall(e);db.updateToolCall(e.id,ToolCallStatus.RUNNING)
        db.recoverInterruptedRuntime(session.id);store.recover(session.id)
        assertEquals(ExecutionState.WAITING,store.get(external.id)!!.state)
        assertEquals("unknown_operation_outcome",store.get(external.id)!!.waitReason)
        store.control(external.id,ExecutionState.READY,"retry")
        assertEquals(ExecutionState.WAITING,store.get(external.id)!!.state)
    }
    @Test fun committedEffectSurvivesDatabaseReopenAndIsNotReplayed() = runTest {
        val t=task();val lease=store.claim(t.id,"crashed")!!;val c=call(t);db.insertToolCall(c)
        store.localOperation(c,lease) { ToolExecutionResult("{\"ok\":true}") }
        db.close();db=AtlasDatabase(RuntimeEnvironment.getApplication());store=ExecutionTaskStore(db){clock}
        db.recoverInterruptedRuntime(session.id);store.recover(session.id)
        assertEquals(ToolCallStatus.COMPLETED,db.loadToolCall(c.id)!!.status)
        assertTrue(store.context(t.id).contains(c.id))
        assertTrue(store.context(t.id).contains("\"status\":\"COMPLETED\""))
        assertTrue(db.loadMessages(session.id).isEmpty())
        executor().run(t.id) { resumed,_ -> assertNotEquals(lease.generation,resumed.generation);assertNotNull(store.receipt(c));SliceOutcome.RESPONDED }
        assertEquals(ExecutionState.COMPLETED,store.get(t.id)!!.state)
    }
    @Test fun cumulativeAllowancePausesWithoutResetOnResume() = runTest {
        val t=task(ExecutionBudget(modelSteps=2));var slices=0
        executor().run(t.id) { lease,_ -> assertTrue(store.reserveInference(lease,1,1));store.reconcileInference(lease,1,1);slices++;SliceOutcome.YIELDED }
        assertEquals(2,slices);assertEquals("task_allowance",store.get(t.id)!!.waitReason)
        store.control(t.id,ExecutionState.READY,"user resume")
        executor().run(t.id) {_,_->fail("Exhausted task must not dispatch");SliceOutcome.RESPONDED}
        assertEquals(2L,store.get(t.id)!!.usage.getLong("model_steps"))
    }
    @Test fun responseThatFinishesOnTheFinalAllowedStepCompletesWithoutAnotherDispatch() = runTest {
        val t=task(ExecutionBudget(modelSteps=1));var dispatches=0
        val result=executor().run(t.id) {lease,_ -> dispatches++;assertTrue(store.reserveInference(lease,1,1));store.reconcileInference(lease,1,1);SliceOutcome.RESPONDED}!!
        assertEquals(1,dispatches);assertEquals(ExecutionState.COMPLETED,result.state)
        assertEquals("",result.waitReason)
    }
    @Test fun activeTimeAllowanceAlsoStopsAutomaticContinuation() = runTest {
        val t=task(ExecutionBudget(activeMs=50));var slices=0
        executor().run(t.id) {_,_->clock+=50;slices++;SliceOutcome.YIELDED}
        assertEquals(1,slices);assertEquals("task_allowance",store.get(t.id)!!.waitReason)
    }
    @Test fun pauseChargesActiveSliceTimeRatherThanResettingAllowance() {
        val t=task(ExecutionBudget(activeMs=50));store.claim(t.id,"owner")!!;clock+=50
        store.control(t.id,ExecutionState.PAUSED,"user pause")
        assertEquals(50L,store.get(t.id)!!.usage.getLong("active_ms"));assertFalse(store.available(t.id))
    }
    @Test fun driverCancellationPausesInsteadOfLeavingLiveOwner() = runTest {
        val t=task()
        assertTrue(runCatching { executor().run(t.id) {_,_->throw CancellationException("cancel")} }.exceptionOrNull() is CancellationException)
        assertEquals(ExecutionState.PAUSED,store.get(t.id)!!.state)
        assertNull(store.get(t.id)!!.owner)
    }
    @Test fun clarificationReplyReattachesOnlyTheWaitingTask() {
        val t=task();val lease=store.claim(t.id,"owner")!!
        store.finish(lease,SliceOutcome.WAITING_USER,10)
        val reply=db.createTurn(session.id,"text")
        store.attachReply(t.id,reply.id)
        assertEquals(t.id,store.forTurn(reply.id)!!.id)
        assertEquals(2,store.turns(t.id).size)
        assertTrue(runCatching {store.attachReply(t.id,db.createTurn(session.id,"text").id)}.isFailure)
    }
    @Test fun activationRequirementContinuesWithoutActivationWhileDraftCanFinish() {
        for(delivery in listOf("activation","draft","inspect")) {
            val w=db.createComposableWorkspace("Delivery $delivery", "")
            val t=task();val lease=store.claim(t.id,"owner")!!
            store.bindWorkspace(store.currentTurn(t.id),w.id,delivery)
            if(delivery=="activation") assertTrue(runCatching {store.bindWorkspace(store.currentTurn(t.id),w.id,"draft")}.isFailure)
            val result=store.finish(lease,SliceOutcome.RESPONDED,1)!!
            assertEquals(if(delivery=="activation") ExecutionState.READY else ExecutionState.COMPLETED,result.state)
            if(delivery=="activation") store.control(t.id,ExecutionState.CANCELLED,"test complete")
        }
    }

    @Test fun repeatedVerificationWithFreshReceiptIdsStillStopsAtTheExistingFailureGuard() = runTest {
        val t=task();var slices=0
        executor().run(t.id) {_,_->
            clock++;slices++
            val c=call(t,"workspace_package_ui_test");db.insertToolCall(c)
            db.updateToolCall(c.id,ToolCallStatus.COMPLETED,resultJson=JSONObject().put("check_id",UUID.randomUUID().toString()).put("passed",false)
                .put("steps",org.json.JSONArray().put(JSONObject().put("index",0).put("passed",false).put("selector","#connect").put("outcome","disabled"))).toString())
            SliceOutcome.RESPONDED
        }
        assertEquals(3,slices);assertEquals("repeated_failure",store.get(t.id)!!.waitReason)
    }
    @Test fun verificationRecoveryDoesNotOverrideExplicitUserWaitOrUnknownOutcome() {
        val t=task();val lease=store.claim(t.id,"owner")!!
        val c=call(t,"workspace_package_ui_test");db.insertToolCall(c);db.updateToolCall(c.id,ToolCallStatus.COMPLETED,resultJson="""{"passed":false}""")
        val wait=store.finish(lease,SliceOutcome.WAITING_USER,1)!!
        assertEquals(ExecutionState.WAITING,wait.state);assertEquals("clarification",wait.waitReason)
    }

    @Test fun repeatedDeterministicFailureWaitsRatherThanLoopingForever() = runTest {
        val t=task();var slices=0
        executor().run(t.id) {_,_->
            val c=call(t);db.insertToolCall(c);db.updateToolCall(c.id,ToolCallStatus.FAILED,error="same invalid arguments");slices++;SliceOutcome.YIELDED
        }
        assertEquals(3,slices);assertEquals("repeated_failure",store.get(t.id)!!.waitReason)
    }
    @Test fun cancellingAuthorizationWaitRemovesPendingExecutableProposal() {
        val t=task();val c=call(t).copy(status=ToolCallStatus.WAITING_FOR_CONFIRMATION,requiresConfirmation=true)
        db.insertToolCall(c);val lease=store.claim(t.id,"owner")!!
        store.finish(lease,SliceOutcome.WAITING_PERMISSION,1)
        store.control(t.id,ExecutionState.CANCELLED,"user cancelled")
        assertEquals(ToolCallStatus.REJECTED,db.loadToolCall(c.id)!!.status)
        assertNull(db.loadActiveTurn(session.id));assertTrue(db.loadPendingToolCalls(session.id).isEmpty())
    }
    @Test fun repeatedProviderCutoffsWaitWithoutClaimingCompletion() = runTest {
        val t=task();var slices=0
        executor().run(t.id) { lease,_ -> slices++;assertFalse(store.observeResponse(lease,"length"));SliceOutcome.YIELDED }
        assertEquals(3,slices);assertEquals(ExecutionState.WAITING,store.get(t.id)!!.state)
        assertEquals("context_cutoff",store.get(t.id)!!.waitReason)
    }
    @Test fun exportedSnapshotsNeverImportExecutableAuthorityAndDeleteRemovesJournal() {
        val t=task();val lease=store.claim(t.id,"owner")!!;val c=call(t);db.insertToolCall(c);store.localOperation(c,lease){ToolExecutionResult("{}")}
        val archive=db.exportSession(session.id)
        assertEquals(1,archive.getJSONArray("executionTaskSnapshots").length())
        val imported=db.importArchive(ArchiveCodec.preview(archive))
        assertTrue(store.list(imported.id).isEmpty())
        db.deleteSession(session.id)
        assertNull(store.get(t.id));assertNull(store.receipt(c));assertNull(store.forTurn(c.turnId))
    }
    @Test fun committedWorkspaceReceiptAllowsRedundantResumeAuditFailureWithoutHidingMutationFailures() {
        val t = task(atomicTools = listOf("workspace_replace_definition"))
        val lease = store.claim(t.id, "owner")!!
        val edit = call(t, "workspace_replace_definition"); db.insertToolCall(edit)
        store.localOperation(edit, lease) { ToolExecutionResult("{\"ok\":true}") }
        val audit = call(t, CapabilityDiscovery.TOOL, """{"stage":"resume_local","reason":"Already working locally"}""")
        db.insertToolCall(audit); db.updateToolCall(audit.id, ToolCallStatus.FAILED,
            resultJson = """{"ok":false,"error_code":"missing_blocker"}""", error = "No blocker")
        val result = store.finish(lease, SliceOutcome.RESPONDED, 1)!!
        assertEquals(ExecutionState.COMPLETED, result.state)
        assertEquals(audit.id, result.document.getJSONObject("checkpoint").getJSONArray("diagnostic_operation_ids").getString(0))
        assertEquals(ToolCallStatus.FAILED, db.loadToolCall(audit.id)!!.status)

        val broken = task(); val brokenLease = store.claim(broken.id, "next")!!
        val mutation = call(broken, "workspace_replace_definition"); db.insertToolCall(mutation)
        db.updateToolCall(mutation.id, ToolCallStatus.FAILED, error = "Revision mismatch")
        assertEquals("unresolved_tool_failure", store.finish(brokenLease, SliceOutcome.RESPONDED, 1)!!.waitReason)
    }
    @Test fun redundantAuditFailureWithoutCommittedEffectStillWaits() {
        val t = task(); val lease = store.claim(t.id, "owner")!!
        val audit = call(t, CapabilityDiscovery.TOOL, """{"stage":"resume_local","reason":"No proof"}""")
        db.insertToolCall(audit); db.updateToolCall(audit.id, ToolCallStatus.FAILED, resultJson = """{"ok":false,"error_code":"missing_blocker"}""")
        assertEquals(ExecutionState.WAITING, store.finish(lease, SliceOutcome.RESPONDED, 1)!!.state)
    }
    @Test fun endingSessionPreservesYieldedAndCompletedLateHistory() {
        val yielded = db.createTurn(session.id, "text"); db.updateTurn(yielded.id, TurnStatus.YIELDED)
        val late = db.createTurn(session.id, "text"); db.updateTurn(late.id, TurnStatus.COMPLETED_LATE)
        val active = db.createTurn(session.id, "text")
        db.cancelOpenSessionWork(session.id, "session ended")
        assertEquals(TurnStatus.YIELDED, db.loadTurn(yielded.id)!!.status)
        assertEquals(TurnStatus.COMPLETED_LATE, db.loadTurn(late.id)!!.status)
        assertEquals(TurnStatus.CANCELLED, db.loadTurn(active.id)!!.status)
    }

}
