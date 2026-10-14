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
    @Test fun committedReceiptIsReplayableWithoutRepeatingEffectAndRejectsChangedArguments() {
        val t=task();val lease=store.claim(t.id,"owner")!!;val c=call(t);db.insertToolCall(c)
        var effects=0
        val first=store.localOperation(c,lease) {effects++;ToolExecutionResult("{\"ok\":true}")}
        val second=store.localOperation(c,lease) {effects++;error("Must not execute")}
        assertEquals(first.resultJson,second.resultJson);assertEquals(1,effects)
        assertEquals(ToolCallStatus.COMPLETED,db.loadToolCall(c.id)!!.status)
        assertTrue(runCatching { store.receipt(c.copy(argumentsJson="{\"x\":1}")) }.isFailure)
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
    @Test fun activeTimeAllowanceAlsoStopsAutomaticContinuation() = runTest {
        val t=task(ExecutionBudget(activeMs=50));var slices=0
        executor().run(t.id) {_,_->clock+=50;slices++;SliceOutcome.YIELDED}
        assertEquals(1,slices);assertEquals("task_allowance",store.get(t.id)!!.waitReason)
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
    @Test fun repeatedDeterministicFailureWaitsRatherThanLoopingForever() = runTest {
        val t=task();var slices=0
        executor().run(t.id) {_,_->
            val c=call(t);db.insertToolCall(c);db.updateToolCall(c.id,ToolCallStatus.FAILED,error="same invalid arguments");slices++;SliceOutcome.YIELDED
        }
        assertEquals(3,slices);assertEquals("repeated_failure",store.get(t.id)!!.waitReason)
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
}
