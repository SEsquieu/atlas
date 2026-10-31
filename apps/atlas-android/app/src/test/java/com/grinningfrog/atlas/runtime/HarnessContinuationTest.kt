package com.grinningfrog.atlas.runtime

import android.app.Application
import com.grinningfrog.atlas.data.AtlasDatabase
import com.grinningfrog.atlas.model.*
import kotlinx.coroutines.*
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
class HarnessContinuationTest {
    private lateinit var db: AtlasDatabase
    private lateinit var store: ExecutionTaskStore
    private lateinit var session: AtlasSession
    @Before fun setup() {
        db = AtlasDatabase(RuntimeEnvironment.getApplication(), null)
        store = ExecutionTaskStore(db)
        val s = db.createSession("Continuation", "Finish the same candidate")
        db.updateSessionStatus(s.id, SessionStatus.ACTIVE); session = db.loadSession(s.id)!!
    }
    @After fun close() = db.close()
    private fun task() = store.create(session, "Finish", db.createTurn(session.id, "user").id, ExecutionBudget())
    private fun call(turn: String, name: String = "probe") = AtlasToolCall(UUID.randomUUID().toString(), session.id, turn, name, "{}", ToolCallStatus.PROPOSED, ToolRisk.READ_ONLY, false, UUID.randomUUID().toString(), createdAtMs = 1, updatedAtMs = 1).also(db::insertToolCall)

    @Test fun pendingCallKeepsProposalIdentityButUsesCurrentLeaseAndSlice() = runTest {
        val task = task(); val first = store.claim(task.id, "first")!!
        val originalTurn = store.currentTurn(task.id); val pending = call(originalTurn)
        store.finish(first, SliceOutcome.YIELDED, 1); val nextTurn = store.nextTurn(task.id)
        val next = store.claim(task.id, "second")!!
        var dispatches = 0
        val adapter = object : AtlasToolAdapter {
            override val definition = ToolDefinition("probe", "probe", "{}", ToolRisk.READ_ONLY)
            override suspend fun execute(call: AtlasToolCall): ToolExecutionResult {
                val ctx = currentCoroutineContext()[ToolExecutionContext]!!
                assertEquals(originalTurn, call.turnId); assertEquals(nextTurn, ctx.executingTurnId)
                dispatches++; return ToolExecutionResult("{\"ok\":true}")
            }
        }
        val harness = ToolHarness(db, { _, _ -> error("No camera") }, { DeviceHealth() }, additionalTools = listOf(adapter), executionLease = { next })
        assertTrue(runCatching { harness.execute(pending) }.isFailure) // no implicit old-slice authority
        withContext(ToolExecutionContext(next, nextTurn, pending.id, originalTurn)) { harness.execute(pending) }
        assertEquals(1, dispatches)
        assertEquals(originalTurn, db.loadToolCall(pending.id)!!.turnId)
        assertTrue(runCatching { withContext(ToolExecutionContext(first, nextTurn, pending.id, originalTurn)) { harness.execute(pending) } }.isFailure)
        val other = task(); val foreign = call(store.currentTurn(other.id))
        assertTrue(runCatching { withContext(ToolExecutionContext(next, nextTurn, foreign.id, foreign.turnId)) { harness.execute(foreign) } }.isFailure)
        assertTrue(runCatching { withContext(ToolExecutionContext(next, nextTurn, pending.id, originalTurn)) { harness.execute(pending.copy(argumentsJson="{\"changed\":true}")) } }.isFailure)
        store.control(task.id, ExecutionState.CANCELLED, "stop")
        assertTrue(runCatching { withContext(ToolExecutionContext(next, nextTurn, pending.id, originalTurn)) { harness.execute(pending) } }.isFailure)
        assertEquals(1, dispatches)
    }

    @Test fun reportDrilldownIsExactBoundedAndSessionScoped() = runTest {
        val t = task(); store.claim(t.id, "owner")
        val original = call(store.currentTurn(t.id), "workspace_package_ui_test")
        val body = JSONObject().put("passed", false).put("detail", "x".repeat(10000)).toString()
        db.updateToolCall(original.id, ToolCallStatus.COMPLETED, resultJson=body)
        val reader = ToolResultRead(db)
        val read = call(store.currentTurn(t.id), "atlas_tool_result_read").copy(argumentsJson=JSONObject().put("operation_id",original.id).put("start_char",100).put("max_chars",1200).toString())
        val result = JSONObject(reader.execute(read).resultJson)
        assertEquals(body.substring(100,1300),result.getString("fragment")); assertEquals(1300,result.getInt("next_char"))
        assertEquals(body,db.loadToolCall(original.id)!!.resultJson)
        val other = db.createSession("Other", "Other")
        assertTrue(runCatching {reader.execute(read.copy(sessionId=other.id))}.isFailure)
        assertTrue(runCatching {reader.execute(read.copy(argumentsJson=JSONObject().put("operation_id",original.id).put("max_chars",6001).toString()))}.isFailure)
    }
}
