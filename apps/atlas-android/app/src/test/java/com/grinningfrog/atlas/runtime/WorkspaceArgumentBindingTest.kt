package com.grinningfrog.atlas.runtime

import com.grinningfrog.atlas.model.ToolCallProposal
import com.grinningfrog.atlas.model.ToolDefinition
import com.grinningfrog.atlas.model.ToolRisk
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WorkspaceArgumentBindingTest {
    @Test fun advertisedSchemaMakesOnlyBoundFieldsOptional() {
        val definition = ToolDefinition("workspace_package_activate", "Activate", """{"type":"object","properties":{},"required":["workspace_id","candidate_id","navigation_sequence","check_id"]}""", ToolRisk.SESSION_WRITE)
        val schema = JSONObject(WorkspaceArgumentBinding.definition(definition).parametersJson)
        assertEquals("[\"check_id\"]", schema.getJSONArray("required").toString())
    }

    @Test fun missingCandidateAndExplicitNullAreNeverGuessed() {
        val original = ToolCallProposal("p", "workspace_package_test", "{}")
        val bound = JSONObject(WorkspaceArgumentBinding.bind(original, "w", 4, null, null, null).argumentsJson)
        assertEquals("w", bound.getString("workspace_id"))
        assertFalse(bound.has("candidate_id"))
        val explicit = original.copy(argumentsJson="""{"workspace_id":null}""")
        assertEquals(explicit, WorkspaceArgumentBinding.bind(explicit, "w", 4, "c", null, null))
    }
}
