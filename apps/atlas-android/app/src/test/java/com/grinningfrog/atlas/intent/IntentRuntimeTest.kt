package com.grinningfrog.atlas.intent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentRuntimeTest {
    private val now = 1_000_000L
    private val idleEnvironment = RuntimeEnvironment(
        userEngagement = UserEngagement.DISENGAGED, power = PowerState.CHARGING,
        connectivity = ConnectivityState.WIFI, cognitionMode = CognitionMode.WORKSHOP,
        capabilities = setOf("local_database"), authorities = setOf("write_intent_metadata"), nowMs = now,
    )

    @Test fun stateMachineRejectsInvalidTransition() {
        val intent = intent(state = IntentState.NEW)
        assertFalse(IntentStateMachine.canTransition(IntentState.NEW, IntentState.RESOLVED))
        assertTrue(IntentStateMachine.canTransition(IntentState.NEW, IntentState.ELIGIBLE))
        assertTrue(IntentStateMachine.canTransition(IntentState.ELIGIBLE, IntentState.ABANDONED))
        assertEquals(IntentState.ELIGIBLE, IntentStateMachine.transition(intent, IntentState.ELIGIBLE, now).state)
    }

    @Test fun scoringIsTransparentBoundedAndStagnationLowersPressure() {
        val evaluator = IntentEligibilityEvaluator()
        val scorer = IntentScorer()
        val base = intent(nextAction = "refresh_intent_metadata")
        val eligible = evaluator.evaluate(base, idleEnvironment, IntentAutonomyMode.DETERMINISTIC)
        val healthy = scorer.score(base, eligible, idleEnvironment)
        val stagnant = scorer.score(base.copy(identicalFailureCount = 4, informationGain = 0.0), eligible, idleEnvironment)
        assertTrue(healthy.pressure in 0.0..1.0)
        assertTrue(healthy.components.containsKey("stagnation"))
        assertTrue(stagnant.pressure < healthy.pressure)
    }

    @Test fun missingAuthorityOverridesHighPressure() {
        val high = intent(importance = 1.0, userRelevance = 1.0, requiredAuthorities = setOf("send_external_message"))
        val result = IntentEligibilityEvaluator().evaluate(high, idleEnvironment, IntentAutonomyMode.BUDGETED)
        assertFalse(result.eligible)
        assertEquals(IntentState.BLOCKED, result.targetState)
    }

    @Test fun environmentReactivatesDormantIntent() {
        val garage = intent(state = IntentState.DORMANT, environmentalAffinity = EnvironmentalAffinity(locations = setOf("garage")))
        val absent = IntentEligibilityEvaluator().evaluate(garage, idleEnvironment, IntentAutonomyMode.DETERMINISTIC)
        val present = IntentEligibilityEvaluator().evaluate(garage, idleEnvironment.copy(locations = setOf("garage")), IntentAutonomyMode.DETERMINISTIC)
        assertFalse(absent.eligible)
        assertEquals(IntentState.WAITING_EVENT, absent.targetState)
        assertTrue(present.eligible)
        assertEquals(IntentState.ELIGIBLE, present.targetState)
    }

    @Test fun noIntentsAndOffModeMakeNoModelCalls() {
        val calls = Calls()
        val store = FakeStore()
        val coordinator = IdleRuntimeCoordinator(store, { IntentAutonomyMode.OFF }, handlers = listOf(calls.cloud))
        val decision = coordinator.evaluate(idleEnvironment)
        runBlocking { coordinator.execute(decision, idleEnvironment) }
        assertEquals(WorkOpportunity.NO_WORK, decision.opportunity)
        assertEquals(0, calls.count)
    }

    @Test fun deterministicModeCanWorkButNeverCallsModels() = runBlocking {
        val calls = Calls()
        val store = FakeStore(mutableListOf(intent(type = IntentType.MAINTENANCE, nextAction = "refresh_intent_metadata")))
        val coordinator = IdleRuntimeCoordinator(store, { IntentAutonomyMode.DETERMINISTIC }, handlers = listOf(IntentMetadataMaintenanceHandler(), calls.local, calls.cloud))
        val decision = coordinator.evaluate(idleEnvironment)
        assertEquals(WorkOpportunity.DETERMINISTIC_WORK, decision.opportunity)
        assertNotNull(coordinator.execute(decision, idleEnvironment))
        assertEquals(0, calls.count)
        assertEquals(1, store.telemetry.deterministicWorkUnits)
    }

    @Test fun localModeNeverFallsBackToCloud() = runBlocking {
        val calls = Calls()
        val store = FakeStore(mutableListOf(intent(type = IntentType.QUESTION)))
        val coordinator = IdleRuntimeCoordinator(store, { IntentAutonomyMode.LOCAL }, handlers = listOf(calls.cloud))
        val decision = coordinator.evaluate(idleEnvironment)
        coordinator.execute(decision, idleEnvironment)
        assertEquals(WorkOpportunity.WAIT_FOR_EVENT, decision.opportunity)
        assertEquals(0, calls.count)
    }

    @Test fun localModeCanCallLocalHandler() = runBlocking {
        val calls = Calls()
        val store = FakeStore(mutableListOf(intent(type = IntentType.QUESTION)))
        val coordinator = IdleRuntimeCoordinator(store, { IntentAutonomyMode.LOCAL }, handlers = listOf(calls.local, calls.cloud))
        val decision = coordinator.evaluate(idleEnvironment)
        coordinator.execute(decision, idleEnvironment)
        assertEquals(1, calls.count)
        assertEquals(1, store.telemetry.localModelCalls)
        assertEquals(0, store.telemetry.cloudModelCalls)
    }

    @Test fun budgetedCloudCannotStartSecondMaxCostSlice() = runBlocking {
        val calls = Calls()
        val intent = intent(type = IntentType.QUESTION)
        val store = FakeStore(mutableListOf(intent))
        val coordinator = IdleRuntimeCoordinator(store, { IntentAutonomyMode.BUDGETED },
            budgetProvider = { WorkBudget(dailyCloudCostUsd = .01) }, handlers = listOf(calls.cloud))
        val first = coordinator.evaluate(idleEnvironment)
        coordinator.execute(first, idleEnvironment)
        store.save(intent.copy(state = IntentState.ELIGIBLE))
        val second = coordinator.evaluate(idleEnvironment.copy(nowMs = now + 1_000))
        assertEquals(null, coordinator.execute(second, idleEnvironment.copy(nowMs = now + 1_000)))
        assertEquals(1, calls.count)
    }

    @Test fun persistOnlyRanksButDoesNoWork() = runBlocking {
        val calls = Calls()
        val store = FakeStore(mutableListOf(intent()))
        val coordinator = IdleRuntimeCoordinator(store, { IntentAutonomyMode.PERSIST_ONLY }, handlers = listOf(calls.local))
        val decision = coordinator.evaluate(idleEnvironment)
        assertEquals(WorkOpportunity.NO_WORK, decision.opportunity)
        assertEquals(null, coordinator.execute(decision, idleEnvironment))
        assertEquals(0, calls.count)
    }

    @Test fun userPreemptionCancelsActiveSliceAndPersistsProgress() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val never = CompletableDeferred<WorkResult>()
        val handler = object : IdleWorkHandler {
            override val actionClass = "slow"
            override val requiresInference = InferenceLocation.NONE
            override val requiredAuthorities = emptySet<String>()
            override fun canHandle(intent: IntentRecord) = true
            override suspend fun execute(intent: IntentRecord, contract: WorkSliceContract): WorkResult { started.complete(Unit); return never.await() }
        }
        val store = FakeStore(mutableListOf(intent()))
        val coordinator = IdleRuntimeCoordinator(store, { IntentAutonomyMode.DETERMINISTIC }, handlers = listOf(handler))
        val decision = coordinator.evaluate(idleEnvironment)
        val work = async { coordinator.execute(decision, idleEnvironment) }
        started.await(); coordinator.preempt(); val result = work.await()
        assertEquals(WorkResult.Outcome.PREEMPTED, result?.outcome)
        assertEquals(IntentState.ELIGIBLE, store.intents.single().state)
    }

    @Test fun restrictiveModeChangeCancelsActiveSlice() = runBlocking {
        var mode = IntentAutonomyMode.LOCAL
        val started = CompletableDeferred<Unit>()
        val never = CompletableDeferred<WorkResult>()
        val handler = object : IdleWorkHandler {
            override val actionClass = "local_slow"
            override val requiresInference = InferenceLocation.LOCAL
            override val requiredAuthorities = emptySet<String>()
            override fun canHandle(intent: IntentRecord) = true
            override suspend fun execute(intent: IntentRecord, contract: WorkSliceContract): WorkResult { started.complete(Unit); return never.await() }
        }
        val store = FakeStore(mutableListOf(intent()))
        val coordinator = IdleRuntimeCoordinator(store, { mode }, handlers = listOf(handler))
        val work = async { coordinator.execute(coordinator.evaluate(idleEnvironment), idleEnvironment) }
        started.await(); mode = IntentAutonomyMode.DETERMINISTIC; coordinator.onModeChanged()
        assertEquals(WorkResult.Outcome.PREEMPTED, work.await()?.outcome)
        assertEquals(0, store.telemetry.localModelCalls)
    }

    @Test fun budgetReservationsAreHardLimits() {
        val ledger = IdleBudgetLedger(WorkBudget(dailyInputTokens = 100, dailyOutputTokens = 50, dailyCloudCostUsd = .01))
        assertTrue(ledger.permits(BudgetUsage(inputTokens = 100, outputTokens = 50, cloudCostUsd = .01)))
        assertFalse(ledger.permits(BudgetUsage(inputTokens = 101)))
        assertFalse(ledger.permits(BudgetUsage(cloudCostUsd = .011)))
    }

    @Test fun deduplicationIsConservative() {
        val existing = intent(type = IntentType.FAILURE, subject = "bench PSU telemetry disconnected")
        assertNotNull(IntentDeduplicator.findMatch(intent(type = IntentType.FAILURE, subject = "bench PSU telemetry disconnected"), listOf(existing)))
        assertEquals(null, IntentDeduplicator.findMatch(intent(type = IntentType.QUESTION, subject = "bench PSU telemetry disconnected"), listOf(existing)))
    }

    private fun intent(
        type: IntentType = IntentType.TASK, subject: String = "test intent", state: IntentState = IntentState.ELIGIBLE,
        importance: Double = .9, userRelevance: Double = .9, nextAction: String? = "do_test_work",
        requiredAuthorities: Set<String> = emptySet(), environmentalAffinity: EnvironmentalAffinity? = null,
    ) = IntentRecord(type = type, subject = subject, origin = IntentOrigin.USER, createdAtMs = now - 10_000,
        lastUpdatedAtMs = now - 1_000, state = state, importance = importance, userRelevance = userRelevance,
        confidence = .9, nextAction = nextAction, requiredAuthorities = requiredAuthorities, environmentalAffinity = environmentalAffinity)

    private class FakeStore(val intents: MutableList<IntentRecord> = mutableListOf()) : IntentStore {
        var telemetry = IdleTelemetry()
        private val budgets = mutableMapOf<String, BudgetUsage>()
        override fun loadNonTerminal() = intents.filter { it.state !in setOf(IntentState.RESOLVED, IntentState.ABANDONED, IntentState.SUPERSEDED) }
        override fun save(intent: IntentRecord) { intents.removeAll { it.id == intent.id }; intents += intent }
        override fun recordTransition(intentId: String, from: IntentState, to: IntentState, reason: String?, atMs: Long) = Unit
        override fun recordEvaluation(evaluation: IdleEvaluationRecord) = Unit
        override fun recordWorkAttempt(attempt: IdleWorkAttemptRecord) = Unit
        override fun loadTelemetry() = telemetry
        override fun updateTelemetry(transform: (IdleTelemetry) -> IdleTelemetry) { telemetry = transform(telemetry) }
        override fun loadBudgetUsage(day: String) = budgets[day] ?: BudgetUsage()
        override fun updateBudgetUsage(day: String, transform: (BudgetUsage) -> BudgetUsage) { budgets[day] = transform(loadBudgetUsage(day)) }
    }

    private class Calls {
        var count = 0
        val local = handler(InferenceLocation.LOCAL)
        val cloud = handler(InferenceLocation.CLOUD)
        private fun handler(location: InferenceLocation) = object : IdleWorkHandler {
            override val actionClass = "model_${location.name.lowercase()}"
            override val requiresInference = location
            override val requiredAuthorities = emptySet<String>()
            override fun canHandle(intent: IntentRecord) = true
            override suspend fun execute(intent: IntentRecord, contract: WorkSliceContract): WorkResult {
                count++
                return WorkResult(WorkResult.Outcome.PROGRESSED, "called", usage = BudgetUsage(inputTokens = 10, outputTokens = 10, cloudCostUsd = if (location == InferenceLocation.CLOUD) contract.maxCostUsd else 0.0))
            }
        }
    }
}
