package com.grinningfrog.atlas.intent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.random.Random

interface IntentStore {
    fun loadNonTerminal(): List<IntentRecord>
    fun save(intent: IntentRecord)
    fun recordTransition(intentId: String, from: IntentState, to: IntentState, reason: String?, atMs: Long)
    fun recordEvaluation(evaluation: IdleEvaluationRecord)
    fun recordWorkAttempt(attempt: IdleWorkAttemptRecord)
    fun loadTelemetry(): IdleTelemetry
    fun updateTelemetry(transform: (IdleTelemetry) -> IdleTelemetry)
    fun loadBudgetUsage(day: String): BudgetUsage
    fun updateBudgetUsage(day: String, transform: (BudgetUsage) -> BudgetUsage)
}

interface IdleWorkHandler {
    val actionClass: String
    val requiresInference: InferenceLocation
    val requiredAuthorities: Set<String>
    fun canHandle(intent: IntentRecord): Boolean
    suspend fun execute(intent: IntentRecord, contract: WorkSliceContract): WorkResult
}

data class WorkResult(
    val outcome: Outcome,
    val summary: String,
    val informationGain: Double = 0.0,
    val progressRate: Double = 0.0,
    val usage: BudgetUsage = BudgetUsage(),
    val completionVerified: Boolean = false,
    val failureSignature: String? = null,
) {
    enum class Outcome { PROGRESSED, COMPLETED, BLOCKED, WAITING_USER, WAITING_EVENT, FAILED, PREEMPTED }
}

class IdleBudgetLedger(private val limit: WorkBudget, private val usage: BudgetUsage = BudgetUsage()) {
    fun permits(reservation: BudgetUsage): Boolean =
        usage.inputTokens + reservation.inputTokens <= limit.dailyInputTokens &&
            usage.outputTokens + reservation.outputTokens <= limit.dailyOutputTokens &&
            usage.cloudCostUsd + reservation.cloudCostUsd <= limit.dailyCloudCostUsd + 1e-9 &&
            reservation.wallTimeMs <= limit.maxDurationMs && reservation.toolCalls <= limit.maxToolCalls

    fun remaining(): BudgetUsage = BudgetUsage(
        inputTokens = max(0, limit.dailyInputTokens - usage.inputTokens),
        outputTokens = max(0, limit.dailyOutputTokens - usage.outputTokens),
        cloudCostUsd = max(0.0, limit.dailyCloudCostUsd - usage.cloudCostUsd),
        wallTimeMs = limit.maxDurationMs,
        toolCalls = limit.maxToolCalls,
    )
}

class IdleRuntimeCoordinator(
    private val store: IntentStore,
    private val modeProvider: () -> IntentAutonomyMode,
    private val budgetProvider: () -> WorkBudget = { WorkBudget() },
    private val eligibility: IntentEligibilityEvaluator = IntentEligibilityEvaluator(),
    private val scorer: IntentScorer = IntentScorer(),
    private val handlers: List<IdleWorkHandler> = emptyList(),
    private val random: Random = Random.Default,
) {
    private val generation = AtomicLong(0)
    @Volatile private var activeJob: Job? = null
    @Volatile private var paused = false

    fun pause() { paused = true; preempt("idle runtime paused") }
    fun resume() { paused = false }
    fun preempt(reason: String = "user interaction") {
        generation.incrementAndGet()
        activeJob?.cancel(CancellationException(reason))
        activeJob = null
    }

    fun onModeChanged() = preempt("autonomy mode changed")

    fun evaluate(environment: RuntimeEnvironment): IdleDecision {
        val mode = modeProvider()
        val intents = if (mode == IntentAutonomyMode.OFF || paused) emptyList() else store.loadNonTerminal()
        val ranked = intents.map { original ->
            var intent = original
            val result = eligibility.evaluate(intent, environment, mode)
            if (result.targetState != intent.state && IntentStateMachine.canTransition(intent.state, result.targetState)) {
                transition(intent, result.targetState, environment.nowMs, result.reason ?: "eligibility changed")
                intent = IntentStateMachine.transition(intent, result.targetState, environment.nowMs, result.reason)
            }
            RankedIntent(intent, result, scorer.score(intent, result, environment))
        }.sortedByDescending { it.score.pressure }
        val eligible = ranked.filter { it.eligibility.eligible }
        val top = eligible.firstOrNull()
        val pressure = aggregatePressure(eligible, environment)
        val decision = select(mode, top, pressure)
        val next = cadence(mode, environment, pressure, top)
        val complete = decision.copy(ranked = ranked.take(10), nextEvaluationAtMs = next)
        store.updateTelemetry { it.copy(runtimeEvaluations = it.runtimeEvaluations + 1, intentsReranked = it.intentsReranked + ranked.size) }
        store.recordEvaluation(IdleEvaluationRecord(
            atMs = environment.nowMs, autonomyMode = mode, environment = environment,
            candidateCount = intents.size, eligibleCount = eligible.size, ranked = ranked.take(10).map { it.score },
            decision = complete.opportunity, selectedIntentId = complete.selected?.intent?.id,
            reason = complete.reason, nextEvaluationAtMs = next,
        ))
        return complete
    }

    suspend fun execute(decision: IdleDecision, environment: RuntimeEnvironment): WorkResult? {
        val selected = decision.selected ?: return null
        val modeAtStart = modeProvider()
        if (paused || environment.userEngagement in setOf(UserEngagement.ACTIVE, UserEngagement.RECENT)) return null
        if (decision.opportunity !in setOf(WorkOpportunity.DETERMINISTIC_WORK, WorkOpportunity.TOOL_WORK, WorkOpportunity.COGNITIVE_WORK)) return null
        val handler = handlers.firstOrNull { it.canHandle(selected.intent) && isPermitted(modeAtStart, it.requiresInference) } ?: return null
        if (!environment.authorities.containsAll(handler.requiredAuthorities)) return null
        val budget = budgetProvider()
        val contract = WorkSliceContract(
            intentId = selected.intent.id, autonomyMode = modeAtStart,
            allowedActionClasses = setOf(handler.actionClass), allowedTools = emptySet(),
            maxDurationMs = budget.maxDurationMs, maxModelTokens = minOf(budget.maxModelTokensPerSlice, budget.dailyOutputTokens),
            maxCostUsd = if (modeAtStart == IntentAutonomyMode.BUDGETED) budget.dailyCloudCostUsd else 0.0,
            maxToolCalls = budget.maxToolCalls,
        )
        if (handler.requiresInference != InferenceLocation.NONE && contract.maxModelTokens <= 0) return null
        if (handler.requiresInference == InferenceLocation.CLOUD && contract.maxCostUsd <= 0.0) return null
        val reservation = BudgetUsage(
            inputTokens = if (handler.requiresInference == InferenceLocation.NONE) 0 else contract.maxModelTokens,
            outputTokens = if (handler.requiresInference == InferenceLocation.NONE) 0 else contract.maxModelTokens,
            cloudCostUsd = if (handler.requiresInference == InferenceLocation.CLOUD) contract.maxCostUsd else 0.0,
            wallTimeMs = contract.maxDurationMs,
            toolCalls = contract.maxToolCalls,
        )
        val day = budgetDay(environment.nowMs)
        val consumed = store.loadBudgetUsage(day)
        if (!IdleBudgetLedger(budget, consumed).permits(reservation)) return null
        val startedGeneration = generation.get()
        var sliceJob: Job? = null
        transition(selected.intent, IntentState.ACTIVE, environment.nowMs, "work slice granted")
        return try {
            val result = supervisorScope {
                val worker = async { handler.execute(selected.intent, contract) }
                sliceJob = worker
                activeJob = worker
                worker.await()
            }
            if (generation.get() != startedGeneration || modeProvider() != modeAtStart) throw CancellationException("idle work invalidated")
            applyResult(selected.intent.copy(state = IntentState.ACTIVE), result, environment.nowMs)
            store.updateBudgetUsage(day) { it + result.usage }
            store.updateTelemetry { telemetryFor(it, handler.requiresInference, result.usage) }
            store.recordWorkAttempt(IdleWorkAttemptRecord(
                id = contract.workAttemptId, intentId = selected.intent.id, startedAtMs = environment.nowMs, completedAtMs = System.currentTimeMillis(),
                autonomyMode = modeAtStart, inferenceLocation = handler.requiresInference, contract = contract, result = result,
            ))
            result
        } catch (cancelled: CancellationException) {
            val result = WorkResult(WorkResult.Outcome.PREEMPTED, "Work slice preempted")
            applyResult(selected.intent.copy(state = IntentState.ACTIVE), result, environment.nowMs)
            store.recordWorkAttempt(IdleWorkAttemptRecord(
                id = contract.workAttemptId, intentId = selected.intent.id, startedAtMs = environment.nowMs, completedAtMs = System.currentTimeMillis(),
                autonomyMode = modeAtStart, inferenceLocation = handler.requiresInference, contract = contract, result = result,
            ))
            result
        } finally {
            if (activeJob === sliceJob) activeJob = null
        }
    }

    private fun select(mode: IntentAutonomyMode, top: RankedIntent?, pressure: Double): IdleDecision {
        if (paused) return IdleDecision(WorkOpportunity.NO_WORK, 0.0, reason = "runtime paused")
        if (mode == IntentAutonomyMode.OFF) return IdleDecision(WorkOpportunity.NO_WORK, 0.0, reason = "autonomy mode OFF")
        if (top == null) return IdleDecision(WorkOpportunity.NO_WORK, pressure, reason = "no actionable intents")
        if (mode == IntentAutonomyMode.PERSIST_ONLY) return IdleDecision(WorkOpportunity.NO_WORK, pressure, top, reason = "PERSIST_ONLY forbids autonomous work")
        if (pressure < .20) return IdleDecision(WorkOpportunity.NO_WORK, pressure, top, reason = "pressure below work threshold")
        val permitted = handlers.filter { it.canHandle(top.intent) && isPermitted(mode, it.requiresInference) }
        val handler = permitted.minByOrNull { it.requiresInference.ordinal }
            ?: return IdleDecision(WorkOpportunity.WAIT_FOR_EVENT, pressure, top, reason = "no mode-permitted work handler")
        val opportunity = when (handler.requiresInference) {
            InferenceLocation.NONE -> WorkOpportunity.DETERMINISTIC_WORK
            else -> WorkOpportunity.COGNITIVE_WORK
        }
        return IdleDecision(opportunity, pressure, top, reason = "selected cheapest permitted handler ${handler.actionClass}")
    }

    private fun isPermitted(mode: IntentAutonomyMode, inference: InferenceLocation) = when (mode) {
        IntentAutonomyMode.OFF, IntentAutonomyMode.PERSIST_ONLY -> false
        IntentAutonomyMode.DETERMINISTIC -> inference == InferenceLocation.NONE
        IntentAutonomyMode.LOCAL -> inference != InferenceLocation.CLOUD
        IntentAutonomyMode.BUDGETED -> true
    }

    private fun aggregatePressure(eligible: List<RankedIntent>, env: RuntimeEnvironment): Double {
        if (eligible.isEmpty()) return 0.0
        val top = eligible.first().score.pressure
        val density = (eligible.size / 10.0).coerceAtMost(1.0)
        val momentum = eligible.maxOf { it.intent.progressRate ?: 0.0 }
        val opportunity = if (env.power in setOf(PowerState.CHARGING, PowerState.FULL) && env.connectivity in setOf(ConnectivityState.WIFI, ConnectivityState.TRUSTED_LAN)) 1.0 else .25
        return (.55 * top + .15 * density + .15 * momentum + .15 * opportunity).coerceIn(0.0, 1.0)
    }

    private fun cadence(mode: IntentAutonomyMode, env: RuntimeEnvironment, pressure: Double, top: RankedIntent?): Long? {
        if (mode in setOf(IntentAutonomyMode.OFF, IntentAutonomyMode.PERSIST_ONLY) || paused || env.userEngagement == UserEngagement.ACTIVE) return null
        val base = when {
            top?.intent?.identicalFailureCount ?: 0 >= 3 -> 60 * 60_000L
            pressure >= .80 -> 90_000L
            pressure >= .60 -> 3 * 60_000L
            pressure >= .40 -> 7 * 60_000L
            pressure >= .20 -> 15 * 60_000L
            else -> 40 * 60_000L
        }
        val jitter = random.nextDouble(.85, 1.25)
        return env.nowMs + (base * jitter).toLong()
    }

    private fun applyResult(intent: IntentRecord, result: WorkResult, nowMs: Long) {
        val next = when (result.outcome) {
            WorkResult.Outcome.COMPLETED -> IntentState.RESOLVED
            WorkResult.Outcome.PROGRESSED, WorkResult.Outcome.PREEMPTED -> IntentState.ELIGIBLE
            WorkResult.Outcome.BLOCKED -> IntentState.BLOCKED
            WorkResult.Outcome.WAITING_USER -> IntentState.WAITING_USER
            WorkResult.Outcome.WAITING_EVENT -> IntentState.WAITING_EVENT
            WorkResult.Outcome.FAILED -> if (intent.identicalFailureCount + 1 >= 3) IntentState.WAITING_EVENT else IntentState.FAILED
        }
        val updated = intent.copy(
            attemptCount = intent.attemptCount + if (result.outcome == WorkResult.Outcome.PREEMPTED) 0 else 1,
            successfulStepCount = intent.successfulStepCount + if (result.outcome in setOf(WorkResult.Outcome.PROGRESSED, WorkResult.Outcome.COMPLETED)) 1 else 0,
            identicalFailureCount = if (result.outcome == WorkResult.Outcome.FAILED) intent.identicalFailureCount + 1 else 0,
            informationGain = result.informationGain.coerceIn(0.0, 1.0), progressRate = result.progressRate.coerceIn(0.0, 1.0),
            lastWorkedAtMs = nowMs,
            cooldownUntilMs = if (result.outcome == WorkResult.Outcome.FAILED) nowMs + backoffMs(intent.identicalFailureCount + 1) else null,
            metadata = intent.metadata + ("lastWorkSummary" to result.summary),
        )
        transition(updated, next, nowMs, result.summary)
    }

    private fun transition(intent: IntentRecord, target: IntentState, atMs: Long, reason: String?) {
        val updated = IntentStateMachine.transition(intent, target, atMs, reason)
        store.save(updated)
        store.recordTransition(intent.id, intent.state, target, reason, atMs)
        store.updateTelemetry { it.copy(intentStateTransitions = it.intentStateTransitions + 1) }
    }

    private fun backoffMs(failureCount: Int): Long = when (failureCount) {
        0, 1 -> 60_000L
        2 -> 5 * 60_000L
        3 -> 30 * 60_000L
        else -> 2 * 60 * 60_000L
    }

    private fun telemetryFor(current: IdleTelemetry, location: InferenceLocation, usage: BudgetUsage) = current.copy(
        deterministicWorkUnits = current.deterministicWorkUnits + if (location == InferenceLocation.NONE) 1 else 0,
        localModelCalls = current.localModelCalls + if (location == InferenceLocation.LOCAL) 1 else 0,
        cloudModelCalls = current.cloudModelCalls + if (location == InferenceLocation.CLOUD) 1 else 0,
        idleInputTokens = current.idleInputTokens + usage.inputTokens,
        idleOutputTokens = current.idleOutputTokens + usage.outputTokens,
        idleCloudCostUsd = current.idleCloudCostUsd + usage.cloudCostUsd,
        idleWallTimeMs = current.idleWallTimeMs + usage.wallTimeMs,
    )
}

internal fun budgetDay(atMs: Long): String = java.time.Instant.ofEpochMilli(atMs).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString()
internal operator fun BudgetUsage.plus(other: BudgetUsage) = BudgetUsage(
    inputTokens + other.inputTokens, outputTokens + other.outputTokens, cloudCostUsd + other.cloudCostUsd,
    wallTimeMs + other.wallTimeMs, toolCalls + other.toolCalls,
)
