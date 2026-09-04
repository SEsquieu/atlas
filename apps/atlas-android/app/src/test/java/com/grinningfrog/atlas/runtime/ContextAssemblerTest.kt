package com.grinningfrog.atlas.runtime

import com.grinningfrog.atlas.model.AtlasMessage
import com.grinningfrog.atlas.model.AtlasSession
import com.grinningfrog.atlas.model.DeliveryStatus
import com.grinningfrog.atlas.model.MemoryItem
import com.grinningfrog.atlas.model.MemoryKind
import com.grinningfrog.atlas.model.MemoryStatus
import com.grinningfrog.atlas.model.MessageKind
import com.grinningfrog.atlas.model.MessageRole
import com.grinningfrog.atlas.model.PendingClarification
import com.grinningfrog.atlas.model.ClarificationAmbiguity
import com.grinningfrog.atlas.model.SessionStatus
import com.grinningfrog.atlas.model.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextAssemblerTest {
    @Test fun reconstructsConversationSummaryAndAdmittedMemory() {
        val context = ContextAssembler().assemble(
            session = session(),
            messages = listOf(
                message(1, "turn-1", MessageRole.USER, "Call the red cable A."),
                message(2, "turn-1", MessageRole.ASSISTANT, "Understood."),
                message(3, "turn-2", MessageRole.USER, "Which one did I call A?"),
            ),
            memories = listOf(memory(MemoryKind.TASK, "Identify cable A before giving the next step.")),
            summary = SessionSummary("session", "The user is labeling workbench cables.", 0, 1),
            observation = null,
            nowMs = 10,
        )

        assertEquals(listOf("Call the red cable A.", "Understood.", "Which one did I call A?"), context.messages.map { it.content })
        assertTrue(context.systemPrompt.contains("The user is labeling workbench cables."))
        assertTrue(context.systemPrompt.contains("Identify cable A"))
    }

    @Test fun truncationKeepsToolProtocolOnWholeTurnBoundaries() {
        val context = ContextAssembler(ContextBudget(maxConversationCharacters = 10_000, maxMessages = 2)).assemble(
            session = session(),
            messages = listOf(
                message(1, "old", MessageRole.USER, "Old question"),
                message(2, "old", MessageRole.ASSISTANT, "Old answer"),
                message(3, "tool-turn", MessageRole.USER, "Look again"),
                message(4, "tool-turn", MessageRole.ASSISTANT, ""),
                message(5, "tool-turn", MessageRole.TOOL, "{\"ok\":true}", MessageKind.TOOL_RESULT),
                message(6, "tool-turn", MessageRole.ASSISTANT, "It moved."),
            ),
            memories = emptyList(), summary = null, observation = null, nowMs = 10,
        )

        assertEquals(4, context.messages.size)
        assertTrue(context.messages.all { it.content != "Old question" && it.content != "Old answer" })
        assertEquals(2, context.omittedMessageCount)
    }

    @Test fun internalMessagesNeverLeakIntoProviderContext() {
        val context = ContextAssembler().assemble(
            session(),
            listOf(message(1, "turn", MessageRole.USER, "hello"), message(2, "turn", MessageRole.ASSISTANT, "secret trace", MessageKind.INTERNAL)),
            emptyList(), null, null, 10,
        )
        assertFalse(context.messages.any { it.content == "secret trace" })
    }

    @Test fun physicalMemoryKeepsEvidenceConfidenceAndDeterministicExpiry() {
        val item = MemoryItem(
            id = "environment-1", sessionId = "session", kind = MemoryKind.ENVIRONMENT,
            content = "The red cable is on the left.", status = MemoryStatus.ACTIVE, confidence = .8,
            sourceTurnId = "turn", evidenceObservationId = "observation-1",
            createdAtMs = 0, updatedAtMs = 0, expiresAtMs = 2_000,
        )
        val context = ContextAssembler().assemble(session(), emptyList(), listOf(item), null, null, nowMs = 1_250)

        assertTrue(context.systemPrompt.contains("confidence=0.8"))
        assertTrue(context.systemPrompt.contains("evidence=observation-1"))
        assertTrue(context.systemPrompt.contains("expiresInMs=750"))
    }

    @Test fun interruptedSpeechOnlyReturnsConfirmedHeardTextToTheNextModel() {
        val interrupted = message(2, "turn-1", MessageRole.ASSISTANT, "First sentence. Second sentence. Third sentence.").copy(
            deliveryStatus = DeliveryStatus.INTERRUPTED,
            deliveredContent = "First sentence.",
            interruptedSentence = "Second sentence.",
        )
        val context = ContextAssembler().assemble(
            session(),
            listOf(message(1, "turn-1", MessageRole.USER, "Tell me the steps."), interrupted, message(3, "turn-2", MessageRole.USER, "Continue.")),
            emptyList(), null, null, 10,
        )

        assertTrue(context.messages[1].content.startsWith("First sentence."))
        assertTrue(context.messages[1].content.contains("user interrupted playback"))
        assertFalse(context.messages[1].content.contains("Third sentence"))
    }

    @Test fun pendingClarificationIsStructuredAndSurvivesTangents() {
        val pending = PendingClarification(
            id = "clarify-1", sessionId = "session", sourceTurnId = "turn-1",
            question = "The black connector or the gray one?", reason = "The next step differs.",
            ambiguity = ClarificationAmbiguity.REFERENT, options = listOf("Black", "Gray"),
            blocking = true, createdAtMs = 1, updatedAtMs = 2,
        )
        val context = ContextAssembler().assemble(
            session(), listOf(message(1, "turn-2", MessageRole.USER, "What's the score?")),
            emptyList(), null, null, 10, pendingClarification = pending,
        )

        assertTrue(context.systemPrompt.contains("id=clarify-1"))
        assertTrue(context.systemPrompt.contains("The black connector or the gray one?"))
        assertTrue(context.systemPrompt.contains("Tangents do not cancel"))
        assertTrue(context.systemPrompt.contains("resolve, defer, or abandon"))
    }

    private fun session() = AtlasSession("session", "test", "stay coherent", SessionStatus.ACTIVE, 0, 0)
    private fun message(sequence: Long, turn: String, role: MessageRole, content: String, kind: MessageKind = MessageKind.DIALOGUE) = AtlasMessage(
        sequence = sequence, id = "message-$sequence", sessionId = "session", turnId = turn, role = role, content = content, createdAtMs = sequence, kind = kind,
    )
    private fun memory(kind: MemoryKind, content: String) = MemoryItem("memory", "session", kind, content, MemoryStatus.ACTIVE, 1.0, "turn", null, 0, 0)
}
