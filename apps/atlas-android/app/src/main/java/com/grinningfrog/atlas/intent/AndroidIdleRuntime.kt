package com.grinningfrog.atlas.intent

import com.grinningfrog.atlas.data.SecureSettings
import com.grinningfrog.atlas.device.DeviceHealthMonitor
import com.grinningfrog.atlas.model.MotionState
import com.grinningfrog.atlas.model.DeviceHealth
import com.grinningfrog.atlas.provider.CapabilityRouter
import com.grinningfrog.atlas.data.AtlasDatabase
import com.grinningfrog.atlas.model.InferenceRequest
import com.grinningfrog.atlas.model.LatencyClass
import com.grinningfrog.atlas.model.ResponseContract
import com.grinningfrog.atlas.model.ResponseMode
import com.grinningfrog.atlas.model.RouteCapability
import com.grinningfrog.atlas.model.InferenceDomain
import com.grinningfrog.atlas.model.InferencePurpose
import com.grinningfrog.atlas.model.InferenceProvenance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

data class IdleRuntimeStatus(
    val mode: IntentAutonomyMode = IntentAutonomyMode.PERSIST_ONLY,
    val paused: Boolean = false,
    val runtimePressure: Double = 0.0,
    val cognitionMode: CognitionMode = CognitionMode.INTERACTIVE,
    val nextEvaluationAtMs: Long? = null,
    val lastDecision: WorkOpportunity = WorkOpportunity.NO_WORK,
    val lastReason: String = "not evaluated",
    val topIntents: List<RankedIntent> = emptyList(),
    val telemetry: IdleTelemetry = IdleTelemetry(),
    val budgetRemaining: BudgetUsage = BudgetUsage(),
)

class AndroidIdleRuntime(
    private val store: IntentStore,
    private val settings: SecureSettings,
    private val health: DeviceHealthMonitor,
    private val scope: CoroutineScope,
    handlers: List<IdleWorkHandler> = listOf(IntentMetadataMaintenanceHandler()),
) {
    private val coordinator = IdleRuntimeCoordinator(store, settings::intentAutonomyMode, settings::idleWorkBudget, handlers = handlers)
    private val mutableStatus = MutableStateFlow(IdleRuntimeStatus(
        mode = settings.intentAutonomyMode, paused = settings.idleRuntimePaused, telemetry = store.loadTelemetry(),
        budgetRemaining = remainingBudget(),
    ))
    val status: StateFlow<IdleRuntimeStatus> = mutableStatus.asStateFlow()
    private var scheduler: Job? = null
    @Volatile private var lastUserInteractionAtMs = System.currentTimeMillis()
    @Volatile private var lastEnvironmentSignature: String? = null

    fun start() {
        if (settings.idleRuntimePaused) coordinator.pause()
        if (scheduler?.isActive == true) return
        if (settings.intentAutonomyMode == IntentAutonomyMode.OFF || settings.idleRuntimePaused) return
        scheduler = scope.launch {
            if (settings.intentAutonomyMode == IntentAutonomyMode.PERSIST_ONLY) {
                evaluateAndMaybeWork()
                return@launch
            }
            while (isActive) {
                val decision = runCatching { evaluateAndMaybeWork() }.getOrElse { error ->
                    IdleDecision(WorkOpportunity.NO_WORK, 0.0, reason = "idle runtime failure: ${error.message}", nextEvaluationAtMs = System.currentTimeMillis() + 60 * 60_000L)
                }
                val target = decision.nextEvaluationAtMs ?: (System.currentTimeMillis() + 30 * 60_000L)
                delay((target - System.currentTimeMillis()).coerceAtLeast(1_000L))
            }
        }
    }

    fun close() { coordinator.preempt("runtime closing"); scheduler?.cancel(); scheduler = null }

    fun onUserInteraction() {
        lastUserInteractionAtMs = System.currentTimeMillis()
        coordinator.preempt("direct user interaction")
        restartScheduler()
    }

    fun setMode(mode: IntentAutonomyMode) {
        settings.intentAutonomyMode = mode
        coordinator.onModeChanged()
        mutableStatus.value = mutableStatus.value.copy(mode = mode)
        if (mode == IntentAutonomyMode.OFF) { scheduler?.cancel(); scheduler = null }
        else restartScheduler()
    }

    fun pause() { settings.idleRuntimePaused = true; coordinator.pause(); mutableStatus.value = mutableStatus.value.copy(paused = true) }
    fun resume() { settings.idleRuntimePaused = false; coordinator.resume(); mutableStatus.value = mutableStatus.value.copy(paused = false); restartScheduler() }
    fun forceEvaluation() { restartScheduler() }

    fun changeIntentState(intentId: String, target: IntentState) {
        val intent = store.loadNonTerminal().firstOrNull { it.id == intentId } ?: return
        if (!IntentStateMachine.canTransition(intent.state, target)) return
        val now = System.currentTimeMillis()
        val updated = IntentStateMachine.transition(intent, target, now, "developer action")
        store.save(updated)
        store.recordTransition(intent.id, intent.state, target, "developer action", now)
        store.updateTelemetry { it.copy(intentStateTransitions = it.intentStateTransitions + 1) }
        restartScheduler()
    }

    fun observeUserText(text: String) {
        if (settings.intentAutonomyMode == IntentAutonomyMode.OFF) return
        val normalized = text.trim()
        val strongPromise = Regex("(?i)\\b(remember to|don't forget|do not forget|we should|later we|come back to|revisit)\\b").containsMatchIn(normalized)
        if (!strongPromise) return
        val now = System.currentTimeMillis()
        val candidate = IntentRecord(
            type = IntentType.PROMISE, subject = normalized.take(240), description = "Explicit unresolved commitment from user interaction",
            origin = IntentOrigin.USER, createdAtMs = now, lastUpdatedAtMs = now,
            importance = .75, userRelevance = .95, confidence = .90, nextAction = "revisit_with_user",
        )
        val existing = store.loadNonTerminal()
        val duplicate = IntentDeduplicator.findMatch(candidate, existing)
        store.save(if (duplicate == null) candidate else duplicate.copy(lastUpdatedAtMs = now, confidence = maxOf(duplicate.confidence, candidate.confidence), userRelevance = maxOf(duplicate.userRelevance, candidate.userRelevance)))
        restartScheduler()
    }

    fun observeEnvironment(device: DeviceHealth) {
        val signature = "${device.charging}:${device.network}:${device.motion}"
        if (signature == lastEnvironmentSignature) return
        lastEnvironmentSignature = signature
        store.updateTelemetry { it.copy(environmentalEvents = it.environmentalEvents + 1) }
        if (settings.intentAutonomyMode != IntentAutonomyMode.OFF) restartScheduler()
    }

    fun observeRepeatedFailure(subject: String, description: String, evidenceCount: Int) {
        if (settings.intentAutonomyMode == IntentAutonomyMode.OFF || evidenceCount < 2) return
        val now = System.currentTimeMillis()
        val candidate = IntentRecord(
            type = IntentType.FAILURE, subject = subject.take(240), description = description.take(1_000),
            origin = IntentOrigin.TOOL, createdAtMs = now, lastUpdatedAtMs = now,
            importance = .65, userRelevance = .65, confidence = (.55 + evidenceCount * .1).coerceAtMost(.95),
            nextAction = "inspect_failure_evidence", metadata = mapOf("evidenceCount" to evidenceCount.toString()),
        )
        val duplicate = IntentDeduplicator.findMatch(candidate, store.loadNonTerminal())
        store.save(if (duplicate == null) candidate else duplicate.copy(
            lastUpdatedAtMs = now, confidence = maxOf(duplicate.confidence, candidate.confidence),
            metadata = duplicate.metadata + ("evidenceCount" to evidenceCount.toString()),
        ))
        restartScheduler()
    }

    private fun restartScheduler() { scheduler?.cancel(); scheduler = null; start() }

    private suspend fun evaluateAndMaybeWork(): IdleDecision {
        val environment = environment()
        val decision = coordinator.evaluate(environment)
        mutableStatus.value = IdleRuntimeStatus(
            mode = settings.intentAutonomyMode, paused = settings.idleRuntimePaused,
            runtimePressure = decision.runtimePressure, cognitionMode = environment.cognitionMode, nextEvaluationAtMs = decision.nextEvaluationAtMs,
            lastDecision = decision.opportunity, lastReason = decision.reason, topIntents = decision.ranked,
            telemetry = store.loadTelemetry(),
            budgetRemaining = remainingBudget(),
        )
        if (environment.userEngagement in setOf(UserEngagement.DISENGAGED, UserEngagement.LONG_ABSENCE)) {
            try { coordinator.execute(decision, environment) } catch (_: CancellationException) { }
            mutableStatus.value = mutableStatus.value.copy(telemetry = store.loadTelemetry(), budgetRemaining = remainingBudget())
        }
        return decision
    }

    private fun environment(): RuntimeEnvironment {
        val now = System.currentTimeMillis()
        val idleFor = now - lastUserInteractionAtMs
        val device = health.snapshot()
        val engagement = when {
            idleFor < 2 * 60_000L -> UserEngagement.RECENT
            idleFor < 30 * 60_000L -> UserEngagement.DISENGAGED
            else -> UserEngagement.LONG_ABSENCE
        }
        val connectivity = when (device.network) {
            "wifi" -> ConnectivityState.WIFI
            "ethernet" -> ConnectivityState.TRUSTED_LAN
            "cellular" -> ConnectivityState.METERED
            else -> ConnectivityState.OFFLINE
        }
        val power = when {
            device.charging && device.batteryPercent == 100 -> PowerState.FULL
            device.charging -> PowerState.CHARGING
            else -> PowerState.BATTERY
        }
        return RuntimeEnvironment(
            userEngagement = engagement,
            mobility = if (device.motion in setOf(MotionState.WALKING, MotionState.VEHICLE)) MobilityState.MOVING else if (device.motion == MotionState.UNKNOWN) MobilityState.UNKNOWN else MobilityState.STATIONARY,
            power = power, connectivity = connectivity,
            cognitionMode = if (power != PowerState.BATTERY && connectivity in setOf(ConnectivityState.WIFI, ConnectivityState.TRUSTED_LAN)) CognitionMode.WORKSHOP else CognitionMode.IDLE,
            capabilities = setOf("local_database", "event_log"), authorities = setOf("read_local_state", "write_intent_metadata"), nowMs = now,
        )
    }

    private fun remainingBudget(): BudgetUsage = IdleBudgetLedger(
        settings.idleWorkBudget(), store.loadBudgetUsage(budgetDay(System.currentTimeMillis()))
    ).remaining()
}

class IntentMetadataMaintenanceHandler : IdleWorkHandler {
    override val actionClass = "maintain_intent_metadata"
    override val requiresInference = InferenceLocation.NONE
    override val requiredAuthorities = setOf("write_intent_metadata")
    override fun canHandle(intent: IntentRecord) = intent.type == IntentType.MAINTENANCE && intent.nextAction == "refresh_intent_metadata"
    override suspend fun execute(intent: IntentRecord, contract: WorkSliceContract) = WorkResult(
        outcome = WorkResult.Outcome.COMPLETED,
        summary = "Deterministic intent metadata maintenance completed",
        informationGain = .2,
        progressRate = 1.0,
        completionVerified = true,
    )
}

class IntentInferenceWorkHandler(
    override val requiresInference: InferenceLocation,
    private val database: AtlasDatabase,
    private val router: CapabilityRouter,
) : IdleWorkHandler {
    init { require(requiresInference != InferenceLocation.NONE) }
    override val actionClass = "analyze_intent_${requiresInference.name.lowercase()}"
    override val requiredAuthorities = emptySet<String>()
    override fun canHandle(intent: IntentRecord) = intent.metadata["cognitiveWork"] == "true"

    override suspend fun execute(intent: IntentRecord, contract: WorkSliceContract): WorkResult {
        val session = database.loadLatestSession() ?: return WorkResult(WorkResult.Outcome.WAITING_EVENT, "No active Atlas session provides an inference context")
        val request = InferenceRequest(
            sessionId = session.id, workspaceId = session.workspaceId, taskRunId = session.taskRunId,
            capability = RouteCapability.REASONING,
            systemPrompt = "You are a bounded capability inside Atlas Core. Analyze only the supplied unresolved intent. Do not grant permissions, execute actions, or claim completion. Return a concise assessment and one proposed next step.",
            userText = buildString {
                append("Intent type: ${intent.type}\nSubject: ${intent.subject}\n")
                intent.description?.let { append("Evidence: $it\n") }
                append("Known attempts: ${intent.attemptCount}\nAllowed external effects: ${contract.externalEffects}")
            },
            latencyClass = LatencyClass.BACKGROUND,
            responseContract = ResponseContract(ResponseMode.EXPLANATION, 100, 180, 6, contract.maxModelTokens.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()),
            provenance = InferenceProvenance(
                domain = InferenceDomain.INTENT,
                purpose = InferencePurpose.INTENT_WORK_SLICE,
                intentId = intent.id,
                workAttemptId = contract.workAttemptId,
                autonomyMode = contract.autonomyMode.name,
                userInitiated = false,
            ),
        )
        val started = System.currentTimeMillis()
        database.appendEvent(session.id, "provider.requested", JSONObject()
            .put("requestId", request.requestId).put("inferenceDomain", request.provenance.domain.name)
            .put("inferencePurpose", request.provenance.purpose.name).put("intentId", intent.id)
            .put("workAttemptId", contract.workAttemptId).put("autonomyMode", contract.autonomyMode.name))
        val response = when (requiresInference) {
            InferenceLocation.LOCAL -> router.routeLocal(request)
            InferenceLocation.CLOUD -> router.routeRemote(request)
            InferenceLocation.NONE -> error("Inference handler cannot use NONE")
        }
        database.appendEvent(session.id, "provider.responded", JSONObject()
            .put("requestId", request.requestId).put("endpointId", response.endpointId)
            .put("inferenceDomain", request.provenance.domain.name).put("inferencePurpose", request.provenance.purpose.name)
            .put("intentId", intent.id).put("workAttemptId", contract.workAttemptId)
            .put("promptTokens", response.promptTokens).put("completionTokens", response.completionTokens)
            .put("latencyMs", response.latencyMs))
        return WorkResult(
            outcome = WorkResult.Outcome.PROGRESSED,
            summary = response.text.take(2_000), informationGain = .4, progressRate = .25,
            usage = BudgetUsage(
                inputTokens = (response.promptTokens ?: 0).toLong(), outputTokens = (response.completionTokens ?: 0).toLong(),
                cloudCostUsd = if (requiresInference == InferenceLocation.CLOUD) contract.maxCostUsd else 0.0,
                wallTimeMs = System.currentTimeMillis() - started,
            ),
        )
    }
}
