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
import org.json.JSONObject

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
        workspaceContextJson: String? = null,
        archiveNotice: String? = null,
    ): AssembledContext {
        val eligible = messages.filter { it.kind != MessageKind.INTERNAL }
        val protocolSafe = sanitizeToolProtocol(eligible)
        val selected = selectRecentMessages(protocolSafe)
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
            archiveNotice?.let { appendLine("RECOVERY NOTICE (Core-owned): $it") }
            val omitted = eligible.size - selected.size
            if (omitted > 0) appendLine("CONTEXT LIMIT: $omitted transcript messages are omitted from this request. A checkpoint is partial evidence, not full recall. Ask for missing details rather than inventing them.")
            if (summary != null) {
                appendLine()
                appendLine("CONVERSATION CHECKPOINT (derived from earlier turns):")
                appendLine(summary.summary.take(8000))
                if (summary.summary.length > 8000) appendLine("CHECKPOINT TRUNCATED at 8,000 characters; remaining summary is unavailable in this request.")
            }
            if (memoryBlock.isNotBlank()) {
                appendLine()
                appendLine("ATLAS-ADMITTED MEMORY:")
                appendLine(memoryBlock)
            }
            if (!workspaceContextJson.isNullOrBlank()) {
                appendLine()
                appendLine("ACTIVE COMPOSABLE WORKSPACE (Core-owned current UI context):")
                appendLine(workspaceContextJson)
                appendLine("Treat this workspace ID, revision, and navigation sequence as authoritative. Use workspace tools for changes; never claim a change applied until Atlas returns its result.")
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
            val cost = turn.sumOf { it.content.length + (it.toolCallsJson?.length ?: 0) + (it.providerContextJson?.length ?: 0) }
            require(selected.isNotEmpty() || (turn.size <= budget.maxMessages && cost <= budget.maxConversationCharacters)) {
                "Context budget exceeded: latest turn has ${turn.size} messages and $cost characters (limits ${budget.maxMessages}/${budget.maxConversationCharacters}). Shorten the current request or start a new session; transcript and memory remain saved."
            }
            if (selected.isNotEmpty() && (messageCount + turn.size > budget.maxMessages || characters + cost > budget.maxConversationCharacters)) break
            selected.addFirst(turn)
            messageCount += turn.size
            characters += cost
        }
        return selected.flatten()
    }

    /**
     * Providers require every tool result to have a matching assistant tool proposal. Old Atlas
     * builds could erase the proposal while rewriting clarification text, and interrupted turns can
     * leave the inverse half-pair. Keep ordinary dialogue while removing only invalid protocol
     * fragments so one historical record cannot poison every later request in the session.
     */
    private fun sanitizeToolProtocol(messages: List<AtlasMessage>): List<AtlasMessage> = messages
        .groupBy { it.turnId }
        .values
        .flatMap { turn ->
            val declaredIds = turn.asSequence()
                .filter { it.role == MessageRole.ASSISTANT }
                .flatMap { parseToolCalls(it.toolCallsJson).asSequence() }
                .map { it.id }
                .toSet()
            val resultIds = turn.asSequence()
                .filter { it.role == MessageRole.TOOL }
                .mapNotNull { it.toolCallId }
                .toSet()
            val validIds = declaredIds intersect resultIds
            turn.mapNotNull { message ->
                when {
                    message.role == MessageRole.TOOL -> message.takeIf { it.toolCallId in validIds }
                    message.role == MessageRole.ASSISTANT && message.toolCallsJson != null -> {
                        val calls = parseToolCalls(message.toolCallsJson).filter { it.id in validIds }
                        when {
                            calls.isNotEmpty() -> message.copy(toolCallsJson = toolCallsJson(calls))
                            message.content.isNotBlank() -> message.copy(toolCallsJson = null)
                            else -> null
                        }
                    }
                    else -> message
                }
            }
        }

    private fun toolCallsJson(calls: List<ToolCallProposal>) = JSONArray().apply {
        calls.forEach { call -> put(JSONObject().apply {
            put("id", call.id); put("name", call.name); put("arguments", call.argumentsJson)
            call.reason?.let { put("reason", it) }
        }) }
    }.toString()

    private fun formatMemories(memories: List<MemoryItem>, nowMs: Long): String {
        var remaining = budget.maxMemoryCharacters
        return buildString {
            MemoryKind.entries.forEach kindLoop@ { kind ->
                val items = memories.filter { it.kind == kind && it.status == com.grinningfrog.atlas.model.MemoryStatus.ACTIVE && (it.expiresAtMs == null || it.expiresAtMs > nowMs) }
                if (items.isEmpty() || remaining <= 0) return@kindLoop
                appendLine("${kind.name}:")
                items.forEach itemLoop@ { item ->
                    if (remaining <= 0) return@itemLoop
                    val evidence = item.evidenceObservationId?.let { "; evidence=$it" }.orEmpty()
                    val expiry = item.expiresAtMs?.let { "; expiresInMs=${(it - nowMs).coerceAtLeast(0)}" }.orEmpty()
                    val line = "- [${item.id}; confidence=${item.confidence}$evidence$expiry] ${item.content}"
                    if (line.length > remaining) { appendLine("[Memory omitted: budget exhausted; do not assume complete recall.]"); remaining = 0; return@itemLoop }
                    appendLine(line)
                    remaining -= line.length
                }
            }
        }.trim()
    }

    private fun toInferenceMessage(message: AtlasMessage): InferenceMessage = InferenceMessage(
        role = message.role,
        content = buildString {
            append(deliveryAwareContent(message))
            message.providerContextJson?.takeIf(String::isNotBlank)?.let {
                append("\n\n[Atlas provider evidence; authoritative audit context, not assistant prose: ")
                append(it)
                append(']')
            }
        },
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
