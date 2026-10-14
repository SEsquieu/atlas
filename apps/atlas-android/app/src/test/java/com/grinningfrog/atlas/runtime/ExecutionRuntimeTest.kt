package com.grinningfrog.atlas.runtime

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.grinningfrog.atlas.data.AtlasDatabase
import com.grinningfrog.atlas.device.*
import com.grinningfrog.atlas.media.MediaRepository
import com.grinningfrog.atlas.model.*
import com.grinningfrog.atlas.provider.*
import com.grinningfrog.atlas.workspace.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Exercises the production session runner and harness; only provider output and hardware startup are substituted. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ExecutionRuntimeTest {
    private lateinit var db: AtlasDatabase
    private lateinit var session: AtlasSession
    @Before fun setup() {
        RuntimeEnvironment.getApplication().deleteDatabase("atlas.db")
        db=AtlasDatabase(RuntimeEnvironment.getApplication())
        session=db.createSession("Execution","Edit the workspace")
        db.updateSessionStatus(session.id,SessionStatus.ACTIVE)
        db.writableDatabase.execSQL("UPDATE sessions SET permissions_json=? WHERE session_id=?",arrayOf("{\"speakResponses\":false}",session.id))
        session=db.loadSession(session.id)!!
    }
    @After fun cleanup() { db.close() }
    private fun runtime(scope: kotlinx.coroutines.CoroutineScope, tools: List<AtlasToolAdapter>, respond: suspend (InferenceRequest)->InferenceResponse): AtlasMobileRuntime {
        val app=RuntimeEnvironment.getApplication()
        val motion=MotionMonitor(app);val media=MediaRepository(app)
        val owner=object:LifecycleOwner {override val lifecycle:Lifecycle=LifecycleRegistry(this)}
        val endpoint=ProviderEndpoint("script","Script","https://example.org","script",supportsTools=true)
        val backend=object:InferenceBackend { override suspend fun infer(endpoint:ProviderEndpoint,apiKey:String?,request:InferenceRequest)=respond(request) }
        val router=CapabilityRouter(backend,{listOf(endpoint)},{RouteTable(fast=listOf("script"),reasoning=listOf("script"),fallback=listOf("script"))},{null})
        val runtime=AtlasMobileRuntime(db,media,CameraController(app,owner,motion,media),SpeechController(app),motion,DeviceHealthMonitor(app,motion),router,scope,additionalTools=tools)
        // An active session snapshot skips real camera/speech startup; no executor path is replaced.
        val field=AtlasMobileRuntime::class.java.getDeclaredField("mutableState").apply {isAccessible=true}
        @Suppress("UNCHECKED_CAST")
        (field.get(runtime) as MutableStateFlow<RuntimeSnapshot>).value=RuntimeSnapshot(session=session,phase=RuntimePhase.READY)
        return runtime
    }
    private fun response(r:InferenceRequest,calls:List<ToolCallProposal> = emptyList())=InferenceResponse(r.requestId,"script",if(calls.isEmpty()) "Done" else "",1,toolCalls=calls,promptTokens=100,completionTokens=20)
    private fun diagnostic(execute:()->Unit) = object:AtlasToolAdapter {
        override val supportsLocalDiagnostics=true
        override val definition=ToolDefinition("diagnostic","Read-only diagnostic","{}",ToolRisk.READ_ONLY,maxCallsPerTurn=32)
        override suspend fun execute(call:AtlasToolCall):ToolExecutionResult {execute();return ToolExecutionResult("{\"ok\":true}")}
    }
    @Test fun cancellingActivityWaitDoesNotCancelServiceOwnedTask() = runTest {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val runtime=runtime(this,emptyList()) { request -> entered.complete(Unit);release.await();response(request) }
        val activity=launch { runtime.ask("Reply when ready") }
        entered.await()
        activity.cancel();activity.join()
        val task=ExecutionTaskStore(db).list(session.id).single()
        assertEquals(ExecutionState.RUNNING,task.state)
        release.complete(Unit)
        repeat(10) { if(ExecutionTaskStore(db).get(task.id)?.state!=ExecutionState.COMPLETED) delay(1) }
        assertEquals(ExecutionState.COMPLETED,ExecutionTaskStore(db).get(task.id)!!.state)
    }

    @Test fun productionRunnerAutomaticallyContinuesAfterEightSteps() = runTest {
        var requests=0;var executions=0;val turns=mutableListOf<String>()
        val runtime=runtime(this,listOf(diagnostic {executions++})) { request ->
            requests++;turns+=request.turnId!!
            response(request,if(requests<=16) listOf(ToolCallProposal("call-$requests","diagnostic","{}")) else emptyList())
        }
        runtime.ask("Run the diagnostics and finish the task")
        assertEquals(17,requests);assertEquals(16,executions);assertEquals(3,turns.distinct().size)
        val task=ExecutionTaskStore(db).list(session.id).single()
        assertEquals(ExecutionState.COMPLETED,task.state)
        assertEquals(1,db.loadMessages(session.id).count {it.role==MessageRole.USER})
        assertEquals(2,db.loadRecentTurns(session.id).count {it.status==TurnStatus.YIELDED})
    }
    @Test fun oversizedSliceBatchIsJournaledAndDrainedWithoutDroppingOrRepeatingCalls() = runTest {
        var requests=0;var executions=0
        val runtime=runtime(this,listOf(diagnostic {executions++})) { request ->
            response(request,if(++requests==1) (1..20).map {ToolCallProposal("batch-$it","diagnostic","{}")} else emptyList())
        }
        runtime.ask("Run the diagnostics")
        assertEquals(2,requests);assertEquals(20,executions)
        val task=ExecutionTaskStore(db).list(session.id).single()
        assertEquals(ExecutionState.COMPLETED,task.state)
        assertEquals(20L,task.usage.getLong("tool_calls"))
        assertTrue(ExecutionTaskStore(db).pending(task.id).isEmpty())
        assertEquals(listOf(12,8),ExecutionTaskStore(db).turns(task.id).map { db.turnDiagnostics(db.loadTurn(it)!!).toolCalls })
    }
    @Test fun installedToolAllowanceCarriesARealRecordBatchAcrossSlicesWithoutRejection() = runTest {
        val workspace=db.createComposableWorkspace("Records","",RecordEditingFixture.definition().toString(),session.workspaceId)
        db.activateComposableWorkspace(session.id,workspace.id);session=db.loadSession(session.id)!!
        var requests=0
        val runtime=runtime(this,WorkspaceTools.create(db)) { request ->
            response(request,if(++requests==1) (1..18).map { index -> ToolCallProposal("record-$index","workspace_add_record",JSONObject().put("workspace_id",workspace.id).put("collection","entries").put("data",RecordEditingFixture.data("Item $index")).toString()) } else emptyList())
        }
        runtime.ask("Add the records to the workspace")
        val store=ExecutionTaskStore(db);val task=store.list(session.id).single()
        assertEquals(ExecutionState.COMPLETED,task.state)
        assertEquals(3,store.turns(task.id).size);assertEquals(2,requests)
        assertEquals(18,db.listWorkspaceRecords(workspace.id,"entries",50).size)
        assertEquals(18L,task.usage.getLong("tool_calls"))
        assertEquals(listOf(6,6,6),store.turns(task.id).map { db.turnDiagnostics(db.loadTurn(it)!!).toolCalls })
        assertTrue(store.calls(task.id).all { it.status==ToolCallStatus.COMPLETED })
    }

    @Test fun discoveryValidationAndCommitCrossSliceBoundaryThroughProductionHarness() = runTest {
        val definition=RecordEditingFixture.definition()
        val workspace=db.createComposableWorkspace("Test","",definition.toString(),session.workspaceId)
        db.activateComposableWorkspace(session.id,workspace.id);session=db.loadSession(session.id)!!
        val updated=JSONObject(definition.toString()).put("title","Updated")
        var requests=0
        val runtime=runtime(this,WorkspaceTools.create(db)+diagnostic {}) { request ->
            val n=++requests
            val proposal=when(n) {
                1 -> ToolCallProposal("blocked",CapabilityDiscovery.TOOL,"""{"stage":"blocked","objective":"Update the workspace","missing_capability":"Additional presentation option"}""")
                in 2..7 -> ToolCallProposal("diagnostic-$n","diagnostic","{}")
                8 -> ToolCallProposal("validate","workspace_validate_definition",JSONObject().put("workspace_id",workspace.id).put("definition",updated).toString())
                9 -> ToolCallProposal("resume",CapabilityDiscovery.TOOL,"""{"stage":"resume_local","reason":"The installed workspace tools can perform this edit"}""")
                10 -> ToolCallProposal("commit","workspace_replace_definition",JSONObject().put("workspace_id",workspace.id).put("navigation_sequence",session.workspaceNavigationSequence).put("base_revision_id",workspace.liveRevisionId).put("definition",updated).put("purpose","Update title").toString())
                else -> null
            }
            response(request,listOfNotNull(proposal))
        }
        runtime.ask("Update the workspace title")
        assertEquals(11,requests)
        assertEquals(ToolCallStatus.COMPLETED,db.loadToolCall("validate")!!.status)
        assertTrue(JSONObject(db.loadToolCall("validate")!!.resultJson!!).getBoolean("valid"))
        assertEquals(ToolCallStatus.COMPLETED,db.loadToolCall("commit")!!.status)
        assertEquals("Updated",JSONObject(db.loadLiveWorkspaceRevision(workspace.id)!!.definitionJson).getString("title"))
        val task=ExecutionTaskStore(db).list(session.id).single()
        assertEquals(ExecutionState.COMPLETED,task.state)
        assertNotNull(ExecutionTaskStore(db).receipt(db.loadToolCall("commit")!!))
    }
}
