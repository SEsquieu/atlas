package com.grinningfrog.atlas.ui

import com.grinningfrog.atlas.model.ListeningState
import com.grinningfrog.atlas.model.RuntimePhase
import com.grinningfrog.atlas.model.RuntimeSnapshot
import com.grinningfrog.atlas.model.SessionStatus

/** Pure projection of Core state into human-facing language. No runtime behavior belongs here. */
object AtlasUiPresentation {
    fun deliveryLabel(task: com.grinningfrog.atlas.runtime.ExecutionTask): String? {
        val work=task.document.optJSONObject("work_state") ?: return null
        val id=work.optJSONObject("latest_candidate")?.optString("candidate_id").orEmpty().take(8)
        return when(work.optString("delivery_status")) {
            "activated" -> "Revision activated · ${work.optJSONObject("activation")?.optString("revision_id").orEmpty().take(8)}"
            "verification_failed" -> "Candidate $id · verification failed · live app unchanged"
            "staged_not_activated" -> "Candidate $id · staged, not live"
            else -> if(task.document.optJSONObject("completion_requirement")?.optString("delivery")=="activation") "Live update requested · not activated yet" else null
        }
    }

    fun attentionTasks(snapshot: RuntimeSnapshot) = snapshot.executionTasks.filter {
        it.sessionId == snapshot.session?.id && it.state == com.grinningfrog.atlas.runtime.ExecutionState.WAITING &&
            it.waitReason in setOf("clarification", "authorization", "unknown_operation_outcome", "task_allowance", "unresolved_tool_failure", "repeated_failure", "runtime_blocker", "context_cutoff")
    }
    fun needsUserAction(snapshot: RuntimeSnapshot): Boolean = snapshot.session?.status?.let { status -> status != SessionStatus.DONE } == true &&
        (snapshot.pendingClarification?.let { it.blocking && it.status == com.grinningfrog.atlas.model.ClarificationStatus.WAITING } == true ||
            snapshot.pendingToolCalls.any { it.status == com.grinningfrog.atlas.model.ToolCallStatus.WAITING_FOR_CONFIRMATION } || attentionTasks(snapshot).isNotEmpty())

    fun canSubmitMessage(snapshot: RuntimeSnapshot, hasProvider: Boolean): Boolean =
        snapshot.session?.status == SessionStatus.ACTIVE && hasProvider &&
            (snapshot.activeTurn == null || snapshot.pendingClarification != null || needsUserAction(snapshot) ||
                snapshot.activeTurn?.status in setOf(com.grinningfrog.atlas.model.TurnStatus.SOFT_TIMED_OUT,
                    com.grinningfrog.atlas.model.TurnStatus.WAITING_FOR_CONFIRMATION,
                    com.grinningfrog.atlas.model.TurnStatus.WAITING_FOR_USER_CLARIFICATION))

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
