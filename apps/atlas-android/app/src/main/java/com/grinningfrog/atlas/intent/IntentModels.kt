package com.grinningfrog.atlas.intent

import java.util.UUID

enum class IntentAutonomyMode { OFF, PERSIST_ONLY, DETERMINISTIC, LOCAL, BUDGETED }
enum class IntentType { TASK, QUESTION, CAPABILITY_GAP, OBSERVATION, MAINTENANCE, PROMISE, DEPENDENCY, CURIOSITY, FAILURE, OPTIMIZATION }
enum class IntentState { NEW, ELIGIBLE, ACTIVE, BLOCKED, DORMANT, WAITING_USER, WAITING_EVENT, RESOLVED, ABANDONED, SUPERSEDED, FAILED }
enum class IntentOrigin { USER, TOOL, RUNTIME, HEARTBEAT, CAPABILITY, MEMORY, MODEL, SYSTEM }
enum class UserEngagement { ACTIVE, RECENT, DISENGAGED, LONG_ABSENCE }
enum class MobilityState { MOVING, STATIONARY, UNKNOWN }
enum class PowerState { BATTERY, CHARGING, FULL }
enum class ConnectivityState { OFFLINE, METERED, WIFI, TRUSTED_LAN }
enum class CognitionMode { INTERACTIVE, WARM_IDLE, IDLE, WORKSHOP, MAINTENANCE }
enum class EnvironmentFreshness { FRESH, AGING, STALE, UNKNOWN }
enum class WorkOpportunity { NO_WORK, DETERMINISTIC_WORK, TOOL_WORK, COGNITIVE_WORK, WAIT_FOR_EVENT, WAIT_FOR_USER, SURFACE_TO_USER }
enum class InferenceLocation { NONE, LOCAL, CLOUD }

data class EnvironmentalAffinity(
    val locations: Set<String> = emptySet(),
    val objects: Set<String> = emptySet(),
    val capabilities: Set<String> = emptySet(),
    val networkContexts: Set<String> = emptySet(),
    val projects: Set<String> = emptySet(),
    val requiresCharging: Boolean = false,
)

data class RuntimeEnvironment(
    val userEngagement: UserEngagement = UserEngagement.ACTIVE,
    val mobility: MobilityState = MobilityState.UNKNOWN,
    val power: PowerState = PowerState.BATTERY,
    val connectivity: ConnectivityState = ConnectivityState.OFFLINE,
    val cognitionMode: CognitionMode = CognitionMode.INTERACTIVE,
    val freshness: EnvironmentFreshness = EnvironmentFreshness.UNKNOWN,
    val locations: Set<String> = emptySet(),
    val visibleObjects: Set<String> = emptySet(),
    val capabilities: Set<String> = emptySet(),
    val authorities: Set<String> = emptySet(),
    val activeProjects: Set<String> = emptySet(),
    val nowMs: Long = System.currentTimeMillis(),
)

data class IntentRecord(
    val id: String = UUID.randomUUID().toString(),
    val type: IntentType,
    val subject: String,
    val description: String? = null,
    val origin: IntentOrigin,
    val createdAtMs: Long,
    val lastUpdatedAtMs: Long,
    val lastEvaluatedAtMs: Long? = null,
    val lastWorkedAtMs: Long? = null,
    val state: IntentState = IntentState.NEW,
    val importance: Double,
    val userRelevance: Double,
    val confidence: Double,
    val environmentalAffinity: EnvironmentalAffinity? = null,
    val requiredCapabilities: Set<String> = emptySet(),
    val requiredAuthorities: Set<String> = emptySet(),
    val estimatedCost: Double? = null,
    val estimatedRisk: Double? = null,
    val attemptCount: Int = 0,
    val identicalFailureCount: Int = 0,
    val successfulStepCount: Int = 0,
    val informationGain: Double? = null,
    val progressRate: Double? = null,
    val blockedReason: String? = null,
    val nextAction: String? = null,
    val parentIntentId: String? = null,
    val supersedesIntentId: String? = null,
    val cooldownUntilMs: Long? = null,
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        listOf(importance, userRelevance, confidence).forEach { require(it in 0.0..1.0) }
        estimatedCost?.let { require(it in 0.0..1.0) }
        estimatedRisk?.let { require(it in 0.0..1.0) }
        informationGain?.let { require(it in 0.0..1.0) }
        progressRate?.let { require(it in 0.0..1.0) }
    }
}

data class IntentScore(val intentId: String, val pressure: Double, val components: Map<String, Double>)
data class Eligibility(val eligible: Boolean, val targetState: IntentState, val reason: String? = null, val actionability: Double = 0.0)

data class WorkBudget(
    val dailyInputTokens: Long = 10_000,
    val dailyOutputTokens: Long = 5_000,
    val dailyCloudCostUsd: Double = .05,
    val maxModelTokensPerSlice: Long = 2_000,
    val maxDurationMs: Long = 90_000,
    val maxToolCalls: Int = 6,
    val maxConsecutiveSlices: Int = 3,
)

data class BudgetUsage(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cloudCostUsd: Double = 0.0,
    val wallTimeMs: Long = 0,
    val toolCalls: Int = 0,
)

data class WorkSliceContract(
    val workAttemptId: String = UUID.randomUUID().toString(),
    val intentId: String,
    val autonomyMode: IntentAutonomyMode,
    val allowedActionClasses: Set<String>,
    val allowedTools: Set<String>,
    val maxDurationMs: Long,
    val maxModelTokens: Long,
    val maxCostUsd: Double,
    val maxToolCalls: Int,
    val networkScope: String = "none",
    val filesystemScope: String = "none",
    val externalEffects: Boolean = false,
    val userInterruptible: Boolean = true,
)

data class RankedIntent(val intent: IntentRecord, val eligibility: Eligibility, val score: IntentScore)

data class IdleDecision(
    val opportunity: WorkOpportunity,
    val runtimePressure: Double,
    val selected: RankedIntent? = null,
    val ranked: List<RankedIntent> = emptyList(),
    val reason: String,
    val nextEvaluationAtMs: Long? = null,
    val workContract: WorkSliceContract? = null,
)

data class IdleTelemetry(
    val runtimeEvaluations: Long = 0,
    val intentStateTransitions: Long = 0,
    val environmentalEvents: Long = 0,
    val intentsReranked: Long = 0,
    val deterministicWorkUnits: Long = 0,
    val toolWorkUnits: Long = 0,
    val localModelCalls: Long = 0,
    val cloudModelCalls: Long = 0,
    val idleInputTokens: Long = 0,
    val idleOutputTokens: Long = 0,
    val idleCloudCostUsd: Double = 0.0,
    val idleWallTimeMs: Long = 0,
)

data class IdleEvaluationRecord(
    val id: String = UUID.randomUUID().toString(),
    val atMs: Long,
    val autonomyMode: IntentAutonomyMode,
    val environment: RuntimeEnvironment,
    val candidateCount: Int,
    val eligibleCount: Int,
    val ranked: List<IntentScore>,
    val decision: WorkOpportunity,
    val selectedIntentId: String?,
    val reason: String,
    val nextEvaluationAtMs: Long?,
    val budgetUsage: BudgetUsage = BudgetUsage(),
)

data class IdleWorkAttemptRecord(
    val id: String = UUID.randomUUID().toString(),
    val intentId: String,
    val startedAtMs: Long,
    val completedAtMs: Long,
    val autonomyMode: IntentAutonomyMode,
    val inferenceLocation: InferenceLocation,
    val contract: WorkSliceContract,
    val result: WorkResult,
)
