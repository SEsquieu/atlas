package com.grinningfrog.atlas.workspace.visual

import org.junit.Assert.*
import org.junit.Test

class WorkspaceVisualStartupTest {
    @Test fun failureReportsActiveAndLastCompletedPhaseWithMonotonicElapsedTime() {
        var now = 100L
        val startup = WorkspaceVisualStartup { now }
        startup.begin("display_allocation")
        now += 20
        startup.complete()
        startup.begin("candidate_ready")
        now += 8000
        val failure = startup.snapshot()
        assertEquals("candidate_ready", failure.getString("phase"))
        assertEquals("display_allocation", failure.getString("last_completed_phase"))
        assertEquals(8020L, failure.getLong("elapsed_ms"))
        assertEquals(8000L, failure.getLong("phase_elapsed_ms"))
        assertEquals(20L, failure.getJSONArray("completed_phases").getJSONObject(0).getLong("elapsed_ms"))
        // Previously returned evidence cannot change when startup advances.
        startup.complete()
        assertEquals(1, failure.getJSONArray("completed_phases").length())
        assertEquals(2, startup.snapshot().getJSONArray("completed_phases").length())
    }
}
