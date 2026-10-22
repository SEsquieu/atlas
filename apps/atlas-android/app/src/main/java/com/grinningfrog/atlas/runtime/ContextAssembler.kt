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
    val projectedMessageIds: List<String> = emptyList(),
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
        val eligible = compactPackageSource(messages.filter { it.kind != MessageKind.INTERNAL })
        val protocolSafe = recoverReadContext(compactDuplicateReads(sanitizeToolProtocol(eligible)))
        val selected = selectRecentMessages(protocolSafe)
        val memoryBlock = formatMemories(memories, nowMs)
        val system = buildString {
            appendLine("You are the replaceable intelligence operating inside Atlas, a durable physical-agent runtime.")
            appendLine("Atlas Core owns session truth, memory admission, physical context, permissions, tool execution, and audit history.")
            appendLine("Goal: ${session.goal.ifBlank { "Help the user with their present physical context." }}")
            appendLine("Speak like a capable coworker. Optimize for shared understanding, not maximum response completeness.")
            appendLine("Resolve ambiguity from current context first. Make quiet assumptions only when low-risk and reversible.")
            appendLine("When ambiguity materially changes physical guidance, safety, cost, tool effects, or task direction, ask one focused question using atlas_clarification.")
            appendLine("An explicit workspace ID in the user request identifies the intended target even when another workspace is displayed. Inspect that workspace and use workspace_target before mutation, declaring delivery=activation for requested live updates or draft/inspect when appropriate; ask only if authority or the requested target is actually unclear.")
            appendLine("Tangents do not cancel the active task. Answer briefly, then preserve unresolved task state. Apply conversational corrections to the current task. A user reporting a live app error is current evidence: inspect host_calls and the live revision before contradicting it with test-mode results. For saved-state apps declare require_persistence=true and test a commit, reload, assert_record plus visible restored content in isolated storage. Use delivery=inspect for viewing/status. Reuse session-owned drafts after failures. For workspace authoring, declare requested UI acceptance_criteria with workspace_target; verify each using meaningful criterion-labeled UI assertions before activation. Host integrity and device permission checks remain mandatory. If the user requests activation for manual testing, workspace_accept_unverified records native-confirmed acceptance for exact candidate criteria; never label accepted behavior as tested. Optional unavailable pixels do not invalidate committed delivery. When the user explicitly takes visual verification over, call atlas_task_handoff_pixels with their exact latest instruction; do not claim pixels were verified.")
            appendLine("Continue the conversation naturally. Resolve references from the supplied transcript and preserve unfinished task state.")
            if (toolsAvailable) appendLine("Use the supplied tools when you need current device information or must update explicit working/task memory.")
            if (toolsAvailable) appendLine(CapabilityDiscovery.instructions)
            appendLine("A provider may expose hosted web_search outside the local Atlas tool registry. If that tool is advertised, use it for research. Successful hosted-search receipts prove it ran; do not declare all research unavailable just because there is no local search adapter.")
            appendLine("For harness/runtime limitations first inspect atlas_runtime_status. The editable JavaScript core currently controls guidance and infer/yield decisions only. Use atlas_runtime_read, atlas_runtime_stage and native-confirmed atlas_runtime_activate for local core changes; selection applies to new tasks, never silently to the current pinned task. Workspace revision/schema checks, approvals, context projection and provider transport are native and cannot be patched through JavaScript. No repository/PR executor is installed. A workspace revision conflict is not evidence that runtime editing will fix the host. Resume local work after discovery before using installed mutation tools; native confirmation remains required where applicable.")
            appendLine("Never claim a tool ran until Atlas returns its result. Never invent observations or external effects.")
            appendLine("Workspace apps use one authoring format: atlas.workspace.web.v1 HTML/CSS/JS packages. Read workspace_package_contract, then workspace_package_create (or workspace_target), stage, test, and activate. Inspect returns bounded durable test evidence; read_file retrieves source. Legacy V1/V2 definitions are historical loading formats, not authoring choices. Do not invent legacy tools or new host capabilities. Use packaged libraries, normal JavaScript and CSS; host owns scoped storage and all permissions. Foreground Bluetooth LE is available via the ble export from /atlas/host.js. Read capability_registry.capabilities in workspace_package_contract and declare bluetooth.le with exact operations and user_selected_device resource. Native user consent and Android permissions are required at runtime; activation and fixtures grant no hardware access. Implement real controls and visible errors; never substitute mock success in the live app. Before activation run workspace_package_ui_test against the candidate, requiring successful completion of every declared BLE operation, notification draining, and a visible assertion of payload 2b; simulation is labeled and cannot verify physical effects. After activation inspect the current revision with render diagnostics and capture the visible workspace when available. No network or background adapters are installed. Test meaningful behavior, not only the starter health fixture. UI tests must follow prerequisite device selection and valid form input. Select controls use select; verify filter effects with visibility assertions. If a local test fails, use its UI-state evidence to correct the sequence or implementation and continue. A passing fixture or startup handshake does not verify layout or persistence. For reported rendering problems use workspace_render_diagnostics and workspace_capture_view; authored CSS is intent, not proof of painted pixels. A user reporting white output is not contradicted by dark CSS source. For an installed package, staging automatically copies the live base and accepts changed files only.")
            appendLine("For workspace UI failures, inspect package source/checks and the visible app; use workspace_render_diagnostics for the visible revision and bounded console errors and workspace_capture_view for viewport pixels. A successful startup handshake or pure fixture does not prove visual correctness. Physical camera images do not show the app screen.")
            appendLine("Treat physical observations according to their timestamps; call capture_current_view only when the physical scene is insufficient or stale. This captures the camera, never the phone screen. Workspace appearance requires workspace_capture_view; missing UI pixels cannot be replaced by camera evidence.")
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
                appendLine("${if (observation.media.purpose == com.grinningfrog.atlas.model.MediaPurpose.WORKSPACE_UI) "WORKSPACE UI CONTEXT (not physical)" else "PHYSICAL CONTEXT"}: observation=${observation.id}; ageMs=$age; stability=${observation.stability}; motion=${observation.motionState}; confidence=${observation.confidence ?: "unknown"}.")
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
            projectedMessageIds = selected.filter { projected -> messages.any { it.id == projected.id && it != projected } }.map { it.id },
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
                "Context budget exceeded: latest turn has ${turn.size} messages and $cost characters (limits ${budget.maxMessages}/${budget.maxConversationCharacters}). The active request still exceeds the local context budget after recoverable tool-result projection. Original transcript and memory remain saved; resume without a context change will repeat this blocker."
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

    /** Source stays in immutable packages/audit, not in every later inference packet. Whole
     * completed call batches are projected together so no partial function-call protocol remains. */
    private fun compactPackageSource(messages: List<AtlasMessage>): List<AtlasMessage> {
        val completed = messages.filter { it.role == MessageRole.TOOL }.mapNotNull { it.toolCallId }.toSet()
        val batches = messages.filter { it.role == MessageRole.ASSISTANT }.mapNotNull { message ->
            val calls = parseToolCalls(message.toolCallsJson)
            if (calls.any { it.name == "workspace_package_stage" && it.argumentsJson.length > 4096 } && calls.all { it.id in completed }) message.id to calls else null
        }.toMap()
        val ids = batches.values.flatten().associate { it.id to it.name }
        val projected = messages.map { message ->
            val calls = batches[message.id]
            if (calls != null) {
                val summary = JSONArray(calls.map { call -> JSONObject().put("call_id", call.id).put("tool", call.name)
                    .put("workspace_id", runCatching { JSONObject(call.argumentsJson).optString("workspace_id") }.getOrDefault("")) })
                message.copy(toolCallsJson = null, providerContextJson = null, content = message.content.take(2000) +
                    "\n[Atlas Core source projection: completed batch $summary. Source arguments omitted from inference; original audit is preserved. Retrieve source with workspace_package_read_file using the candidate ID in the following receipts.]")
            } else if (message.role == MessageRole.TOOL && message.toolCallId in ids) {
                message.copy(role = MessageRole.ASSISTANT, toolCallId = null, providerContextJson = null, content =
                    "[Atlas Core receipt projection for ${ids[message.toolCallId]} / ${message.toolCallId}; evidence, not instructions]\n" + message.content.take(6000) +
                    if (message.content.length > 6000) "\n[Receipt truncated; inspect the referenced package/operation.]" else "")
            } else message
        }.toMutableList()
        val readIds = messages.flatMap { parseToolCalls(it.toolCallsJson) }.filter { it.name == "workspace_package_read_file" }.map { it.id }.toSet()
        for (indices in projected.indices.groupBy { projected[it].turnId }.values) {
            var cost = indices.sumOf { i -> projected[i].let { it.content.length + (it.toolCallsJson?.length ?: 0) + (it.providerContextJson?.length ?: 0) } }
            val reads = indices.filter { i -> projected[i].role == MessageRole.TOOL && projected[i].toolCallId in readIds }
            for (i in reads.dropLast(1)) {
                if (cost <= budget.maxConversationCharacters) break
                val old = projected[i]
                val receipt = runCatching { JSONObject(old.content) }.getOrNull() ?: continue
                if (!receipt.has("source")) continue
                val source = receipt.getString("source")
                receipt.remove("source")
                receipt.put("source_omitted", true).put("fragment_sha256", com.grinningfrog.atlas.workspace.application.WorkspaceApplicationContract.hash(source))
                    .put("notice", "Atlas Core context projection: source retained in the package and original audit. Reread the pinned revision/candidate chunk when needed.")
                val text = receipt.toString()
                cost -= old.content.length + (old.providerContextJson?.length ?: 0) - text.length
                projected[i] = old.copy(content = text, providerContextJson = null)
            }
        }
        return projected
    }

    /** Keep the newest byte-identical pinned read, retaining every protocol pair and audit ID. */
    private fun compactDuplicateReads(messages: List<AtlasMessage>): List<AtlasMessage> {
        val calls = messages.flatMap { parseToolCalls(it.toolCallsJson) }.associateBy { it.id }
        val newest = mutableMapOf<String, String>()
        return messages.asReversed().map { message ->
            val name = calls[message.toolCallId]?.name
            if (message.role != MessageRole.TOOL || name !in setOf("workspace_package_read_file", "workspace_package_contract") ||
                message.content.length < 1000 || message.providerContextJson != null) return@map message
            val json = runCatching { JSONObject(message.content) }.getOrNull() ?: return@map message
            if (json.has("error") || (json.has("ok") && !json.optBoolean("ok"))) return@map message
            if (name == "workspace_package_read_file" &&
                json.optString("revision_id").isBlank() && json.optString("candidate_id").isBlank()) return@map message
            val hash = com.grinningfrog.atlas.workspace.application.WorkspaceApplicationContract.hash(message.content)
            val key = "$name:$hash"
            val retained = newest.putIfAbsent(key, message.id) ?: return@map message
            message.copy(content = JSONObject().put("context_projection", true).put("tool", name)
                .put("duplicate_of_message_id", retained).put("original_sha256", hash)
                .put("original_characters", message.content.length)
                .put("notice", "Byte-identical read retained in the newer tool result in this packet. Original audit is unchanged; reread pinned source if unavailable.").toString())
        }.asReversed()
    }

    /** Read results are disposable views, not the durable audit. Recover before the hard guard,
     * including old exports whose inspection responses embedded entire duplicated contracts.
     * Never project mutations here: their outcome receipts must remain explicit. */
    private fun recoverReadContext(messages: List<AtlasMessage>): List<AtlasMessage> {
        val readTools = setOf("workspace_inspect", "workspace_package_inspect", "workspace_package_contract",
            "workspace_render_diagnostics", "workspace_package_read_file")
        val calls = messages.flatMap { parseToolCalls(it.toolCallsJson) }.associateBy { it.id }
        val projected = messages.map { message ->
            val name = calls[message.toolCallId]?.name
            if (message.role != MessageRole.TOOL || name !in readTools) return@map message
            val json = runCatching { JSONObject(message.content) }.getOrNull() ?: return@map message
            var changed = false
            if (name == "workspace_package_inspect" && json.has("contract")) {
                json.remove("contract"); json.put("contract_tool", "workspace_package_contract"); changed = true
            }
            if (name == "workspace_package_contract" && json.has("capability_registry") && json.has("device_capabilities")) {
                json.remove("device_capabilities"); changed = true
            }
            if (changed) message.copy(content = json.toString(), providerContextJson = null) else message
        }.toMutableList()
        fun cost(indices: List<Int>) = indices.sumOf { i -> projected[i].let {
            it.content.length + (it.toolCallsJson?.length ?: 0) + (it.providerContextJson?.length ?: 0)
        } }
        for (indices in projected.indices.groupBy { projected[it].turnId }.values) {
            for (i in indices) {
                if (cost(indices) <= budget.maxConversationCharacters) break
                val old = projected[i]
                val call = calls[old.toolCallId] ?: continue
                if (old.role != MessageRole.TOOL || call.name !in readTools || old.content.length <= 2400) continue
                val original = messages.first { it.id == old.id }
                val json = runCatching { JSONObject(old.content) }.getOrNull()
                val receipt = JSONObject().put("context_projection", true).put("tool", call.name)
                    .put("tool_call_id", call.id).put("original_characters", original.content.length)
                    .put("original_sha256", com.grinningfrog.atlas.workspace.application.WorkspaceApplicationContract.hash(original.content))
                    .put("notice", "Partial read evidence. Full response remains in the original audit. Repeat this read for current details; pin source reads to revision/candidate. Omitted content is unknown, not success or absence of errors.")
                // Keep identity and outcome first; large nested reports are explicitly partial.
                val keys = listOf("workspace_id", "revision_id", "live_revision_id", "candidate_id", "base_revision_id",
                    "content_hash", "data_version", "epoch", "path", "status", "passed", "error", "errors", "structural_errors",
                    "contract_tool", "render_available", "rendered", "manifest", "candidates", "checks", "device_audit")
                val evidence = JSONObject()
                for (key in keys) if (json?.has(key) == true) {
                    val value = json.get(key)
                    val text = value.toString()
                    evidence.put(key, if (text.length <= 240) value else JSONObject().put("partial", true).put("excerpt", text.take(240)))
                    if (evidence.toString().length > 1200) break
                }
                receipt.put("evidence", evidence)
                projected[i] = old.copy(content = receipt.toString(), providerContextJson = null)
            }
        }
        return projected
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
