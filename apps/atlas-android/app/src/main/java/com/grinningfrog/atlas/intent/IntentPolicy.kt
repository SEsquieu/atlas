package com.grinningfrog.atlas.intent

object IntentStateMachine {
    private val terminal = setOf(IntentState.RESOLVED, IntentState.ABANDONED, IntentState.SUPERSEDED)
    private val transitions = mapOf(
        IntentState.NEW to setOf(IntentState.ELIGIBLE, IntentState.BLOCKED, IntentState.DORMANT),
        IntentState.ELIGIBLE to setOf(IntentState.ACTIVE, IntentState.BLOCKED, IntentState.DORMANT),
        IntentState.ACTIVE to setOf(IntentState.ELIGIBLE, IntentState.BLOCKED, IntentState.WAITING_USER, IntentState.WAITING_EVENT, IntentState.RESOLVED, IntentState.FAILED, IntentState.SUPERSEDED),
        IntentState.BLOCKED to setOf(IntentState.ELIGIBLE, IntentState.DORMANT),
        IntentState.DORMANT to setOf(IntentState.ELIGIBLE),
        IntentState.WAITING_USER to setOf(IntentState.ELIGIBLE),
        IntentState.WAITING_EVENT to setOf(IntentState.ELIGIBLE),
        IntentState.FAILED to setOf(IntentState.ELIGIBLE, IntentState.ABANDONED),
    )

    fun canTransition(from: IntentState, to: IntentState): Boolean =
        from == to || (from !in terminal && to in setOf(IntentState.ABANDONED, IntentState.SUPERSEDED)) || transitions[from]?.contains(to) == true

    fun transition(intent: IntentRecord, to: IntentState, nowMs: Long, reason: String? = null): IntentRecord {
        require(canTransition(intent.state, to)) { "Illegal intent transition ${intent.state} -> $to" }
        return intent.copy(state = to, lastUpdatedAtMs = nowMs, blockedReason = reason.takeIf { to in setOf(IntentState.BLOCKED, IntentState.WAITING_EVENT, IntentState.WAITING_USER) })
    }
}

class IntentEligibilityEvaluator {
    fun evaluate(intent: IntentRecord, environment: RuntimeEnvironment, mode: IntentAutonomyMode): Eligibility {
        if (mode == IntentAutonomyMode.OFF) return Eligibility(false, intent.state, "autonomy mode OFF")
        if (intent.state in setOf(IntentState.RESOLVED, IntentState.ABANDONED, IntentState.SUPERSEDED)) return Eligibility(false, intent.state, "terminal")
        if (intent.cooldownUntilMs?.let { it > environment.nowMs } == true) return Eligibility(false, intent.state, "cooldown")
        val missingAuthority = intent.requiredAuthorities - environment.authorities
        if (missingAuthority.isNotEmpty()) return Eligibility(false, IntentState.BLOCKED, "missing authority: ${missingAuthority.sorted().joinToString()}")
        val missingCapability = intent.requiredCapabilities - environment.capabilities
        if (missingCapability.isNotEmpty()) return Eligibility(false, IntentState.WAITING_EVENT, "missing capability: ${missingCapability.sorted().joinToString()}")
        if (intent.state == IntentState.WAITING_USER && environment.userEngagement !in setOf(UserEngagement.ACTIVE, UserEngagement.RECENT)) {
            return Eligibility(false, IntentState.WAITING_USER, "waiting for user")
        }
        val affinity = intent.environmentalAffinity
        if (affinity != null && !environmentMatches(affinity, environment)) return Eligibility(false, IntentState.WAITING_EVENT, "environmental affinity absent")
        val actionability = when {
            intent.nextAction.isNullOrBlank() -> .35
            intent.estimatedRisk?.let { it >= .8 } == true -> .15
            else -> 1.0
        }
        return Eligibility(true, IntentState.ELIGIBLE, actionability = actionability)
    }

    fun environmentMatches(affinity: EnvironmentalAffinity, env: RuntimeEnvironment): Boolean {
        fun matches(required: Set<String>, present: Set<String>) = required.isEmpty() || required.any(present::contains)
        return matches(affinity.locations, env.locations) && matches(affinity.objects, env.visibleObjects) &&
            matches(affinity.capabilities, env.capabilities) && matches(affinity.projects, env.activeProjects) &&
            (affinity.networkContexts.isEmpty() || env.connectivity.name.lowercase() in affinity.networkContexts.map(String::lowercase)) &&
            (!affinity.requiresCharging || env.power in setOf(PowerState.CHARGING, PowerState.FULL))
    }
}

data class ScoringWeights(
    val importance: Double = .25, val userRelevance: Double = .20, val actionability: Double = .20,
    val environment: Double = .15, val expectedProgress: Double = .10, val resourceFit: Double = .10,
    val blockage: Double = .25, val stagnation: Double = .15, val risk: Double = .15, val cost: Double = .10,
)

class IntentScorer(private val weights: ScoringWeights = ScoringWeights()) {
    fun score(intent: IntentRecord, eligibility: Eligibility, env: RuntimeEnvironment): IntentScore {
        val environment = environmentalRelevance(intent.environmentalAffinity, env)
        val expectedProgress = intent.progressRate ?: if (intent.nextAction.isNullOrBlank()) .25 else .65
        val resourceFit = when {
            env.power in setOf(PowerState.CHARGING, PowerState.FULL) && env.connectivity in setOf(ConnectivityState.WIFI, ConnectivityState.TRUSTED_LAN) -> 1.0
            env.connectivity == ConnectivityState.OFFLINE -> .25
            else -> .55
        }
        val stagnation = (intent.identicalFailureCount / 3.0).coerceIn(0.0, 1.0) * (1.0 - (intent.informationGain ?: 0.0))
        val components = linkedMapOf(
            "importance" to weights.importance * intent.importance,
            "user_relevance" to weights.userRelevance * intent.userRelevance,
            "actionability" to weights.actionability * eligibility.actionability,
            "environment" to weights.environment * environment,
            "expected_progress" to weights.expectedProgress * expectedProgress,
            "resource_fit" to weights.resourceFit * resourceFit,
            "blockage" to -weights.blockage * if (eligibility.eligible) 0.0 else 1.0,
            "stagnation" to -weights.stagnation * stagnation,
            "risk" to -weights.risk * (intent.estimatedRisk ?: 0.0),
            "cost" to -weights.cost * (intent.estimatedCost ?: 0.0),
        )
        return IntentScore(intent.id, components.values.sum().coerceIn(0.0, 1.0), components)
    }

    private fun environmentalRelevance(affinity: EnvironmentalAffinity?, env: RuntimeEnvironment): Double {
        if (affinity == null) return .5
        val checks = mutableListOf<Boolean>()
        if (affinity.locations.isNotEmpty()) checks += affinity.locations.any(env.locations::contains)
        if (affinity.objects.isNotEmpty()) checks += affinity.objects.any(env.visibleObjects::contains)
        if (affinity.capabilities.isNotEmpty()) checks += affinity.capabilities.any(env.capabilities::contains)
        if (affinity.projects.isNotEmpty()) checks += affinity.projects.any(env.activeProjects::contains)
        if (affinity.networkContexts.isNotEmpty()) checks += env.connectivity.name.lowercase() in affinity.networkContexts.map(String::lowercase)
        if (affinity.requiresCharging) checks += env.power in setOf(PowerState.CHARGING, PowerState.FULL)
        return if (checks.isEmpty()) .5 else checks.count { it }.toDouble() / checks.size
    }
}

object IntentDeduplicator {
    fun findMatch(candidate: IntentRecord, existing: List<IntentRecord>): IntentRecord? {
        val normalized = normalize(candidate.subject)
        return existing.filter { it.state !in setOf(IntentState.RESOLVED, IntentState.ABANDONED, IntentState.SUPERSEDED) }
            .firstOrNull { it.type == candidate.type && (normalize(it.subject) == normalized || sharedTokens(it.subject, candidate.subject) >= .75) }
    }

    private fun normalize(value: String) = value.lowercase().replace(Regex("[^a-z0-9 ]"), " ").trim().replace(Regex("\\s+"), " ")
    private fun sharedTokens(a: String, b: String): Double {
        val left = normalize(a).split(' ').filter(String::isNotBlank).toSet()
        val right = normalize(b).split(' ').filter(String::isNotBlank).toSet()
        if (left.isEmpty() || right.isEmpty()) return 0.0
        return left.intersect(right).size.toDouble() / left.union(right).size
    }
}
