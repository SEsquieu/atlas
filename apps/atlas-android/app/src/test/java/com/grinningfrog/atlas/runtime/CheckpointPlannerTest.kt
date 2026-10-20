package com.grinningfrog.atlas.runtime

import com.grinningfrog.atlas.model.*
import org.junit.Assert.*
import org.junit.Test

class CheckpointPlannerTest {
    private fun message(sequence: Long, turn: String, text: String) = AtlasMessage(sequence, "m$sequence", "session", turn, MessageRole.USER, text, 0, MessageKind.DIALOGUE)
    @Test fun budgetNeverAdvancesBeyondSuppliedWholeTurns() {
        val batch = CheckpointPlanner.select(listOf(message(1, "first", "a"), message(2, "second", "b".repeat(30)), message(3, "second", "c")), 20)
        assertEquals(listOf(1L), batch.messages.map { it.sequence })
        assertFalse(batch.transcript.contains("bbbb"))
    }
    @Test fun oversizedOldestTurnFailsInsteadOfSkippingItsHistory() {
        assertTrue(runCatching { CheckpointPlanner.select(listOf(message(1, "first", "a".repeat(30)), message(2, "second", "b")), 20) }.isFailure)
    }
    @Test fun largeToolPayloadProjectsToDurableEvidenceWithoutDroppingWholeTurn() {
        val user=message(1,"first","Preserve my save data")
        val tool=message(2,"first","{\"source\":\""+"x".repeat(30000)+"\"}").copy(role=MessageRole.TOOL,kind=MessageKind.TOOL_RESULT,toolCallId="source-call")
        val batch=CheckpointPlanner.select(listOf(user,tool),2000)
        assertEquals(listOf(1L,2L),batch.messages.map {it.sequence})
        assertTrue(batch.transcript.contains("Preserve my save data"))
        assertTrue(batch.transcript.contains("sha256="))
        assertTrue(batch.transcript.length<2000)
        assertEquals(tool.content,batch.messages.last().content)
    }
    @Test fun clippedProviderResponseCannotBecomeCheckpoint() {
        for (reason in listOf("length", "incomplete", null)) assertTrue(runCatching { CheckpointPlanner.validateResponse("partial", reason) }.isFailure)
        assertTrue(runCatching { CheckpointPlanner.validateResponse("x".repeat(8001), "stop") }.isFailure)
        CheckpointPlanner.validateResponse("complete", "stop")
    }
}
