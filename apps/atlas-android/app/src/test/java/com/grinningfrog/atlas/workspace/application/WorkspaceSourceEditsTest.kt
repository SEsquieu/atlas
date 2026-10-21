package com.grinningfrog.atlas.workspace.application

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WorkspaceSourceEditsTest {
    private fun edit(source: String, find: String, replace: String) = JSONObject().put("path", "ui/app.js")
        .put("expected_sha256", WorkspaceApplicationContract.hash(source)).put("find", find).put("replace", replace)

    @Test fun exactEditPreservesUnchangedSourceAndOriginalInput() {
        val source = "before UNIQUE after"
        val files = JSONObject().put("ui/app.js", source)
        val result = WorkspaceSourceEdits.changes(files, JSONObject(), JSONArray().put(edit(source, "UNIQUE", "changed")))
        assertEquals("before changed after", result.getString("ui/app.js"))
        assertEquals(source, files.getString("ui/app.js"))
    }

    @Test fun ambiguousMissingStaleAndDuplicateEditsAreRejected() {
        val files = JSONObject().put("ui/app.js", "x x")
        for (edits in listOf(
            JSONArray().put(edit("x x", "x", "y")),
            JSONArray().put(edit("x x", "absent", "y")),
            JSONArray().put(edit("old", "x x", "y")),
            JSONArray().put(edit("x x", "x x", "y")).put(edit("x x", "x x", "z")),
        )) assertTrue(runCatching { WorkspaceSourceEdits.changes(files, JSONObject(), edits) }.isFailure)
        assertTrue(runCatching { WorkspaceSourceEdits.changes(files, JSONObject().put("ui/app.js", "other"), JSONArray().put(edit("x x", "x x", "y"))) }.isFailure)
        assertEquals("x x", files.getString("ui/app.js"))
    }
}
