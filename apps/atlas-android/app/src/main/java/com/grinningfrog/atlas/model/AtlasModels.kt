package com.grinningfrog.atlas.model

import java.util.UUID

enum class SessionStatus { IDLE, ACTIVE, PAUSED, DONE, ERROR }
enum class RuntimePhase { STOPPED, STARTING, READY, LISTENING, TRANSCRIBING, CAPTURING, THINKING, SPEAKING, DEGRADED, ERROR }
enum class ListeningState { INACTIVE, PREPARING, READY, HEARING, PROCESSING }
enum class ContextMode { MANUAL, LIVE }
enum class RouteCapability { FAST, VISION, REASONING, FALLBACK }
enum class InferenceRisk { NORMAL, ELEVATED, SAFETY_CRITICAL }
enum class LatencyClass { LATENCY_CRITICAL, INTERACTIVE, BACKGROUND }
enum class ContextStability { STABLE, TRANSITIONING, UNKNOWN }
enum class MotionState { STATIONARY, HANDHELD_STABLE, TURNING, WALKING, VEHICLE, UNKNOWN }
enum class PermissionPolicy { NEVER, USER_REQUEST, ACTIVE_SESSION }
enum class MediaPurpose { HEARTBEAT, STANDARD_VISION, DETAIL_VISION }
enum class MessageRole { USER, ASSISTANT, TOOL }
enum class MessageKind { DIALOGUE, TOOL_RESULT, INTERNAL }
enum class TurnStatus {
    CREATED, ASSEMBLING_CONTEXT, WAITING_FOR_MODEL, WAITING_FOR_CONFIRMATION,
    EXECUTING_TOOL, COMPLETED, FAILED, CANCELLED, INTERRUPTED,
}
enum class ToolCallStatus { PROPOSED, WAITING_FOR_CONFIRMATION, APPROVED, RUNNING, COMPLETED, REJECTED, FAILED, UNKNOWN }
enum class ToolRisk { READ_ONLY, SESSION_WRITE, PERSONAL_DATA, EXTERNAL_EFFECT }
enum class MemoryKind { WORKING, TASK, ENVIRONMENT, DURABLE }
enum class MemoryStatus { ACTIVE, SUPERSEDED, FORGOTTEN }
enum class DeliveryStatus { NOT_APPLICABLE, PENDING, DELIVERED, INTERRUPTED, TEXT_ONLY, FAILED }
enum class SpeechSegmentStatus { QUEUED, STARTED, COMPLETED, INTERRUPTED, SKIPPED, FAILED }
enum class ResponseMode { IMMEDIATE, DEFAULT, PHYSICAL_GUIDANCE, SAFETY, EXPLANATION }
enum class WorkspaceKind { PERSONAL, ORGANIZATION }
enum class PrincipalKind { USER, SERVICE, DEVICE }
enum class TaskRunStatus { PENDING, ACTIVE, BLOCKED, COMPLETED, CANCELLED }
enum class MemoryScope { SESSION, TASK, PRINCIPAL, WORKSPACE, ENVIRONMENT }

const val DEFAULT_PERSONAL_WORKSPACE_ID = "workspace:personal:local"

data class AtlasWorkspace(val id: String, val kind: WorkspaceKind, val name: String, val organizationId: String? = null)

data class AtlasTaskRun(
    val id: String,
    val workspaceId: String,
    val status: TaskRunStatus,
    val goal: String,
    val procedureId: String? = null,
    val procedureRevisionId: String? = null,
    val externalRef: String? = null,
    val currentStepId: String? = null,
    val startedAtMs: Long? = null,
    val completedAtMs: Long? = null,
)

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
    val contextMode: ContextMode = ContextMode.MANUAL,
    val workspaceId: String = DEFAULT_PERSONAL_WORKSPACE_ID,
    val actorId: String? = null,
    val siteId: String? = null,
    val stationId: String? = null,
    val taskRunId: String? = null,
    val policyId: String? = null,
    val policyRevision: Int? = null,
)

data class ObservationTiming(
    val totalMs: Long,
    val captureMs: Long,
    val processingMs: Long,
    val providerMs: Long? = null,
)

data class MediaRef(
    val id: String,
    val storageKey: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val byteSize: Long,
    val sha256: String,
    val purpose: MediaPurpose,
    val rawByteSize: Long,
    val processingMs: Long,
)

data class InferenceImage(
    val mediaId: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val sha256: String,
    val bytes: ByteArray,
)

data class AtlasMessage(
    val sequence: Long = 0,
    val id: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val turnId: String,
    val role: MessageRole,
    val content: String,
    val createdAtMs: Long,
    val kind: MessageKind = MessageKind.DIALOGUE,
    val toolCallId: String? = null,
    val toolCallsJson: String? = null,
    val deliveryStatus: DeliveryStatus = DeliveryStatus.NOT_APPLICABLE,
    val deliveredContent: String? = null,
    val interruptedSentence: String? = null,
)

data class SpeechSegment(
    val id: String = UUID.randomUUID().toString(),
    val messageId: String,
    val sessionId: String,
    val turnId: String,
    val sentenceIndex: Int,
    val text: String,
    val status: SpeechSegmentStatus,
    val queuedAtMs: Long,
    val startedAtMs: Long? = null,
    val completedAtMs: Long? = null,
)

data class ResponseContract(
    val mode: ResponseMode,
    val targetWords: Int,
    val hardMaxWords: Int,
    val maxSentences: Int,
    val maxOutputTokens: Int,
    val actionFirst: Boolean = false,
)

data class AgentTurn(
    val id: String,
    val sessionId: String,
    val status: TurnStatus,
    val trigger: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val stepCount: Int = 0,
    val error: String? = null,
)

data class ToolDefinition(
    val name: String,
    val description: String,
    val parametersJson: String,
    val risk: ToolRisk,
    val requiresConfirmation: Boolean = false,
    val maxCallsPerTurn: Int = 2,
)

data class ToolCallProposal(
    val id: String,
    val name: String,
    val argumentsJson: String,
    val reason: String? = null,
)

data class AtlasToolCall(
    val id: String,
    val sessionId: String,
    val turnId: String,
    val name: String,
    val argumentsJson: String,
    val status: ToolCallStatus,
    val risk: ToolRisk,
    val requiresConfirmation: Boolean,
    val idempotencyKey: String,
    val reason: String? = null,
    val resultJson: String? = null,
    val error: String? = null,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

data class MemoryItem(
    val id: String,
    val sessionId: String,
    val kind: MemoryKind,
    val content: String,
    val status: MemoryStatus,
    val confidence: Double,
    val sourceTurnId: String?,
    val evidenceObservationId: String? = null,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val expiresAtMs: Long? = null,
    val workspaceId: String = DEFAULT_PERSONAL_WORKSPACE_ID,
    val scope: MemoryScope = MemoryScope.SESSION,
    val scopeId: String = sessionId,
)

data class SessionSummary(
    val sessionId: String,
    val summary: String,
    val throughMessageSequence: Long,
    val updatedAtMs: Long,
)

data class InferenceMessage(
    val role: MessageRole,
    val content: String,
    val toolCallId: String? = null,
    val toolCalls: List<ToolCallProposal> = emptyList(),
)

data class VisualObservation(
    val id: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val media: MediaRef,
    val observedAtMs: Long,
    val availableAtMs: Long,
    val timing: ObservationTiming,
    val confidence: Double? = null,
    val stability: ContextStability = ContextStability.UNKNOWN,
    val motionState: MotionState = MotionState.UNKNOWN,
    val sceneFingerprint: String? = null,
    val summary: String? = null,
    val interpretedAtMs: Long? = null,
)

data class ProviderEndpoint(
    val id: String,
    val name: String,
    val baseUrl: String,
    val model: String,
    val apiKeyAlias: String? = null,
    val supportsVision: Boolean = false,
    val supportsTools: Boolean = false,
    val supportsStreaming: Boolean = false,
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
    val turnId: String? = null,
    val step: Int = 0,
    val messages: List<InferenceMessage> = emptyList(),
    val tools: List<ToolDefinition> = emptyList(),
    val observation: VisualObservation? = null,
    val image: InferenceImage? = null,
    val contextNote: String? = null,
    val risk: InferenceRisk = InferenceRisk.NORMAL,
    val latencyClass: LatencyClass = LatencyClass.INTERACTIVE,
    val responseContract: ResponseContract? = null,
    val workspaceId: String = DEFAULT_PERSONAL_WORKSPACE_ID,
    val taskRunId: String? = null,
)

data class InferenceResponse(
    val requestId: String,
    val endpointId: String,
    val text: String,
    val latencyMs: Long,
    val degraded: Boolean = false,
    val selectedModel: String? = null,
    val routingProfile: String? = null,
    val routingReason: String? = null,
    val routingRevision: String? = null,
    val toolCalls: List<ToolCallProposal> = emptyList(),
    val finishReason: String? = null,
    val providerContinuationId: String? = null,
    val firstTokenLatencyMs: Long? = null,
)

sealed interface InferenceStreamEvent {
    data class TextDelta(val text: String) : InferenceStreamEvent
    data class ToolCallDelta(val index: Int, val id: String?, val name: String?, val argumentsDelta: String) : InferenceStreamEvent
    data class Completed(val response: InferenceResponse) : InferenceStreamEvent
}

data class AtlasEvent(
    val sequence: Long,
    val id: String,
    val sessionId: String,
    val type: String,
    val atMs: Long,
    val dataJson: String,
    val workspaceId: String = DEFAULT_PERSONAL_WORKSPACE_ID,
    val taskRunId: String? = null,
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
    val messages: List<AtlasMessage> = emptyList(),
    val activeTurn: AgentTurn? = null,
    val pendingToolCalls: List<AtlasToolCall> = emptyList(),
    val memories: List<MemoryItem> = emptyList(),
    val sessionSummary: SessionSummary? = null,
    val listeningState: ListeningState = ListeningState.INACTIVE,
    val partialTranscript: String? = null,
    val streamingResponse: String? = null,
)
