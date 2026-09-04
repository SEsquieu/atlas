package com.grinningfrog.atlas.runtime

import com.grinningfrog.atlas.data.AtlasDatabase
import com.grinningfrog.atlas.model.AtlasSession
import com.grinningfrog.atlas.model.AtlasToolCall
import com.grinningfrog.atlas.model.DeviceHealth
import com.grinningfrog.atlas.model.MediaPurpose
import com.grinningfrog.atlas.model.MemoryItem
import com.grinningfrog.atlas.model.MemoryKind
import com.grinningfrog.atlas.model.MemoryStatus
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
    fun evaluate(session: AtlasSession, proposal: ToolCallProposal): ToolPolicyDecision = ToolPolicyDecision(
        allowed = true,
        requiresConfirmation = definition.requiresConfirmation ||
            (definition.risk == ToolRisk.EXTERNAL_EFFECT && session.permissions.externalActionsRequireConfirmation),
        risk = definition.risk,
        reason = "Policy supplied by ${definition.name} adapter",
    )
    suspend fun execute(call: AtlasToolCall): ToolExecutionResult
}
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
) {
    private val builtInDefinitions: List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "capture_current_view",
            description = "Capture a current camera observation when existing physical context is absent, stale, or lacks needed detail.",
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
    val definitions: List<ToolDefinition> = builtInDefinitions + clarificationDefinition + additionalTools.map { it.definition }

    fun definition(name: String) = definitions.firstOrNull { it.name == name }

    fun evaluate(session: AtlasSession, proposal: ToolCallProposal): ToolPolicyDecision {
        val definition = definition(proposal.name)
            ?: return ToolPolicyDecision(false, false, ToolRisk.EXTERNAL_EFFECT, "Tool is not registered on this device")
        if (proposal.argumentsJson.toByteArray().size > 16_384) {
            return ToolPolicyDecision(false, false, definition.risk, "Tool arguments exceed the Atlas limit")
        }
        val arguments = runCatching { JSONObject(proposal.argumentsJson) }.getOrNull()
            ?: return ToolPolicyDecision(false, false, definition.risk, "Tool arguments are not valid JSON")
        additionalTools.firstOrNull { it.definition.name == proposal.name }?.let { return it.evaluate(session, proposal) }
        return when (proposal.name) {
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

    suspend fun execute(call: AtlasToolCall): ToolExecutionResult {
        val arguments = JSONObject(call.argumentsJson)
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
            "atlas_remember" -> executeMemory(call, arguments)
            else -> additionalTools.firstOrNull { it.definition.name == call.name }?.execute(call)
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
