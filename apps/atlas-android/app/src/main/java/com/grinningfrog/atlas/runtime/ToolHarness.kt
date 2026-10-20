package com.grinningfrog.atlas.runtime

import com.grinningfrog.atlas.data.AtlasDatabase
import com.grinningfrog.atlas.model.AtlasSession
import com.grinningfrog.atlas.model.AtlasToolCall
import com.grinningfrog.atlas.model.DeviceHealth
import com.grinningfrog.atlas.model.MediaPurpose
import com.grinningfrog.atlas.model.MemoryItem
import com.grinningfrog.atlas.model.MemoryKind
import com.grinningfrog.atlas.model.MemoryStatus
import com.grinningfrog.atlas.model.MemoryScope
import com.grinningfrog.atlas.model.ToolCallProposal
import com.grinningfrog.atlas.model.ToolDefinition
import com.grinningfrog.atlas.model.ToolRisk
import com.grinningfrog.atlas.model.VisualObservation
import org.json.JSONObject
import java.util.UUID

data class ToolExecutionResult(
    val resultJson: String,
    val observation: VisualObservation? = null,
)

data class ToolPolicyDecision(
    val allowed: Boolean,
    val requiresConfirmation: Boolean,
    val risk: ToolRisk,
    val reason: String,
)

/** Stable extension point for future phone, wearable, LAN, and account-scoped capabilities. */
interface AtlasToolAdapter {
    val definition: ToolDefinition
    /** Opt in only for installed public, read-only research executors. */
    val supportsCapabilityDiscovery: Boolean get() = false
    /** Installed, scoped, read-only local diagnostics; never grants access to a discovered capability. */
    val supportsLocalDiagnostics: Boolean get() = false
    /** Core-approved installed local mutations only; never external access or permission acquisition. */
    val supportsLocalMutation: Boolean get() = false
    fun evaluate(session: AtlasSession, proposal: ToolCallProposal): ToolPolicyDecision = ToolPolicyDecision(
        allowed = true,
        requiresConfirmation = definition.requiresConfirmation ||
            (definition.risk == ToolRisk.EXTERNAL_EFFECT && session.permissions.externalActionsRequireConfirmation),
        risk = definition.risk,
        reason = "Policy supplied by ${definition.name} adapter",
    )
    suspend fun execute(call: AtlasToolCall): ToolExecutionResult
}
/** Only synchronous SQLite effects may opt in; no network, files, or suspension inside the transaction. */
interface AtlasLocalMutationAdapter : AtlasToolAdapter {
    fun executeLocal(call: AtlasToolCall): ToolExecutionResult
    override suspend fun execute(call: AtlasToolCall) = executeLocal(call)
}
/** Async local adapter whose durable effect and Core operation receipt share one transaction. */
interface AtlasReceiptBackedAdapter : AtlasToolAdapter
/**
 * Core-owned tool boundary. Providers can only propose calls; this harness validates policy,
 * performs the device operation, and returns an auditable result.
 */
class ToolHarness(
    private val database: AtlasDatabase,
    private val capture: suspend (reason: String, purpose: MediaPurpose) -> VisualObservation,
    private val deviceHealth: () -> DeviceHealth,
    private val now: () -> Long = System::currentTimeMillis,
    private val additionalTools: List<AtlasToolAdapter> = emptyList(),
    private val executionLease: () -> ExecutionLease? = { null },
) {
    private val discovery = CapabilityDiscovery(database)
    private val builtInDefinitions: List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "atlas_task_handoff_pixels",
            description = "Record the user's explicit instruction to take workspace visual verification over themselves. Supply an exact 8..500 character quote from the latest user message in this task. Use only when the user requests this handoff, never to bypass a check for convenience. Marks pixels unverified and user-owned; cannot waive pure/UI execution checks, activation, permissions, unknown effects or other required behaviors.",
            parametersJson = """{"type":"object","properties":{"user_quote":{"type":"string","minLength":8,"maxLength":500}},"required":["user_quote"],"additionalProperties":false}""",
            risk = ToolRisk.SESSION_WRITE, maxCallsPerTurn = 1,
        ),
        ToolDefinition(
            name = "capture_current_view",
            description = "Capture the PHYSICAL CAMERA scene, never the phone screen or workspace UI. For app appearance use workspace_capture_view. A black camera frame says nothing about app rendering.",
            parametersJson = """{"type":"object","properties":{"reason":{"type":"string"},"detail":{"type":"boolean"}},"required":["reason"],"additionalProperties":false}""",
            risk = ToolRisk.READ_ONLY,
            maxCallsPerTurn = 3,
        ),
        ToolDefinition(
            name = "get_device_state",
            description = "Read current battery, charging, thermal, network, and motion state from the Atlas device.",
            parametersJson = """{"type":"object","properties":{},"additionalProperties":false}""",
            risk = ToolRisk.READ_ONLY,
            maxCallsPerTurn = 2,
        ),
        ToolDefinition(
            name = "atlas_remember",
            description = "Create, update, or forget explicit Atlas memory. Use working for current constraints, task for progress, environment for sourced physical facts, and durable only for a user preference or fact that should survive beyond the immediate task.",
            parametersJson = """{"type":"object","properties":{"action":{"type":"string","enum":["remember","forget"]},"kind":{"type":"string","enum":["working","task","environment","durable"]},"content":{"type":"string","maxLength":1000},"memory_id":{"type":"string"},"confidence":{"type":"number","minimum":0,"maximum":1},"evidence_observation_id":{"type":"string"},"ttl_seconds":{"type":"integer","minimum":30,"maximum":86400}},"required":["action"],"additionalProperties":false}""",
            risk = ToolRisk.SESSION_WRITE,
            maxCallsPerTurn = 6,
        ),
    )
    private val clarificationDefinition = ToolDefinition(
        name = CLARIFICATION_TOOL,
        description = "Create or update Atlas Core's durable pending clarification. Request one focused question, or resolve, defer, or abandon the pending question.",
        parametersJson = """{"type":"object","properties":{"action":{"type":"string","enum":["request","resolve","defer","abandon"]},"clarification_id":{"type":"string"},"question":{"type":"string","maxLength":300},"reason":{"type":"string","maxLength":500},"ambiguity":{"type":"string","enum":["referent","intent","missing_fact","safety","authority","task_scope","other"]},"options":{"type":"array","items":{"type":"string"},"maxItems":5},"blocking":{"type":"boolean"},"normalized_answer":{"type":"string","maxLength":500}},"required":["action"],"additionalProperties":false}""",
        risk = ToolRisk.SESSION_WRITE,
        maxCallsPerTurn = 2,
    )
    val definitions: List<ToolDefinition> = builtInDefinitions + clarificationDefinition + CapabilityDiscovery.definition + additionalTools.map { it.definition }

    fun atomicLocalTools() = additionalTools.filter { it is AtlasLocalMutationAdapter || it is AtlasReceiptBackedAdapter }.map { it.definition.name } + listOf("atlas_remember","atlas_task_handoff_pixels")

    fun definition(name: String) = definitions.firstOrNull { it.name == name }

    fun evaluate(session: AtlasSession, proposal: ToolCallProposal): ToolPolicyDecision {
        val definition = definition(proposal.name)
            ?: return ToolPolicyDecision(false, false, ToolRisk.EXTERNAL_EFFECT, "Tool is not registered on this device")
        if (proposal.argumentsJson.toByteArray(Charsets.UTF_8).size > definition.maxArgumentBytes.coerceIn(16_384, 1_100_000)) {
            return ToolPolicyDecision(false, false, definition.risk, "Tool arguments exceed the Atlas limit")
        }
        val arguments = runCatching { JSONObject(proposal.argumentsJson) }.getOrNull()
            ?: return ToolPolicyDecision(false, false, definition.risk, "Tool arguments are not valid JSON")
        val turnId = executionLease()?.let { ExecutionTaskStore(database).currentTurn(it.taskId) } ?: database.loadActiveTurn(session.id)?.id
        if (turnId != null && discovery.active(turnId) && !discoveryAllowed(proposal.name, turnId))
            return ToolPolicyDecision(false, false, definition.risk, "Capability discovery is read-only; a separate explicit authorization path is required")
        if (proposal.name == CapabilityDiscovery.TOOL) {
            val error = runCatching { CapabilityDiscovery.validate(arguments) }.exceptionOrNull()
            return ToolPolicyDecision(error == null, false, ToolRisk.SESSION_WRITE, error?.message ?: "Audit-only capability discovery control; no external access granted")
        }
        additionalTools.firstOrNull { it.definition.name == proposal.name }?.let {
            val decision = it.evaluate(if (it.definition.name.startsWith("workspace_")) ExecutionTaskStore(database).workspaceSession(session, turnId) else session, proposal)
            if (turnId != null && discovery.active(turnId) && (decision.requiresConfirmation || (decision.risk != ToolRisk.READ_ONLY && !(discovery.localWorkResumed(turnId) && it.supportsLocalMutation && decision.risk == ToolRisk.SESSION_WRITE))))
                return ToolPolicyDecision(false, false, decision.risk, "Discovery cannot authorize additional research permissions")
            return decision
        }
        return when (proposal.name) {
            "atlas_task_handoff_pixels" -> ToolPolicyDecision(true,false,ToolRisk.SESSION_WRITE,"Task-local visual verification handoff; execution and consent gates unchanged")
            CLARIFICATION_TOOL -> ToolPolicyDecision(true, false, ToolRisk.SESSION_WRITE, "Core-owned conversational control")
            "capture_current_view" -> ToolPolicyDecision(
                allowed = session.permissions.observe && session.permissions.captureImage != com.grinningfrog.atlas.model.PermissionPolicy.NEVER,
                requiresConfirmation = false,
                risk = ToolRisk.READ_ONLY,
                reason = "Current-view capture is governed by the active session camera policy",
            )
            "get_device_state" -> ToolPolicyDecision(true, false, ToolRisk.READ_ONLY, "Read-only local telemetry")
            "atlas_remember" -> {
                val targetId = arguments.optString("memory_id").takeIf(String::isNotBlank)
                val durable = arguments.optString("kind").equals("durable", ignoreCase = true) ||
                    targetId?.let { database.accessibleMemoryKind(it, session.id) == MemoryKind.DURABLE } == true
                ToolPolicyDecision(
                    allowed = true,
                    requiresConfirmation = durable,
                    risk = if (durable) ToolRisk.PERSONAL_DATA else ToolRisk.SESSION_WRITE,
                    reason = if (durable) "Durable user memory requires explicit consent" else "Session-scoped memory is reversible and visible",
                )
            }
            else -> ToolPolicyDecision(false, false, definition.risk, "No executor is installed")
        }
    }

    private fun discoveryAllowed(name: String, turnId: String): Boolean = name in setOf(CapabilityDiscovery.TOOL, CLARIFICATION_TOOL) ||
        additionalTools.any { it.definition.name == name && (it.supportsCapabilityDiscovery || it.supportsLocalDiagnostics) && it.definition.risk == ToolRisk.READ_ONLY } ||
        (discovery.localWorkResumed(turnId) && additionalTools.any { it.definition.name == name && it.supportsLocalMutation && it.definition.risk == ToolRisk.SESSION_WRITE })

    suspend fun execute(call: AtlasToolCall): ToolExecutionResult {
        val executionStore=ExecutionTaskStore(database)
        val history=executionStore.forTurn(call.turnId)?.let {executionStore.calls(it.id)} ?: database.loadTurnToolCalls(call.turnId)
        require(!ToolRetryPolicy.identicalDeterministicFailure(call,history)) { "IDENTICAL_RETRY_SUPPRESSED: Correct the arguments or report the blocker; identical deterministic failures persist across slices." }
        val arguments = JSONObject(call.argumentsJson)
        val researching = discovery.active(call.turnId)
        // Recheck at execution: a preceding call in this same batch may have declared a blocker.
        if (researching && !discoveryAllowed(call.name, call.turnId)) {
            database.appendEvent(call.sessionId, "capability.discovery_denied", JSONObject().put("turnId", call.turnId).put("tool", call.name).put("reason", "Discovery is read-only; no acquisition or invocation authorized"))
            error("Capability discovery is read-only; a separate explicit authorization path is required")
        }
        if (call.name == CapabilityDiscovery.TOOL) return discovery.record(call, arguments)
        val adapter = additionalTools.firstOrNull { it.definition.name == call.name }
        if (researching && adapter?.supportsLocalMutation == true && adapter.definition.risk == ToolRisk.SESSION_WRITE) {
            val session = requireNotNull(database.loadSession(call.sessionId)) { "Session is unavailable" }
            val policy = adapter.evaluate(if (call.name.startsWith("workspace_")) ExecutionTaskStore(database).workspaceSession(session, call.turnId) else session, ToolCallProposal(call.id, call.name, call.argumentsJson))
            require(policy.allowed && policy.risk == ToolRisk.SESSION_WRITE && !policy.requiresConfirmation) { "Local mutation is unavailable under its current policy" }
        }
        val trustedResearch = adapter?.supportsCapabilityDiscovery == true && adapter.definition.risk == ToolRisk.READ_ONLY
        if (trustedResearch) {
            val researchAdapter = requireNotNull(adapter)
            val session = requireNotNull(database.loadSession(call.sessionId)) { "Session is unavailable" }
            val policy = researchAdapter.evaluate(session, ToolCallProposal(call.id, call.name, call.argumentsJson))
            require(policy.allowed && policy.risk == ToolRisk.READ_ONLY && !policy.requiresConfirmation) { "Discovery research is unavailable under the current permissions" }
            discovery.searchAttempt(call)
            try {
                val result = researchAdapter.execute(call)
                discovery.searchResult(call, result.resultJson)
                return result
            } catch (error: Exception) {
                discovery.searchResult(call, null, error.message?.take(500) ?: error.javaClass.simpleName)
                throw error
            }
        }
        return when (call.name) {
            CLARIFICATION_TOOL -> error("Clarification controls must be reconciled by Atlas Core")
            "capture_current_view" -> {
                val detail = arguments.optBoolean("detail", false)
                val observation = capture(arguments.optString("reason", "model-requested refresh"), if (detail) MediaPurpose.DETAIL_VISION else MediaPurpose.STANDARD_VISION)
                ToolExecutionResult(JSONObject().apply {
                    put("ok", true); put("observation_id", observation.id); put("observed_at_ms", observation.observedAtMs)
                    put("available_at_ms", observation.availableAtMs); put("age_ms", (now() - observation.observedAtMs).coerceAtLeast(0))
                    put("width", observation.media.width); put("height", observation.media.height); put("bytes", observation.media.byteSize)
                }.toString(), observation)
            }
            "get_device_state" -> {
                val health = deviceHealth()
                ToolExecutionResult(JSONObject().apply {
                    put("ok", true); put("battery_percent", health.batteryPercent); put("charging", health.charging)
                    put("thermal_status", health.thermalStatus); put("network", health.network); put("motion", health.motion.name.lowercase())
                    put("observed_at_ms", now())
                }.toString())
            }
            "atlas_task_handoff_pixels" -> {
                require(arguments.keys().asSequence().toSet()==setOf("user_quote"))
                val store=ExecutionTaskStore(database)
                store.localOperation(call,executionLease()) {ToolExecutionResult(store.handOffPixels(call,arguments.getString("user_quote")).toString())}
            }
            "atlas_remember" -> ExecutionTaskStore(database).localOperation(call, executionLease()) { executeMemory(call, arguments) }
            else -> additionalTools.firstOrNull { it.definition.name == call.name }?.let { adapter ->
                if (adapter is AtlasLocalMutationAdapter) ExecutionTaskStore(database).localOperation(call, executionLease()) { adapter.executeLocal(call) } else adapter.execute(call)
            }
                ?: error("Tool ${call.name} is not registered")
        }
    }

    private fun executeMemory(call: AtlasToolCall, arguments: JSONObject): ToolExecutionResult {
        return when (arguments.getString("action")) {
            "forget" -> {
                val memoryId = arguments.getString("memory_id")
                require(database.memoryIsAccessible(memoryId, call.sessionId)) { "Memory is not accessible from this session" }
                database.forgetMemory(memoryId, now(), call.sessionId)
                ToolExecutionResult(JSONObject().put("ok", true).put("forgotten_memory_id", memoryId).toString())
            }
            "remember" -> {
                val content = arguments.getString("content").trim().take(1_000)
                require(content.isNotBlank()) { "Memory content cannot be blank" }
                val kind = MemoryKind.valueOf(arguments.getString("kind").uppercase())
                val timestamp = now()
                val requestedId = arguments.optString("memory_id").takeIf(String::isNotBlank)
                require(requestedId == null || database.memoryIsAccessible(requestedId, call.sessionId)) { "Memory update is not accessible from this session" }
                val evidenceId = arguments.optString("evidence_observation_id").takeIf(String::isNotBlank)
                    ?: if (kind == MemoryKind.ENVIRONMENT) database.loadLatestObservation(call.sessionId)?.id else null
                require(kind != MemoryKind.ENVIRONMENT || evidenceId != null) { "Environment memory requires observation evidence" }
                require(evidenceId == null || database.observationBelongsToSession(evidenceId, call.sessionId)) {
                    "Memory evidence is not an observation from this session"
                }
                val expiresAt = if (kind == MemoryKind.ENVIRONMENT) {
                    timestamp + arguments.optLong("ttl_seconds", 300L).coerceIn(30L, 86_400L) * 1_000L
                } else null
                val scope = when (kind) {
                    MemoryKind.WORKING -> MemoryScope.SESSION
                    MemoryKind.TASK -> if (call.sessionId.let { database.loadSession(it)?.taskRunId } != null) MemoryScope.TASK else MemoryScope.SESSION
                    MemoryKind.ENVIRONMENT -> MemoryScope.ENVIRONMENT
                    MemoryKind.DURABLE -> MemoryScope.WORKSPACE
                }
                val session = database.loadSession(call.sessionId) ?: error("Session is unavailable")
                val scopeId = when (scope) {
                    MemoryScope.SESSION -> session.id
                    MemoryScope.TASK -> session.taskRunId ?: session.id
                    MemoryScope.PRINCIPAL -> session.actorId ?: session.workspaceId
                    MemoryScope.WORKSPACE -> session.workspaceId
                    MemoryScope.ENVIRONMENT -> session.stationId ?: session.siteId ?: session.id
                }
                val item = MemoryItem(
                    id = requestedId ?: UUID.randomUUID().toString(),
                    sessionId = call.sessionId,
                    kind = kind,
                    content = content,
                    status = MemoryStatus.ACTIVE,
                    confidence = arguments.optDouble("confidence", 1.0).coerceIn(0.0, 1.0),
                    sourceTurnId = call.turnId,
                    evidenceObservationId = evidenceId,
                    createdAtMs = timestamp,
                    updatedAtMs = timestamp,
                    expiresAtMs = expiresAt,
                    workspaceId = session.workspaceId,
                    scope = scope,
                    scopeId = scopeId,
                )
                database.saveMemory(item)
                ToolExecutionResult(JSONObject().put("ok", true).put("memory_id", item.id).put("kind", item.kind.name.lowercase()).toString())
            }
            else -> error("Unsupported memory action")
        }
    }

    companion object {
        const val CLARIFICATION_TOOL = "atlas_clarification"
    }
}
