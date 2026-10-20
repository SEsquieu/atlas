package com.grinningfrog.atlas.workspace

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WorkspaceOrderingTest {
    private fun rows() = listOf(
        JSONObject().put("_id", "a").put("name", "Zed").put("rank", 10),
        JSONObject().put("_id", "b").put("name", "Alpha").put("rank", 2),
        JSONObject().put("_id", "c").put("name", "alpha").put("rank", 2),
        JSONObject().put("_id", "d").put("name", "Missing"),
    )
    private fun ids(rows: List<JSONObject>) = rows.map { it.getString("_id") }
    @Test fun numericSortIsStableAndAppliesBeforeLimit() {
        val list = JSONObject().put("sort_by", "rank").put("limit", 3)
        assertEquals(listOf("b", "c", "a"), ids(WorkspaceRecordProjection.project(list, rows())))
        list.put("sort_direction", "descending")
        assertEquals(listOf("a", "b", "c"), ids(WorkspaceRecordProjection.project(list, rows())))
        list.put("limit", 4)
        assertEquals("d", ids(WorkspaceRecordProjection.project(list, rows())).last())
    }
    @Test fun nameSortIgnoresCaseAndCheckboxChangesDoNotMoveRows() {
        val list = JSONObject().put("sort_by", "name")
        val rows = rows()
        assertEquals(listOf("b", "c", "d", "a"), ids(WorkspaceRecordProjection.project(list, rows)))
        rows[2].put("done", true)
        assertEquals(listOf("b", "c", "d", "a"), ids(WorkspaceRecordProjection.project(list, rows)))
        assertEquals(listOf("a", "b", "c", "d"), ids(WorkspaceRecordProjection.project(JSONObject(), rows)))
    }
    @Test fun validatorRejectsIgnoredPropertiesAndInvalidSortFields() {
        val root = RecordEditingFixture.definition()
        val list = root.getJSONArray("views").getJSONObject(0).getJSONArray("components").getJSONObject(0)
        list.put("sort_by", "absent").put("sort_direction", "up").put("filter_by", "done")
        val errors = WorkspaceRuntimeV2.validate(root).errors
        assertTrue(errors.any { it.contains("unknown collection field") })
        assertTrue(errors.any { it.contains("ascending or descending") })
        assertTrue(errors.any { it.contains("unsupported property") && it.contains("filter_by") })
    }
    @Test fun removeStepSimulationPreservesOtherRowsAndSupportsUpdateThenRemove() {
        val root = RecordEditingFixture.definition()
        root.getJSONObject("actions").put("remove", JSONObject().put("steps", JSONArray()
            .put(JSONObject().put("type", "toggle_record").put("collection", "entries").put("record_id", "a").put("field", "done"))
            .put(JSONObject().put("type", "remove_record").put("collection", "entries").put("record_id", "a"))))
        root.put("tests", JSONArray().put(JSONObject().put("name", "remove one").put("action", "remove")
            .put("initial_records", JSONObject().put("entries", JSONArray().put(RecordEditingFixture.data("A").put("_id", "a")).put(RecordEditingFixture.data("B").put("_id", "b"))))
            .put("assert", JSONObject().put("op", "eq").put("args", JSONArray().put(JSONObject().put("var", "count.entries")).put(1)))))
        assertTrue(WorkspaceRuntimeV2.simulate(root).failures.joinToString(), WorkspaceRuntimeV2.simulate(root).passed)
    }
}
