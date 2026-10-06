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
    @Test fun clippedProviderResponseCannotBecomeCheckpoint() {
        for (reason in listOf("length", "incomplete", null)) assertTrue(runCatching { CheckpointPlanner.validateResponse("partial", reason) }.isFailure)
        assertTrue(runCatching { CheckpointPlanner.validateResponse("x".repeat(8001), "stop") }.isFailure)
        CheckpointPlanner.validateResponse("complete", "stop")
    }
}
