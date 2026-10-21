package com.grinningfrog.atlas.runtime

import com.grinningfrog.atlas.model.AtlasToolCall
import com.grinningfrog.atlas.model.ToolCallStatus
import com.grinningfrog.atlas.model.ToolRisk
import org.junit.Assert.*
import org.junit.Test

class ExecutionWorkStateEditsTest {
    @Test fun patchPathsAndPendingVerificationSurviveTaskProjection() {
        val call = AtlasToolCall("stage", "session", "turn", "workspace_package_stage",
            """{"workspace_id":"w","files":{"ui/index.html":"html"},"edits":[{"path":"ui/app.js"}]}""",
            ToolCallStatus.COMPLETED, ToolRisk.SESSION_WRITE, false, "operation",
            resultJson="""{"candidate_id":"candidate","structurally_valid":true}""", createdAtMs=1, updatedAtMs=2)
        val work = ExecutionWorkState.build(listOf(call), "w")
        assertEquals("[\"ui/app.js\",\"ui/index.html\"]", work.getJSONArray("changed_files").toString())
        assertEquals(1, work.getInt("consecutive_untested_stages"))
        assertTrue(work.getString("next_authoring_action").contains("Verify"))
        assertEquals("staged_not_activated", work.getString("delivery_status"))
    }
}
