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
    @Test fun discoveryPolicyIsSuppliedOnlyWhenCoreToolsAreAvailable() {
        val available = ContextAssembler().assemble(session(), emptyList(), emptyList(), null, null, 1, toolsAvailable = true)
        val unavailable = ContextAssembler().assemble(session(), emptyList(), emptyList(), null, null, 1, toolsAvailable = false)
        assertTrue(available.systemPrompt.contains("Discovery is read-only"))
        assertTrue(available.systemPrompt.contains("atlas_capability_discovery"))
        assertFalse(unavailable.systemPrompt.contains("atlas_capability_discovery"))
    }

    @Test fun oversizedCurrentTurnFailsWithRecoveryEvidence() {
        val error = runCatching { ContextAssembler(ContextBudget(maxConversationCharacters = 10)).assemble(
            session(), listOf(message(1, "turn", MessageRole.USER, "x".repeat(11))), emptyList(), null, null, 1)
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("transcript and memory remain saved"))
    }

    @Test fun omittedHistoryIsExplicitAndExpiredMemoryIsExcluded() {
        val expired = memory(MemoryKind.TASK, "expired secret").copy(expiresAtMs = 9)
        val context = ContextAssembler(ContextBudget(maxMessages = 1)).assemble(session(), listOf(
            message(1, "old", MessageRole.USER, "old"), message(2, "new", MessageRole.USER, "new")),
            listOf(expired), null, null, 10, archiveNotice = "Recovered paused session")
        assertTrue(context.systemPrompt.contains("1 transcript messages are omitted"))
        assertTrue(context.systemPrompt.contains("Recovered paused session"))
        assertFalse(context.systemPrompt.contains("expired secret"))
    }

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
        val context = ContextAssembler(ContextBudget(maxConversationCharacters = 10_000, maxMessages = 4)).assemble(
            session = session(),
            messages = listOf(
                message(1, "old", MessageRole.USER, "Old question"),
                message(2, "old", MessageRole.ASSISTANT, "Old answer"),
                message(3, "tool-turn", MessageRole.USER, "Look again"),
                message(4, "tool-turn", MessageRole.ASSISTANT, "").copy(
                    toolCallsJson = """[{"id":"call-observe","name":"capture_current_view","arguments":"{}"}]""",
                ),
                message(5, "tool-turn", MessageRole.TOOL, "{\"ok\":true}", MessageKind.TOOL_RESULT).copy(toolCallId = "call-observe"),
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

    @Test fun providerEvidenceSurvivesIntoLaterTurnsWithoutChangingVisibleContent() {
        val prior = message(2, "turn-1", MessageRole.ASSISTANT, "The store page did not load.").copy(
            providerContextJson = "{\"providerToolUses\":[{\"type\":\"web_search\",\"action\":\"open_page\",\"status\":\"completed\",\"urls\":[\"https://example.com/ad\"]}]}",
        )
        val context = ContextAssembler().assemble(
            session(), listOf(message(1, "turn-1", MessageRole.USER, "Check the ad"), prior),
            emptyList(), null, null, 10,
        )

        assertTrue(context.messages[1].content.startsWith("The store page did not load."))
        assertTrue(context.messages[1].content.contains("authoritative audit context"))
        assertTrue(context.messages[1].content.contains("open_page"))
        assertTrue(context.messages[1].content.contains("https://example.com/ad"))
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

    @Test fun activeWorkspaceContextIsExplicitAndVersioned() {
        val workspace = """{"id":"workspace-1","name":"Bench","revisionId":"revision-7","navigationSequence":4,"definition":{"format":"atlas.workspace.v1","title":"Bench","components":[]}}"""
        val context = ContextAssembler().assemble(
            session(), emptyList(), emptyList(), null, null, 10, workspaceContextJson = workspace,
        )

        assertTrue(context.systemPrompt.contains("ACTIVE COMPOSABLE WORKSPACE"))
        assertTrue(context.systemPrompt.contains("workspace-1"))
        assertTrue(context.systemPrompt.contains("revision-7"))
        assertTrue(context.systemPrompt.contains("navigation sequence"))
    }

    @Test fun orphanedClarificationResultCannotPoisonResumedProviderContext() {
        val context = ContextAssembler().assemble(
            session(),
            listOf(
                message(1, "turn-1", MessageRole.USER, "I added workspaces."),
                message(2, "turn-1", MessageRole.ASSISTANT, "What should we build first?"),
                message(3, "turn-1", MessageRole.TOOL, "{\"ok\":true}", MessageKind.TOOL_RESULT)
                    .copy(toolCallId = "call-orphan"),
                message(4, "turn-2", MessageRole.USER, "Build me a working MUD."),
            ),
            emptyList(), null, null, 10,
        )

        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.USER), context.messages.map { it.role })
        assertFalse(context.messages.any { it.toolCallId == "call-orphan" })
    }

    @Test fun completeToolCallAndResultPairSurvivesSanitization() {
        val assistant = message(2, "turn-1", MessageRole.ASSISTANT, "").copy(
            toolCallsJson = """[{"id":"call-1","name":"workspace_create","arguments":"{}"}]""",
        )
        val context = ContextAssembler().assemble(
            session(),
            listOf(
                message(1, "turn-1", MessageRole.USER, "Build it."),
                assistant,
                message(3, "turn-1", MessageRole.TOOL, "{\"ok\":true}", MessageKind.TOOL_RESULT).copy(toolCallId = "call-1"),
            ),
            emptyList(), null, null, 10,
        )

        assertEquals("workspace_create", context.messages[1].toolCalls.single().name)
        assertEquals("call-1", context.messages[2].toolCallId)
    }

    @Test fun largePackageSourceIsProjectedWithoutBreakingMixedToolBatchProtocol() {
        val arguments = org.json.JSONObject().put("workspace_id", "workspace").put("files", org.json.JSONObject().put("ui/app.js", "x".repeat(100_000))).toString()
        val calls = org.json.JSONArray().put(org.json.JSONObject().put("id", "stage").put("name", "workspace_package_stage").put("arguments", arguments))
            .put(org.json.JSONObject().put("id", "inspect").put("name", "workspace_package_inspect").put("arguments", "{}"))
        val original = message(2, "turn", MessageRole.ASSISTANT, "Building").copy(toolCallsJson = calls.toString())
        val context = ContextAssembler().assemble(session(), listOf(message(1, "turn", MessageRole.USER, "Build"), original,
            message(3, "turn", MessageRole.TOOL, "{\"candidate_id\":\"immutable\"}", MessageKind.TOOL_RESULT).copy(toolCallId = "stage"),
            message(4, "turn", MessageRole.TOOL, "{}", MessageKind.TOOL_RESULT).copy(toolCallId = "inspect")), emptyList(), null, null, 10)
        assertTrue(context.messages.all { it.toolCalls.isEmpty() && it.toolCallId == null })
        assertTrue(context.messages.any { it.content.contains("immutable") })
        assertTrue(context.messages.any { it.content.contains("source projection") })
        assertTrue(original.toolCallsJson!!.length > 100_000)
        assertTrue(context.approximateCharacters < 20_000)
    }

    @Test fun earlierSourceReadsBecomePinnedReferencesOnlyWhenTheTurnNeedsSpace() {
        val messages = mutableListOf(message(1, "turn", MessageRole.USER, "Inspect files"))
        for (i in 0..2) {
            val calls = org.json.JSONArray().put(org.json.JSONObject().put("id", "read-$i").put("name", "workspace_package_read_file").put("arguments", "{}"))
            messages += message((2 + i * 2).toLong(), "turn", MessageRole.ASSISTANT, "").copy(toolCallsJson = calls.toString())
            messages += message((3 + i * 2).toLong(), "turn", MessageRole.TOOL, org.json.JSONObject().put("source", "x".repeat(1000)).put("revision_id", "pinned").put("path", "file-$i.js").toString(), MessageKind.TOOL_RESULT).copy(toolCallId = "read-$i")
        }
        val context = ContextAssembler(ContextBudget(maxConversationCharacters = 2900)).assemble(session(), messages, emptyList(), null, null, 10)
        assertTrue(context.messages.any { it.content.contains("source_omitted") })
        assertTrue(context.messages.last().content.contains("x".repeat(1000)))
        assertEquals("read-2", context.messages.last().toolCallId)
        assertTrue(messages[2].content.contains("x".repeat(1000)))
    }

    @Test fun legacyInspectionBatchRecoversBeforeGuardAndPreservesProtocolOnResume() {
        val contract = org.json.JSONObject().put("capability_registry", org.json.JSONObject().put("rules", "r".repeat(5000)))
            .put("device_capabilities", org.json.JSONObject().put("rules", "r".repeat(5000)))
        val results = listOf(
            "workspace_package_contract" to contract.toString(),
            "workspace_package_inspect" to org.json.JSONObject().put("workspace_id", "bench").put("live_revision_id", "pinned")
                .put("contract", contract).put("files", org.json.JSONArray(listOf("ui/app.js"))).toString(),
            "workspace_render_diagnostics" to org.json.JSONObject().put("revision_id", "pinned").put("layout", "l".repeat(18000)).toString())
        val calls = org.json.JSONArray()
        results.forEachIndexed { i, result -> calls.put(org.json.JSONObject().put("id", "call-$i").put("name", result.first).put("arguments", "{}")) }
        val messages = listOf(message(1, "turn", MessageRole.USER, "Repair Bench Companion"),
            message(2, "turn", MessageRole.ASSISTANT, "").copy(toolCallsJson = calls.toString())) + results.mapIndexed { i, result ->
            message((i + 3).toLong(), "turn", MessageRole.TOOL, result.second, MessageKind.TOOL_RESULT).copy(toolCallId = "call-$i") }
        val assembler = ContextAssembler()
        val first = assembler.assemble(session(), messages, emptyList(), null, null, 10)
        val resumed = assembler.assemble(session(), messages, emptyList(), null, null, 20)
        assertEquals(first.messages, resumed.messages)
        assertEquals(3, first.messages[1].toolCalls.size)
        assertEquals(listOf("call-0", "call-1", "call-2"), first.messages.drop(2).map { it.toolCallId })
        assertFalse(org.json.JSONObject(first.messages[2].content).has("device_capabilities"))
        assertEquals("workspace_package_contract", org.json.JSONObject(first.messages[3].content).getString("contract_tool"))
        assertTrue(first.messages[3].content.contains("pinned"))
        assertTrue(first.projectedMessageIds.contains("message-3"))
        assertTrue(messages[3].content.contains("contract"))
    }

    @Test fun oversizedDiagnosticsBecomeExplicitPartialEvidenceWithoutLosingMutationReceipt() {
        val diagnostics = org.json.JSONObject().put("revision_id", "pinned").put("structural_errors", org.json.JSONArray(listOf("BROKEN")))
            .put("layout", "l".repeat(50000)).toString()
        val calls = """[{"id":"read","name":"workspace_render_diagnostics","arguments":"{}"},{"id":"write","name":"workspace_package_activate","arguments":"{}"}]"""
        val mutation = """{"activated":false,"error":"STALE_REVISION","candidate_id":"candidate"}"""
        val messages = listOf(message(1, "turn", MessageRole.USER, "Fix it"),
            message(2, "turn", MessageRole.ASSISTANT, "").copy(toolCallsJson = calls),
            message(3, "turn", MessageRole.TOOL, diagnostics, MessageKind.TOOL_RESULT).copy(toolCallId = "read"),
            message(4, "turn", MessageRole.TOOL, mutation, MessageKind.TOOL_RESULT).copy(toolCallId = "write"))
        val context = ContextAssembler().assemble(session(), messages, emptyList(), null, null, 10)
        val projected = org.json.JSONObject(context.messages[2].content)
        assertTrue(projected.getBoolean("context_projection"))
        assertTrue(projected.toString().contains("BROKEN"))
        assertEquals(com.grinningfrog.atlas.workspace.application.WorkspaceApplicationContract.hash(diagnostics), projected.getString("original_sha256"))
        assertEquals(mutation, context.messages[3].content)
        assertTrue(context.messages.sumOf { it.content.length } < 32000)
        assertEquals(diagnostics, messages[2].content)
    }

    private fun session() = AtlasSession("session", "test", "stay coherent", SessionStatus.ACTIVE, 0, 0)
    private fun message(sequence: Long, turn: String, role: MessageRole, content: String, kind: MessageKind = MessageKind.DIALOGUE) = AtlasMessage(
        sequence = sequence, id = "message-$sequence", sessionId = "session", turnId = turn, role = role, content = content, createdAtMs = sequence, kind = kind,
    )
    private fun memory(kind: MemoryKind, content: String) = MemoryItem("memory", "session", kind, content, MemoryStatus.ACTIVE, 1.0, "turn", null, 0, 0)
}
