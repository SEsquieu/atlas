package com.grinningfrog.atlas.workspace

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceDefinitionValidatorTest {
    @Test fun acceptsStarterDefinition() {
        assertTrue(WorkspaceDefinitionValidator.validate(WorkspaceDefinitionValidator.starter("Bench", "Fixture notes")).valid)
    }

    @Test fun rejectsUnsupportedComponents() {
        val definition = JSONObject().apply {
            put("format", WorkspaceDefinitionValidator.FORMAT)
            put("title", "Unsafe")
            put("components", JSONArray().put(JSONObject().put("id", "shell").put("type", "execute_kotlin")))
        }.toString()
        assertFalse(WorkspaceDefinitionValidator.validate(definition).valid)
    }

    @Test fun rejectsDuplicateComponentIds() {
        val definition = JSONObject().apply {
            put("format", WorkspaceDefinitionValidator.FORMAT)
            put("title", "Duplicate")
            put("components", JSONArray()
                .put(JSONObject().put("id", "same").put("type", "text").put("text", "one"))
                .put(JSONObject().put("id", "same").put("type", "notes")))
        }.toString()
        assertFalse(WorkspaceDefinitionValidator.validate(definition).valid)
    }
}
