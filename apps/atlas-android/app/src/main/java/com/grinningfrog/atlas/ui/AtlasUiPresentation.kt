package com.grinningfrog.atlas.ui

import com.grinningfrog.atlas.model.ListeningState
import com.grinningfrog.atlas.model.RuntimePhase
import com.grinningfrog.atlas.model.RuntimeSnapshot
import com.grinningfrog.atlas.model.SessionStatus

/** Pure projection of Core state into human-facing language. No runtime behavior belongs here. */
object AtlasUiPresentation {
    fun runtimeLabel(snapshot: RuntimeSnapshot): String = when {
        snapshot.listeningState == ListeningState.PREPARING -> "getting the microphone ready"
        snapshot.listeningState == ListeningState.READY -> "speak now"
        snapshot.listeningState == ListeningState.HEARING -> "listening"
        snapshot.listeningState == ListeningState.PROCESSING -> "heard you"
        snapshot.phase == RuntimePhase.CAPTURING -> "looking"
        snapshot.phase == RuntimePhase.THINKING -> "thinking"
        snapshot.phase == RuntimePhase.SPEAKING -> "speaking"
        snapshot.phase == RuntimePhase.DEGRADED -> "working with a degraded route"
        snapshot.phase == RuntimePhase.ERROR -> "needs attention"
        snapshot.session?.status == SessionStatus.PAUSED -> "paused"
        snapshot.session?.status == SessionStatus.ACTIVE -> "with you"
        else -> "ready"
    }

    fun voiceLabel(snapshot: RuntimeSnapshot, active: Boolean): String = when {
        !active -> "Voice available in an active session"
        snapshot.listeningState == ListeningState.PREPARING -> "Atlas · preparing"
        snapshot.listeningState == ListeningState.READY -> "Atlas · speak now"
        snapshot.listeningState == ListeningState.HEARING -> "Atlas · listening"
        snapshot.listeningState == ListeningState.PROCESSING -> "Atlas · heard you"
        snapshot.phase == RuntimePhase.SPEAKING -> "Atlas · speaking · tap to interrupt"
        snapshot.phase == RuntimePhase.THINKING -> "Atlas · thinking"
        else -> "Hold the thread with Atlas"
    }
}
