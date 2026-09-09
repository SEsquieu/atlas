package com.grinningfrog.atlas.ui

import com.grinningfrog.atlas.model.AtlasSession
import com.grinningfrog.atlas.model.ListeningState
import com.grinningfrog.atlas.model.RuntimePhase
import com.grinningfrog.atlas.model.RuntimeSnapshot
import com.grinningfrog.atlas.model.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class AtlasUiPresentationTest {
    @Test fun listeningStateTakesPriorityOverInferencePhase() {
        val snapshot = RuntimeSnapshot(phase = RuntimePhase.THINKING, listeningState = ListeningState.HEARING)
        assertEquals("listening", AtlasUiPresentation.runtimeLabel(snapshot))
        assertEquals("Atlas · listening", AtlasUiPresentation.voiceLabel(snapshot, active = true))
    }

    @Test fun pausedSessionNeverClaimsVoiceIsAvailable() {
        val session = AtlasSession("id", "Bench", "Repair", SessionStatus.PAUSED, 0, 0)
        val snapshot = RuntimeSnapshot(session = session, phase = RuntimePhase.READY)
        assertEquals("paused", AtlasUiPresentation.runtimeLabel(snapshot))
        assertEquals("Voice available in an active session", AtlasUiPresentation.voiceLabel(snapshot, active = false))
    }

    @Test fun speakingExplainsBargeIn() {
        val snapshot = RuntimeSnapshot(phase = RuntimePhase.SPEAKING)
        assertEquals("Atlas · speaking · tap to interrupt", AtlasUiPresentation.voiceLabel(snapshot, active = true))
    }
}
