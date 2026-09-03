package com.grinningfrog.atlas.model

import java.util.UUID

enum class SessionStatus { IDLE, ACTIVE, PAUSED, DONE, ERROR }
enum class RuntimePhase { STOPPED, STARTING, READY, LISTENING, CAPTURING, THINKING, SPEAKING, DEGRADED, ERROR }
enum class RouteCapability { FAST, VISION, REASONING, FALLBACK }
enum class ContextStability { STABLE, TRANSITIONING, UNKNOWN }
enum class MotionState { STATIONARY, HANDHELD_STABLE, TURNING, WALKING, VEHICLE, UNKNOWN }
enum class PermissionPolicy { NEVER, USER_REQUEST, ACTIVE_SESSION }

data class SessionPermissions(
    val observe: Boolean = true,
    val captureImage: PermissionPolicy = PermissionPolicy.ACTIVE_SESSION,
    val microphone: PermissionPolicy = PermissionPolicy.USER_REQUEST,
    val speakResponses: Boolean = true,
    val proactiveSpeech: Boolean = false,
    val externalActionsRequireConfirmation: Boolean = true,
)

data class AtlasSession(
    val id: String,
    val name: String,
    val goal: String,
    val status: SessionStatus,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val permissions: SessionPermissions = SessionPermissions(),
)

data class ObservationTiming(
    val totalMs: Long,
    val captureMs: Long,
    val processingMs: Long,
    val providerMs: Long? = null,
)

data class VisualObservation(
    val id: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val mediaPath: String,
    val observedAtMs: Long,
    val availableAtMs: Long,
    val timing: ObservationTiming,
    val confidence: Double? = null,
    val stability: ContextStability = ContextStability.UNKNOWN,
    val motionState: MotionState = MotionState.UNKNOWN,
    val sceneFingerprint: String? = null,
    val summary: String? = null,
)

data class ProviderEndpoint(
    val id: String,
    val name: String,
    val baseUrl: String,
    val model: String,
    val apiKeyAlias: String? = null,
    val supportsVision: Boolean = false,
    val supportsTools: Boolean = false,
    val timeoutMs: Long = 60_000,
)

data class RouteTable(
    val fast: List<String> = emptyList(),
    val vision: List<String> = emptyList(),
    val reasoning: List<String> = emptyList(),
    val fallback: List<String> = emptyList(),
) {
    fun candidates(capability: RouteCapability): List<String> = when (capability) {
        RouteCapability.FAST -> fast + fallback
        RouteCapability.VISION -> vision + fallback
        RouteCapability.REASONING -> reasoning + fallback
        RouteCapability.FALLBACK -> fallback
    }.distinct()
}

data class InferenceRequest(
    val requestId: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val capability: RouteCapability,
    val systemPrompt: String,
    val userText: String,
    val observation: VisualObservation? = null,
    val contextNote: String? = null,
)

data class InferenceResponse(
    val requestId: String,
    val endpointId: String,
    val text: String,
    val latencyMs: Long,
    val degraded: Boolean = false,
)

data class AtlasEvent(
    val sequence: Long,
    val id: String,
    val sessionId: String,
    val type: String,
    val atMs: Long,
    val dataJson: String,
)

data class DeviceHealth(
    val batteryPercent: Int? = null,
    val charging: Boolean = false,
    val thermalStatus: String = "unknown",
    val network: String = "unknown",
    val motion: MotionState = MotionState.UNKNOWN,
)

data class RuntimeSnapshot(
    val session: AtlasSession? = null,
    val phase: RuntimePhase = RuntimePhase.STOPPED,
    val latestObservation: VisualObservation? = null,
    val latestResponse: String? = null,
    val latestError: String? = null,
    val contextAgeMs: Long? = null,
    val nextHeartbeatAtMs: Long? = null,
    val deviceHealth: DeviceHealth = DeviceHealth(),
    val recentEvents: List<AtlasEvent> = emptyList(),
)
