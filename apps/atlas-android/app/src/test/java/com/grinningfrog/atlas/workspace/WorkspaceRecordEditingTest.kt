package com.grinningfrog.atlas.workspace

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Generic record fixture: identity must remain separate from editable user fields. */
object RecordEditingFixture {
    fun definition() = JSONObject("""{
        "format":"atlas.workspace.v2","title":"Records","entry_view":"main","state":{},
        "collections":{"entries":{"fields":{"name":{"type":"string"},"done":{"type":"boolean"}}}},
        "actions":{"toggleEntry":{"steps":[{"type":"toggle_record","collection":"entries","record_id":{"var":"row.id"},"field":"done"}]}},
        "views":[{"id":"main","components":[{"id":"entries","type":"list","collection":"entries","primary_field":"name","checked_field":"done","row_action":"toggleEntry"}]}]
    }""")
    fun data(name: String, done: Boolean = false) = JSONObject().put("name", name).put("done", done)
}

class WorkspaceRecordEditingTest {
    private fun records() = mutableMapOf("entries" to mutableListOf(
        RecordEditingFixture.data("Same name").put("_id", "a"),
        RecordEditingFixture.data("Same name").put("_id", "b"),
    ))

    @Test fun rowToggleTargetsStableIdentityAndIsPure() {
        val app = RecordEditingFixture.definition(); val records = records()
        val result = WorkspaceRuntimeV2.executeAction(app, "toggleEntry", WorkspaceRuntimeV2.initialState(app), records, "entries", "b")
        val mutation = result.mutations.filterIsInstance<WorkspaceMutation.UpdateRecord>().single()
        assertEquals("b", mutation.recordId)
        assertTrue(mutation.data.getBoolean("done"))
        assertEquals("Same name", mutation.data.getString("name"))
        assertFalse(mutation.data.has("_id"))
        assertFalse(records.getValue("entries")[0].getBoolean("done"))
        assertFalse(records.getValue("entries")[1].getBoolean("done"))
    }

    @Test fun patchPreservesOtherFieldsAndRejectsBadTypesAndIds() {
        val app = RecordEditingFixture.definition(); val state = WorkspaceRuntimeV2.initialState(app)
        val result = WorkspaceRuntimeV2.executeRecordUpdate(app, state, records(), "entries", "a", JSONObject().put("done", true))
        assertEquals("Same name", result.mutations.filterIsInstance<WorkspaceMutation.UpdateRecord>().single().data.getString("name"))
        for (patch in listOf(JSONObject().put("done", "true"), JSONObject().put("_id", "b"), JSONObject())) {
            assertThrows(IllegalArgumentException::class.java) { WorkspaceRuntimeV2.executeRecordUpdate(app, state, records(), "entries", "a", patch) }
        }
        assertThrows(WorkspaceRuntimeException::class.java) { WorkspaceRuntimeV2.executeRecordUpdate(app, state, records(), "entries", "missing", JSONObject().put("done", true)) }
        assertThrows(WorkspaceRuntimeException::class.java) { WorkspaceRuntimeV2.executeAction(app, "toggleEntry", state, records()) }
    }

    @Test fun embeddedSimulationExercisesRecordFixturesAndSequences() {
        val app = RecordEditingFixture.definition().put("tests", JSONArray().put(JSONObject()
            .put("name", "toggle selected record twice").put("actions", JSONArray().put("toggleEntry").put("toggleEntry"))
            .put("initial_records", JSONObject().put("entries", JSONArray(records().getValue("entries"))))
            .put("row_collection", "entries").put("row_id", "b")
            .put("assert", JSONObject().put("op", "eq").put("args", JSONArray().put(JSONObject().put("var", "row.done")).put(false)))))
        assertTrue(WorkspaceRuntimeV2.simulate(app).failures.joinToString(), WorkspaceRuntimeV2.simulate(app).passed)
        app.getJSONArray("tests").getJSONObject(0).put("actions", JSONArray().put("toggleEntry"))
        assertFalse(WorkspaceRuntimeV2.simulate(app).passed)
    }

    @Test fun validationRejectsInvalidRowBindingsAndActionFields() {
        val app = RecordEditingFixture.definition()
        val list = app.getJSONArray("views").getJSONObject(0).getJSONArray("components").getJSONObject(0)
        list.put("checked_field", "name").put("row_action", "missing")
        assertTrue(WorkspaceRuntimeV2.validate(app).errors.any { it.contains("must be boolean") })
        assertTrue(WorkspaceRuntimeV2.validate(app).errors.any { it.contains("unknown row_action") })
        app.getJSONObject("actions").getJSONObject("toggleEntry").getJSONArray("steps").getJSONObject(0).put("field", "name")
        assertTrue(WorkspaceRuntimeV2.validate(app).errors.any { it.contains("boolean collection field") })
    }
}
