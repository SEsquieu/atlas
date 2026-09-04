package com.grinningfrog.atlas.runtime

import com.grinningfrog.atlas.model.AtlasMessage
import com.grinningfrog.atlas.model.AtlasSession
import com.grinningfrog.atlas.model.DeliveryStatus
import com.grinningfrog.atlas.model.InferenceMessage
import com.grinningfrog.atlas.model.MemoryItem
import com.grinningfrog.atlas.model.MemoryKind
import com.grinningfrog.atlas.model.MessageKind
import com.grinningfrog.atlas.model.MessageRole
import com.grinningfrog.atlas.model.PendingClarification
import com.grinningfrog.atlas.model.SessionSummary
import com.grinningfrog.atlas.model.ToolCallProposal
import com.grinningfrog.atlas.model.VisualObservation
import org.json.JSONArray

data class ContextBudget(
    val maxConversationCharacters: Int = 32_000,
    val maxMessages: Int = 32,
    val maxMemoryCharacters: Int = 8_000,
)

data class AssembledContext(
    val systemPrompt: String,
    val messages: List<InferenceMessage>,
    val includedMessageIds: List<String>,
    val omittedMessageCount: Int,
    val approximateCharacters: Int,
)

/** Provider-neutral, deterministic projection of Atlas-owned state into an inference packet. */
class ContextAssembler(private val budget: ContextBudget = ContextBudget()) {
    fun assemble(
        session: AtlasSession,
        messages: List<AtlasMessage>,
        memories: List<MemoryItem>,
        summary: SessionSummary?,
        observation: VisualObservation?,
        nowMs: Long,
        toolsAvailable: Boolean = true,
        pendingClarification: PendingClarification? = null,
    ): AssembledContext {
        val eligible = messages.filter { it.kind != MessageKind.INTERNAL }
        val selected = selectRecentMessages(eligible)
        val memoryBlock = formatMemories(memories, nowMs)
        val system = buildString {
            appendLine("You are the replaceable intelligence operating inside Atlas, a durable physical-agent runtime.")
            appendLine("Atlas Core owns session truth, memory admission, physical context, permissions, tool execution, and audit history.")
            appendLine("Goal: ${session.goal.ifBlank { "Help the user with their present physical context." }}")
            appendLine("Speak like a capable coworker. Optimize for shared understanding, not maximum response completeness.")
            appendLine("Resolve ambiguity from current context first. Make quiet assumptions only when low-risk and reversible.")
            appendLine("When ambiguity materially changes physical guidance, safety, cost, tool effects, or task direction, ask one focused question using atlas_clarification.")
            appendLine("Tangents do not cancel the active task. Answer briefly, then preserve unresolved task state.")
            appendLine("Continue the conversation naturally. Resolve references from the supplied transcript and preserve unfinished task state.")
            if (toolsAvailable) appendLine("Use the supplied tools when you need current device information or must update explicit working/task memory.")
            appendLine("Never claim a tool ran until Atlas returns its result. Never invent observations or external effects.")
            appendLine("Treat physical observations according to their timestamps; call capture_current_view when the supplied view is insufficient or stale.")
            if (toolsAvailable) appendLine("Use atlas_remember for concise session facts, decisions, constraints, and task progress that later turns must retain. Durable user memory requires confirmation.")
            appendLine("Keep spoken answers direct, but do not sacrifice necessary safety context.")
            if (pendingClarification != null) {
                appendLine()
                appendLine("PENDING CLARIFICATION (Core-owned; id=${pendingClarification.id}; blocking=${pendingClarification.blocking}):")
                appendLine(pendingClarification.question)
                appendLine("Reason: ${pendingClarification.reason}")
                appendLine("Treat a short fragment as a possible answer. Call atlas_clarification with resolve, defer, or abandon. Until then, do not propose physical tools when blocking=true.")
            }
            if (summary != null) {
                appendLine()
                appendLine("CONVERSATION CHECKPOINT (derived from earlier turns):")
                appendLine(summary.summary)
            }
            if (memoryBlock.isNotBlank()) {
                appendLine()
                appendLine("ATLAS-ADMITTED MEMORY:")
                appendLine(memoryBlock)
            }
            if (observation != null) {
                val age = (nowMs - observation.observedAtMs).coerceAtLeast(0)
                appendLine()
                appendLine("PHYSICAL CONTEXT: observation=${observation.id}; ageMs=$age; stability=${observation.stability}; motion=${observation.motionState}; confidence=${observation.confidence ?: "unknown"}.")
                observation.summary?.let { appendLine("Prior interpretation: $it") }
            } else {
                appendLine()
                appendLine("PHYSICAL CONTEXT: no usable observation is currently available.")
            }
        }.trim()
        val inferenceMessages = selected.map(::toInferenceMessage)
        return AssembledContext(
            systemPrompt = system,
            messages = inferenceMessages,
            includedMessageIds = selected.map { it.id },
            omittedMessageCount = eligible.size - selected.size,
            approximateCharacters = system.length + inferenceMessages.sumOf { it.content.length },
        )
    }

    private fun selectRecentMessages(messages: List<AtlasMessage>): List<AtlasMessage> {
        val selected = ArrayDeque<List<AtlasMessage>>()
        var characters = 0
        var messageCount = 0
        val turns = messages.fold(mutableListOf<MutableList<AtlasMessage>>()) { groups, message ->
            if (groups.lastOrNull()?.lastOrNull()?.turnId != message.turnId) groups += mutableListOf<AtlasMessage>()
            groups.last() += message
            groups
        }
        for (turn in turns.asReversed()) {
            val cost = turn.sumOf { it.content.length + (it.toolCallsJson?.length ?: 0) }
            if (selected.isNotEmpty() && (messageCount + turn.size > budget.maxMessages || characters + cost > budget.maxConversationCharacters)) break
            selected.addFirst(turn)
            messageCount += turn.size
            characters += cost
        }
        return selected.flatten()
    }

    private fun formatMemories(memories: List<MemoryItem>, nowMs: Long): String {
        var remaining = budget.maxMemoryCharacters
        return buildString {
            MemoryKind.entries.forEach kindLoop@ { kind ->
                val items = memories.filter { it.kind == kind }
                if (items.isEmpty() || remaining <= 0) return@kindLoop
                appendLine("${kind.name}:")
                items.forEach itemLoop@ { item ->
                    if (remaining <= 0) return@itemLoop
                    val evidence = item.evidenceObservationId?.let { "; evidence=$it" }.orEmpty()
                    val expiry = item.expiresAtMs?.let { "; expiresInMs=${(it - nowMs).coerceAtLeast(0)}" }.orEmpty()
                    val line = "- [${item.id}; confidence=${item.confidence}$evidence$expiry] ${item.content}".take(remaining)
                    appendLine(line)
                    remaining -= line.length
                }
            }
        }.trim()
    }

    private fun toInferenceMessage(message: AtlasMessage): InferenceMessage = InferenceMessage(
        role = message.role,
        content = deliveryAwareContent(message),
        toolCallId = message.toolCallId,
        toolCalls = parseToolCalls(message.toolCallsJson),
    )

    private fun deliveryAwareContent(message: AtlasMessage): String {
        if (message.role != MessageRole.ASSISTANT || message.toolCallsJson != null) return message.content
        return when (message.deliveryStatus) {
            DeliveryStatus.INTERRUPTED -> buildString {
                append(message.deliveredContent.orEmpty())
                if (isNotEmpty()) append("\n")
                append("[Atlas delivery note: the user interrupted playback")
                message.interruptedSentence?.let { append(" during: ").append(it) }
                append(". Do not assume the unheard remainder was communicated.]")
            }
            DeliveryStatus.FAILED -> buildString {
                append(message.deliveredContent.orEmpty())
                if (isNotEmpty()) append("\n")
                append("[Atlas delivery note: speech playback failed; do not assume the full response was heard.]")
            }
            else -> message.content
        }
    }

    private fun parseToolCalls(raw: String?): List<ToolCallProposal> = runCatching {
        val array = JSONArray(raw ?: return emptyList())
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(ToolCallProposal(item.getString("id"), item.getString("name"), item.optString("arguments", "{}"), item.optString("reason").ifBlank { null }))
            }
        }
    }.getOrDefault(emptyList())
}
