package com.grinningfrog.atlas.runtime

import com.grinningfrog.atlas.model.MotionState
import com.grinningfrog.atlas.model.MediaPurpose
import com.grinningfrog.atlas.model.RouteCapability
import com.grinningfrog.atlas.model.VisualObservation

enum class VisualUseCase { NONE, DESCRIPTIVE, DETAIL, CONFIRMATION, NAVIGATION, HIGH_RISK }
enum class FreshnessDecision { NOT_REQUIRED, REUSE, REFRESH_DUE, REFRESH_REQUIRED }

data class FreshnessAssessment(
    val useCase: VisualUseCase,
    val decision: FreshnessDecision,
    val ageMs: Long?,
    val staleAfterMs: Long,
    val refreshDueAtMs: Long?,
    val reason: String,
)

object FreshnessPolicy {
    private val visualPatterns = listOf(
        Regex("what (am i|are we) looking at", RegexOption.IGNORE_CASE), Regex("can you see", RegexOption.IGNORE_CASE),
        Regex("look at this", RegexOption.IGNORE_CASE), Regex("read this", RegexOption.IGNORE_CASE),
        Regex("what does this say", RegexOption.IGNORE_CASE), Regex("which one", RegexOption.IGNORE_CASE),
        Regex("is this", RegexOption.IGNORE_CASE), Regex("where should", RegexOption.IGNORE_CASE),
        Regex("help me (fix|find|identify)", RegexOption.IGNORE_CASE),
    )
    private val highRisk = Regex("(wire.*cut|safe|danger|hazard|drive|energized|live circuit)", RegexOption.IGNORE_CASE)
    private val navigation = Regex("(where should|which way|right place|right aisle|navigate|direction)", RegexOption.IGNORE_CASE)
    private val detail = Regex("(read this|what does this say|label|small text|serial|part number|model number)", RegexOption.IGNORE_CASE)
    private val confirmation = Regex("(^|\\s)(is|are|am|do|does|confirm|right one)", RegexOption.IGNORE_CASE)

    fun classify(text: String): VisualUseCase {
        if (highRisk.containsMatchIn(text)) return VisualUseCase.HIGH_RISK
        if (navigation.containsMatchIn(text)) return VisualUseCase.NAVIGATION
        if (detail.containsMatchIn(text)) return VisualUseCase.DETAIL
        if (visualPatterns.none { it.containsMatchIn(text) }) return VisualUseCase.NONE
        if (confirmation.containsMatchIn(text)) return VisualUseCase.CONFIRMATION
        return VisualUseCase.DESCRIPTIVE
    }

    fun assess(text: String, observation: VisualObservation?, nowMs: Long, expectedRefreshMs: Long = 4_000): FreshnessAssessment {
        val useCase = classify(text)
        if (useCase == VisualUseCase.NONE) return FreshnessAssessment(useCase, FreshnessDecision.NOT_REQUIRED, null, 0, null, "request does not require visual context")
        if (observation == null) return FreshnessAssessment(useCase, FreshnessDecision.REFRESH_REQUIRED, null, staleWindow(MotionState.UNKNOWN, useCase), null, "no visual observation is available")
        val age = (nowMs - observation.observedAtMs).coerceAtLeast(0)
        val staleAfter = staleWindow(observation.motionState, useCase)
        if (useCase == VisualUseCase.HIGH_RISK) return FreshnessAssessment(useCase, FreshnessDecision.REFRESH_REQUIRED, age, 0, nowMs, "high-risk visual claims require a new physical sample")
        if (useCase == VisualUseCase.DETAIL && observation.media.purpose != MediaPurpose.DETAIL_VISION) {
            return FreshnessAssessment(useCase, FreshnessDecision.REFRESH_REQUIRED, age, staleAfter, nowMs, "request needs a detail-quality observation")
        }
        if (observation.stability == com.grinningfrog.atlas.model.ContextStability.TRANSITIONING) {
            return FreshnessAssessment(useCase, FreshnessDecision.REFRESH_REQUIRED, age, staleAfter, nowMs, "latest observation was captured while context was transitioning")
        }
        val refreshDueAt = observation.observedAtMs + staleAfter - expectedRefreshMs.coerceAtMost(staleAfter)
        return when {
            age >= staleAfter -> FreshnessAssessment(useCase, FreshnessDecision.REFRESH_REQUIRED, age, staleAfter, refreshDueAt, "visual context is stale")
            nowMs >= refreshDueAt -> FreshnessAssessment(useCase, FreshnessDecision.REFRESH_DUE, age, staleAfter, refreshDueAt, "visual context is usable but refresh is due")
            else -> FreshnessAssessment(useCase, FreshnessDecision.REUSE, age, staleAfter, refreshDueAt, "visual context is fresh enough for this use case")
        }
    }

    fun heartbeatWindow(motion: MotionState, batteryConstrained: Boolean): Long {
        val base = when (motion) {
            MotionState.STATIONARY -> 60_000L
            MotionState.HANDHELD_STABLE -> 30_000L
            MotionState.WALKING, MotionState.TURNING -> 10_000L
            MotionState.VEHICLE -> 5_000L
            MotionState.UNKNOWN -> 20_000L
        }
        return if (batteryConstrained) (base * 2).coerceAtMost(120_000) else base
    }

    fun rollingInterpretationIsFresh(observation: VisualObservation, nowMs: Long): Boolean {
        val interpretedAt = observation.interpretedAtMs ?: return false
        val evidenceAge = (nowMs - observation.observedAtMs).coerceAtLeast(0)
        val interpretationAge = (nowMs - interpretedAt).coerceAtLeast(0)
        val window = heartbeatWindow(observation.motionState, batteryConstrained = false)
        return observation.summary != null && evidenceAge <= window && interpretationAge <= window
    }

    private fun staleWindow(motion: MotionState, useCase: VisualUseCase): Long = when (motion) {
        MotionState.STATIONARY -> when (useCase) { VisualUseCase.DESCRIPTIVE -> 120_000L; VisualUseCase.CONFIRMATION -> 45_000L; else -> 30_000L }
        MotionState.HANDHELD_STABLE -> when (useCase) { VisualUseCase.DESCRIPTIVE -> 60_000L; VisualUseCase.CONFIRMATION -> 30_000L; else -> 15_000L }
        MotionState.WALKING, MotionState.TURNING -> when (useCase) { VisualUseCase.DESCRIPTIVE -> 10_000L; else -> 5_000L }
        MotionState.VEHICLE -> 2_000L
        MotionState.UNKNOWN -> 15_000L
    }
}

object InferenceIntentPolicy {
    private val reasoning = Regex("(analy[sz]e|compare|plan|troubleshoot|diagnos|explain|reason|trade.?off|step by step|what should|how should|why (is|are|does|do|did|would|should))", RegexOption.IGNORE_CASE)

    fun capability(text: String, visualUseCase: VisualUseCase): RouteCapability = when {
        visualUseCase != VisualUseCase.NONE -> RouteCapability.VISION
        reasoning.containsMatchIn(text) -> RouteCapability.REASONING
        else -> RouteCapability.FAST
    }
}

data class ProactiveInferenceAssessment(val allowed: Boolean, val reason: String)

object ProactiveInferencePolicy {
    const val COOLDOWN_MS = 60_000L
    const val WINDOW_MS = 60 * 60_000L
    const val MAX_CALLS_PER_WINDOW = 12

    fun assess(nowMs: Long, recentAttemptsMs: List<Long>): ProactiveInferenceAssessment {
        if (recentAttemptsMs.size >= MAX_CALLS_PER_WINDOW) return ProactiveInferenceAssessment(false, "hourly inference ceiling reached")
        val latest = recentAttemptsMs.maxOrNull()
        if (latest != null && nowMs - latest < COOLDOWN_MS) return ProactiveInferenceAssessment(false, "inference cooldown active")
        return ProactiveInferenceAssessment(true, "within proactive inference budget")
    }
}

object SceneDifference {
    fun score(previous: String?, current: String?): Double? {
        if (previous == null || current == null || previous.length != current.length || previous.length % 2 != 0) return null
        val differences = previous.chunked(2).zip(current.chunked(2)).map { (a, b) ->
            kotlin.math.abs(a.toInt(16) - b.toInt(16)) / 255.0
        }
        return differences.average().coerceIn(0.0, 1.0)
    }
}
