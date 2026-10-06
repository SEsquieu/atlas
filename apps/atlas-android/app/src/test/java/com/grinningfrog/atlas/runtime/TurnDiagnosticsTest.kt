package com.grinningfrog.atlas.runtime
import com.grinningfrog.atlas.model.*
import org.junit.Assert.*
import org.junit.Test
class TurnDiagnosticsTest {
    private fun turn(status: TurnStatus) = AgentTurn("turn", "session", status, "text", 1000, 4000, 7, if (status == TurnStatus.FAILED) "budget exceeded" else null)
    @Test fun committedRevisionSurvivesFinalReplyFailure() {
        val call = AtlasToolCall("call", "session", "turn", "workspace_replace_definition", "{}", ToolCallStatus.COMPLETED, ToolRisk.SESSION_WRITE, false, "key", resultJson = """{"ok":true,"revision_id":"revision","valid":true,"simulation_passed":true,"tests_run":5,"migration":{}}""", createdAtMs = 2000, updatedAtMs = 3000)
        val report = TurnDiagnostics.project(turn(TurnStatus.FAILED), listOf(call), emptyList())
        assertEquals("REVISION COMMITTED · turn interrupted", report.headline)
        assertEquals(listOf("revision"), report.revisionIds)
        assertEquals("5/5 passed", report.tests)
        assertEquals(3000L, report.elapsedMs(9000))
    }
    @Test fun failureBeforeApplyNeverClaimsACommit() {
        val report = TurnDiagnostics.project(turn(TurnStatus.FAILED), emptyList(), emptyList())
        assertTrue(report.headline.contains("no workspace revision committed"))
        assertEquals("not reported", report.tests)
    }
    @Test fun lateToolProposalIsDistinctFromExecutedTool() {
        val event = AtlasEvent(1, "e", "session", "provider.completed_late", 4000, """{"turnId":"turn","requestId":"request","latencyMs":68721,"toolCallCount":1}""")
        val report = TurnDiagnostics.project(turn(TurnStatus.COMPLETED_LATE), emptyList(), listOf(event))
        assertEquals("LATE RESULT · tool calls were not executed", report.headline)
        assertEquals(0, report.toolCalls); assertEquals(1, report.lateToolCalls); assertEquals(68721L, report.inferenceMs)
    }
    @Test fun unrelatedEventsCannotAffectReport() {
        val event = AtlasEvent(1, "e", "session", "provider.responded", 4000, """{"turnId":"other","latencyMs":99999}""")
        assertEquals(0L, TurnDiagnostics.project(turn(TurnStatus.COMPLETED), emptyList(), listOf(event)).inferenceMs)
    }
}
