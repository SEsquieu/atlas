package com.grinningfrog.atlas.runtime

import com.grinningfrog.atlas.model.ContextStability
import com.grinningfrog.atlas.model.MotionState
import com.grinningfrog.atlas.model.ObservationTiming
import com.grinningfrog.atlas.model.VisualObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FreshnessPolicyTest {
    @Test fun nonVisualRequestsDoNotConsumeCameraContext() {
        val result = FreshnessPolicy.assess("Write a short toast", null, nowMs = 100_000)
        assertEquals(VisualUseCase.NONE, result.useCase)
        assertEquals(FreshnessDecision.NOT_REQUIRED, result.decision)
        assertNull(result.ageMs)
    }

    @Test fun movingContextExpiresSoonerThanStationaryContext() {
        val now = 100_000L
        val walking = observation(now - 12_000, MotionState.WALKING)
        val stationary = observation(now - 12_000, MotionState.STATIONARY)

        assertEquals(FreshnessDecision.REFRESH_REQUIRED, FreshnessPolicy.assess("What am I looking at?", walking, now).decision)
        assertEquals(FreshnessDecision.REUSE, FreshnessPolicy.assess("What am I looking at?", stationary, now).decision)
    }

    @Test fun highRiskClaimsAlwaysRequireANewSample() {
        val result = FreshnessPolicy.assess("Is this live circuit safe?", observation(99_999, MotionState.STATIONARY), 100_000)
        assertEquals(VisualUseCase.HIGH_RISK, result.useCase)
        assertEquals(FreshnessDecision.REFRESH_REQUIRED, result.decision)
    }

    @Test fun transitioningObservationIsNeverReused() {
        val observation = observation(99_999, MotionState.HANDHELD_STABLE).copy(stability = ContextStability.TRANSITIONING)
        assertEquals(FreshnessDecision.REFRESH_REQUIRED, FreshnessPolicy.assess("Can you see the label?", observation, 100_000).decision)
    }

    @Test fun sceneDifferenceIsDeterministic() {
        assertEquals(0.0, SceneDifference.score("001020", "001020")!!, 0.0001)
        assertEquals(1.0, SceneDifference.score("0000", "ffff")!!, 0.0001)
        assertNull(SceneDifference.score("00", "0000"))
    }

    private fun observation(atMs: Long, motion: MotionState) = VisualObservation(
        sessionId = "session",
        mediaPath = "/not/read/by/policy.jpg",
        observedAtMs = atMs,
        availableAtMs = atMs + 50,
        timing = ObservationTiming(50, 40, 10),
        stability = ContextStability.STABLE,
        motionState = motion,
    )
}
