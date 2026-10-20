package com.grinningfrog.atlas.runtime

import com.grinningfrog.atlas.model.*
import org.junit.Assert.*
import org.junit.Test

class ControlRecoveryTest {
    private fun proposal(args: String) = ToolCallProposal("control", ToolHarness.CLARIFICATION_TOOL, args)
    private fun call(name: String, status: ToolCallStatus, error: String? = "Denied") = AtlasToolCall("id", "session", "turn", name, "{}", status, ToolRisk.SESSION_WRITE, false, "key", "reason", error = error, createdAtMs = 0, updatedAtMs = 0)
    @Test fun missingReasonReturnsFeedbackAndCorrectedQuestionCanProceed() {
        val invalid = proposal("""{"action":"request","question":"Which value?"}""")
        val failure = ClarificationControl.rejection(listOf(invalid), null)!!
        assertEquals("invalid_clarification_control", failure.code)
        assertFalse(ToolFailurePayload.json(failure, failure.message!!).getBoolean("ok"))
        assertNull(ClarificationControl.rejection(listOf(proposal("""{"action":"request","question":"Which value?","reason":"Two possible values","options":["First","Second"]}""")), null))
    }
    @Test fun staleResolveAndMixedBatchRejectBeforeControlHandling() {
        val stale = ClarificationControl.rejection(listOf(proposal("""{"action":"resolve","clarification_id":"old"}""")), "current")!!
        assertEquals("current", stale.recovery.getString("pending_clarification_id"))
        assertNotNull(ClarificationControl.rejection(listOf(proposal("""{"action":"request","question":"Which?","reason":"Ambiguous"}"""), ToolCallProposal("write", "workspace_create", "{}")), null))
        assertNotNull(ClarificationControl.rejection(listOf(proposal("not json")), null))
    }
    @Test fun repeatedDeterministicRejectionsSuppressButSuccessAndDifferentReasonReset() {
        val rejected = call("edit", ToolCallStatus.REJECTED)
        assertTrue(ToolRetryPolicy.repeatedRejections(listOf(rejected)).isEmpty())
        assertEquals(setOf("edit"), ToolRetryPolicy.repeatedRejections(listOf(rejected, rejected)))
        assertTrue(ToolRetryPolicy.repeatedRejections(listOf(rejected, call("edit", ToolCallStatus.REJECTED, "Different"))).isEmpty())
        assertTrue(ToolRetryPolicy.repeatedRejections(listOf(rejected, rejected, call("edit", ToolCallStatus.COMPLETED, null))).isEmpty())
        assertTrue(ToolRetryPolicy.repeatedRejections(listOf(call("search", ToolCallStatus.FAILED), call("search", ToolCallStatus.FAILED))).isEmpty())
    }
    @Test fun deterministicRetryHistoryIgnoresSliceAndIdButAllowsCorrectionsAndTransientRetry() {
        val first=call("atlas_task_handoff_pixels",ToolCallStatus.FAILED,"user_quote must quote the latest user instruction in this task")
        val second=first.copy(id="second",turnId="next-slice")
        val next=first.copy(id="next",turnId="third-slice")
        assertTrue(ToolRetryPolicy.identicalDeterministicFailure(next,listOf(first,second)))
        assertFalse(ToolRetryPolicy.identicalDeterministicFailure(next.copy(argumentsJson="{\"user_quote\":\"actual user instruction\"}"),listOf(first,second)))
        val transient=first.copy(name="workspace_package_stage",error="temporary database unavailable")
        assertFalse(ToolRetryPolicy.identicalDeterministicFailure(transient.copy(id="next"),listOf(transient,transient.copy(id="second"))))
        assertTrue(ToolRetryPolicy.sameArguments("{\"a\":1,\"b\":2}","{ \"b\":2, \"a\":1 }"))
    }

}
