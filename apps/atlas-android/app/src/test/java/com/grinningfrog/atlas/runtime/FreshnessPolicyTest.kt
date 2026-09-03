package com.grinningfrog.atlas.runtime

import com.grinningfrog.atlas.model.ContextStability
import com.grinningfrog.atlas.model.MotionState
import com.grinningfrog.atlas.model.MediaPurpose
import com.grinningfrog.atlas.model.MediaRef
import com.grinningfrog.atlas.model.ObservationTiming
import com.grinningfrog.atlas.model.VisualObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test fun detailRequestsDoNotReuseHeartbeatThumbnails() {
        val heartbeat = observation(99_999, MotionState.STATIONARY).copy(
            media = observation(99_999, MotionState.STATIONARY).media.copy(purpose = MediaPurpose.HEARTBEAT),
        )
        val result = FreshnessPolicy.assess("Read this small label", heartbeat, 100_000)
        assertEquals(VisualUseCase.DETAIL, result.useCase)
        assertEquals(FreshnessDecision.REFRESH_REQUIRED, result.decision)
    }

    @Test fun detailRequestsReuseFreshDetailCaptures() {
        val detail = observation(99_999, MotionState.STATIONARY).copy(
            media = observation(99_999, MotionState.STATIONARY).media.copy(purpose = MediaPurpose.DETAIL_VISION),
        )
        assertEquals(FreshnessDecision.REUSE, FreshnessPolicy.assess("Read this small label", detail, 100_000).decision)
    }

    @Test fun ordinaryTextUsesFastRouteAndComplexTextUsesReasoning() {
        assertEquals(com.grinningfrog.atlas.model.RouteCapability.FAST, InferenceIntentPolicy.capability("Tell me a short joke", VisualUseCase.NONE))
        assertEquals(com.grinningfrog.atlas.model.RouteCapability.REASONING, InferenceIntentPolicy.capability("Compare these approaches and explain the tradeoffs", VisualUseCase.NONE))
        assertEquals(com.grinningfrog.atlas.model.RouteCapability.VISION, InferenceIntentPolicy.capability("What am I looking at?", VisualUseCase.DESCRIPTIVE))
    }

    @Test fun proactiveInferenceHasDurableCooldownAndHourlyCeiling() {
        assertTrue(ProactiveInferencePolicy.assess(100_000, emptyList()).allowed)
        assertFalse(ProactiveInferencePolicy.assess(100_000, listOf(99_999)).allowed)
        val fullWindow = (0 until ProactiveInferencePolicy.MAX_CALLS_PER_WINDOW).map { it * 100_000L }
        assertFalse(ProactiveInferencePolicy.assess(2_000_000, fullWindow).allowed)
    }

    @Test fun rollingSemanticContextExpiresWithItsPhysicalEvidence() {
        val fresh = observation(99_000, MotionState.STATIONARY).copy(summary = "A workbench", interpretedAtMs = 99_100)
        assertTrue(FreshnessPolicy.rollingInterpretationIsFresh(fresh, 100_000))
        assertFalse(FreshnessPolicy.rollingInterpretationIsFresh(fresh, 200_000))
        assertFalse(FreshnessPolicy.rollingInterpretationIsFresh(fresh.copy(summary = null), 100_000))
    }

    @Test fun sceneDifferenceIsDeterministic() {
        assertEquals(0.0, SceneDifference.score("001020", "001020")!!, 0.0001)
        assertEquals(1.0, SceneDifference.score("0000", "ffff")!!, 0.0001)
        assertNull(SceneDifference.score("00", "0000"))
    }

    private fun observation(atMs: Long, motion: MotionState) = VisualObservation(
        sessionId = "session",
        media = MediaRef("media", "test.jpg", "image/jpeg", 100, 100, 10, "hash", MediaPurpose.STANDARD_VISION, 20, 1),
        observedAtMs = atMs,
        availableAtMs = atMs + 50,
        timing = ObservationTiming(50, 40, 10),
        stability = ContextStability.STABLE,
        motionState = motion,
    )
}
