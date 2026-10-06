package com.grinningfrog.atlas.workspace

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class WorkspaceRuntimeV2Test {
    @Test fun validatesAndRunsAStatefulApplication() {
        val app = dungeon()
        assertTrue(WorkspaceRuntimeV2.validate(app).errors.joinToString(), WorkspaceRuntimeV2.validate(app).valid)

        val initial = WorkspaceRuntimeV2.initialState(app)
        assertEquals("cell", initial.getString("location"))
        val result = WorkspaceRuntimeV2.executeAction(app, "openDoor", initial)
        assertEquals("hall", result.state.getString("location"))
        assertEquals(1L, result.state.getLong("turns"))
        assertEquals("hall", result.state.getString("_view"))
    }

    @Test fun simulationRunsEmbeddedAssertions() {
        val result = WorkspaceRuntimeV2.simulate(dungeon())
        assertTrue(result.failures.joinToString(), result.passed)
        assertEquals(1, result.testsRun)
    }

    @Test fun rejectsUnknownStateAndUnboundedLanguageFeatures() {
        val app = dungeon()
        app.getJSONObject("actions").put("bad", JSONObject().put("steps", JSONArray()
            .put(JSONObject().put("type", "set").put("key", "missing").put("value", true))
            .put(JSONObject().put("type", "execute_python"))))
        val result = WorkspaceRuntimeV2.validate(app)
        assertFalse(result.valid)
        assertTrue(result.errors.any { it.contains("unknown state") })
        assertTrue(result.errors.any { it.contains("unsupported type") })
    }

    @Test fun expressionsArePureAndBounded() {
        val state = JSONObject().put("health", 3).put("name", "Ada")
        val expression = JSONObject().put("op", "if").put("args", JSONArray()
            .put(JSONObject().put("op", "gt").put("args", JSONArray().put(JSONObject().put("var", "state.health")).put(0)))
            .put(JSONObject().put("op", "concat").put("args", JSONArray().put("Hello ").put(JSONObject().put("var", "state.name"))))
            .put("Game over"))
        assertEquals("Hello Ada", WorkspaceRuntimeV2.evaluate(expression, state))
    }

    @Test fun migrationHydratesNewFieldsAndDropsRemovedFields() {
        val app = dungeon()
        val persisted = JSONObject().put("location", "hall").put("legacy", 99).put("_view", "hall")
        val migration = WorkspaceRuntimeV2.migrateState(app, persisted)

        assertEquals("hall", migration.state.getString("location"))
        assertEquals(0L, migration.state.getLong("turns"))
        assertEquals("hall", migration.state.getString("_view"))
        assertTrue(migration.addedKeys.contains("turns"))
        assertTrue(migration.removedKeys.contains("legacy"))
        assertFalse(migration.state.has("legacy"))
    }

    @Test fun guardedActionsFailClosedWithoutMutatingState() {
        val app = dungeon()
        app.getJSONObject("actions").getJSONObject("openDoor")
            .put("enabled_if", JSONObject().put("op", "eq").put("args", JSONArray().put(JSONObject().put("var", "state.location")).put("hall")))
            .put("disabled_message", "The door is barred")
        val initial = WorkspaceRuntimeV2.initialState(app)

        val failure = assertThrows(WorkspaceActionRejected::class.java) {
            WorkspaceRuntimeV2.executeAction(app, "openDoor", initial)
        }
        assertEquals("The door is barred", failure.message)
        assertEquals("cell", initial.getString("location"))
    }

    @Test fun invariantsRejectTheEntireActionResult() {
        val app = dungeon().put("invariants", JSONArray().put(JSONObject()
            .put("name", "turn limit")
            .put("assert", JSONObject().put("op", "lte").put("args", JSONArray().put(JSONObject().put("var", "state.turns")).put(0)))
            .put("message", "Too many turns")))
        val records = mutableMapOf("journal" to mutableListOf<JSONObject>())

        val failure = assertThrows(WorkspaceRuntimeException::class.java) {
            WorkspaceRuntimeV2.executeAction(app, "openDoor", WorkspaceRuntimeV2.initialState(app), records)
        }
        assertEquals("Too many turns", failure.message)
        assertTrue(records.getValue("journal").isEmpty())
    }

    @Test fun divisionByZeroIsAControlledRuntimeFailure() {
        val divide = JSONObject().put("op", "divide").put("args", JSONArray().put(7).put(0))
        val failure = assertThrows(WorkspaceRuntimeException::class.java) {
            WorkspaceRuntimeV2.evaluate(divide, JSONObject())
        }
        assertEquals("Cannot divide by zero", failure.message)
    }

    @Test fun simulationCanExercisePersistedStateAndActionSequences() {
        val app = dungeon()
        app.put("tests", JSONArray().put(JSONObject().put("name", "migrated sequence")
            .put("initial_state", JSONObject().put("location", "cell"))
            .put("actions", JSONArray().put("openDoor"))
            .put("assert", JSONObject().put("op", "eq").put("args", JSONArray().put(JSONObject().put("var", "state.turns")).put(1)))))
        val persisted = JSONObject().put("location", "cell")

        val result = WorkspaceRuntimeV2.simulate(app, persisted)
        assertTrue(result.failures.joinToString(), result.passed)
    }

    @Test fun acceptsResponsiveRowsAndGrids() {
        val app = dungeon()
        app.getJSONArray("views").getJSONObject(0).getJSONArray("components")
            .put(JSONObject().put("id", "actions").put("type", "grid").put("columns", 3).put("children", JSONArray()
                .put(JSONObject().put("id", "one").put("type", "button").put("label", "One").put("action", "openDoor"))))
        assertTrue(WorkspaceRuntimeV2.validate(app).errors.joinToString(), WorkspaceRuntimeV2.validate(app).valid)
    }

    private fun dungeon() = JSONObject().apply {
        put("format", WorkspaceRuntimeV2.FORMAT); put("title", "The Cell"); put("entry_view", "cell")
        put("state", JSONObject()
            .put("location", JSONObject().put("type", "enum").put("values", JSONArray().put("cell").put("hall")).put("initial", "cell"))
            .put("turns", JSONObject().put("type", "integer").put("initial", 0)))
        put("collections", JSONObject().put("journal", JSONObject().put("fields", JSONObject()
            .put("text", JSONObject().put("type", "string")))))
        put("actions", JSONObject().put("openDoor", JSONObject().put("steps", JSONArray()
            .put(JSONObject().put("type", "set").put("key", "location").put("value", "hall"))
            .put(JSONObject().put("type", "increment").put("key", "turns").put("by", 1))
            .put(JSONObject().put("type", "insert").put("collection", "journal").put("data", JSONObject().put("text", "Opened the door")))
            .put(JSONObject().put("type", "navigate").put("view", "hall")))))
        put("views", JSONArray()
            .put(JSONObject().put("id", "cell").put("title", "Cell").put("components", JSONArray()
                .put(JSONObject().put("id", "scene").put("type", "text").put("text", "A locked cell."))
                .put(JSONObject().put("id", "door").put("type", "button").put("label", "Open door").put("action", "openDoor"))))
            .put(JSONObject().put("id", "hall").put("title", "Hall").put("components", JSONArray()
                .put(JSONObject().put("id", "turnCount").put("type", "metric").put("label", "Turns").put("value", JSONObject().put("var", "state.turns"))))))
        put("tests", JSONArray().put(JSONObject().put("name", "door opens").put("action", "openDoor")
            .put("assert", JSONObject().put("op", "eq").put("args", JSONArray().put(JSONObject().put("var", "state.location")).put("hall")))))
    }
}
