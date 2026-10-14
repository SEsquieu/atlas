package com.grinningfrog.atlas.ui

import com.grinningfrog.atlas.model.AtlasSession
import com.grinningfrog.atlas.model.ListeningState
import com.grinningfrog.atlas.model.RuntimePhase
import com.grinningfrog.atlas.model.RuntimeSnapshot
import com.grinningfrog.atlas.model.SessionStatus
import org.json.JSONObject
import com.grinningfrog.atlas.runtime.ExecutionTask
import com.grinningfrog.atlas.runtime.ExecutionState
import com.grinningfrog.atlas.model.*
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
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
    private fun activeSnapshot() = RuntimeSnapshot(session = AtlasSession("id", "Test", "Test", SessionStatus.ACTIVE, 0, 0))
    private fun task(reason: String, state: ExecutionState = ExecutionState.WAITING, sessionId: String = "id") =
        ExecutionTask("task", sessionId, state, 0, null, 0, 0, JSONObject().put("objective", "Task").put("wait_reason", reason))

    @Test fun glowRequiresActualUserActionNotProviderErrorsOrWorkInProgress() {
        val base = activeSnapshot()
        assertFalse(AtlasUiPresentation.needsUserAction(base.copy(phase = RuntimePhase.ERROR, latestError = "HTTP 520")))
        assertFalse(AtlasUiPresentation.needsUserAction(base.copy(executionTasks = listOf(task("provider")))))
        assertFalse(AtlasUiPresentation.needsUserAction(base.copy(executionTasks = listOf(task("", ExecutionState.RUNNING)))))
        assertFalse(AtlasUiPresentation.needsUserAction(base.copy(executionTasks = listOf(task("unknown_operation_outcome", ExecutionState.COMPLETED)))))
        assertFalse(AtlasUiPresentation.needsUserAction(base.copy(executionTasks = listOf(task("task_allowance", sessionId = "another-session")))))
        assertTrue(AtlasUiPresentation.needsUserAction(base.copy(executionTasks = listOf(task("unknown_operation_outcome")))))
        assertTrue(AtlasUiPresentation.needsUserAction(base.copy(executionTasks = listOf(task("task_allowance")))))
        assertFalse(AtlasUiPresentation.needsUserAction(base.copy(session = base.session!!.copy(status = SessionStatus.DONE), executionTasks = listOf(task("task_allowance")))))
    }
    @Test fun onlyPendingBlockingClarificationLightsAlertAndResolutionClearsIt() {
        val question = PendingClarification("question", "id", "turn", "Which one?", "Ambiguous", ClarificationAmbiguity.REFERENT, createdAtMs = 0, updatedAtMs = 0)
        val base = activeSnapshot()
        assertTrue(AtlasUiPresentation.needsUserAction(base.copy(pendingClarification = question)))
        assertFalse(AtlasUiPresentation.needsUserAction(base.copy(pendingClarification = question.copy(blocking = false))))
        assertFalse(AtlasUiPresentation.needsUserAction(base.copy(pendingClarification = question.copy(status = ClarificationStatus.RESOLVED))))
        assertFalse(AtlasUiPresentation.needsUserAction(base.copy(pendingClarification = null)))
    }
    @Test fun pendingApprovalLightsAlertUntilHandled() {
        val call = AtlasToolCall("call", "id", "turn", "tool", "{}", ToolCallStatus.WAITING_FOR_CONFIRMATION, ToolRisk.EXTERNAL_EFFECT,
            true, "key", createdAtMs = 0, updatedAtMs = 0)
        assertTrue(AtlasUiPresentation.needsUserAction(activeSnapshot().copy(pendingToolCalls = listOf(call))))
        assertFalse(AtlasUiPresentation.needsUserAction(activeSnapshot().copy(pendingToolCalls = listOf(call.copy(status = ToolCallStatus.COMPLETED)))))
    }

}
