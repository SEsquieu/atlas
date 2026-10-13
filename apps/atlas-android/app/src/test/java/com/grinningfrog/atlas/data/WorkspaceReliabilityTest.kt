package com.grinningfrog.atlas.data

import android.app.Application
import com.grinningfrog.atlas.model.*
import com.grinningfrog.atlas.workspace.*
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WorkspaceReliabilityTest {
    private lateinit var db: AtlasDatabase
    @Before fun setup() { RuntimeEnvironment.getApplication().deleteDatabase("atlas.db"); db = AtlasDatabase(RuntimeEnvironment.getApplication()); db.writableDatabase }
    @After fun cleanup() { db.close() }
    @Test fun togglesDoNotChangeDatabaseOrderEvenWithIdenticalCreationTimes() {
        val root = RecordEditingFixture.definition()
        val workspace = db.createComposableWorkspace("Order", "", root.toString())
        repeat(3) { db.addWorkspaceRecord(workspace.id, "entries", RecordEditingFixture.data("Row $it").toString(), 10) }
        val before = db.listWorkspaceRecords(workspace.id, "entries").map { it.id }
        db.applyWorkspaceAction(workspace.id, root, "toggleEntry", rowCollection = "entries", rowId = before[1])
        assertEquals(before, db.listWorkspaceRecords(workspace.id, "entries").map { it.id })
        db.close(); db = AtlasDatabase(RuntimeEnvironment.getApplication())
        assertEquals(before, db.listWorkspaceRecords(workspace.id, "entries").map { it.id })
    }
    @Test fun requiredFieldDefaultsBackfillLiveRecordsAtomically() {
        val root = RecordEditingFixture.definition()
        val workspace = db.createComposableWorkspace("Migration", "", root.toString())
        val a = db.addWorkspaceRecord(workspace.id, "entries", RecordEditingFixture.data("First").toString(), 10)
        val b = db.addWorkspaceRecord(workspace.id, "entries", RecordEditingFixture.data("Second").toString(), 20)
        val next = JSONObject(root.toString())
        next.getJSONObject("collections").getJSONObject("entries").getJSONObject("fields").put("rank", JSONObject().put("type", "integer").put("initial", 0))
        val preview = db.workspaceRecordMigration(workspace.id, next).second
        assertEquals(2, preview.getInt("records_checked")); assertEquals(2, preview.getInt("records_backfilled"))
        assertFalse(JSONObject(db.listWorkspaceRecords(workspace.id, "entries").first().dataJson).has("rank"))
        val revision = db.replaceWorkspaceDefinition(workspace.id, workspace.liveRevisionId, next.toString(), "USER", null, null, "Add rank")
        assertTrue(db.listWorkspaceRecords(workspace.id, "entries").all { JSONObject(it.dataJson).getInt("rank") == 0 })
        assertEquals(listOf(a.id, b.id), db.listWorkspaceRecords(workspace.id, "entries").map { it.id })
        db.applyWorkspaceAction(workspace.id, next, "toggleEntry", rowCollection = "entries", rowId = a.id)
        val incompatible = JSONObject(next.toString())
        incompatible.getJSONObject("collections").getJSONObject("entries").getJSONObject("fields").put("missing", JSONObject().put("type", "string"))
        assertThrows(IllegalArgumentException::class.java) { db.replaceWorkspaceDefinition(workspace.id, revision.id, incompatible.toString(), "USER", null, null, "Bad field") }
        assertEquals(revision.id, db.loadLiveWorkspaceRevision(workspace.id)!!.id)
        assertFalse(JSONObject(db.listWorkspaceRecords(workspace.id, "entries").first().dataJson).has("missing"))
    }
    @Test fun previewChecksActualRecordsRatherThanOnlyPreparedTestFixtures() = runTest {
        val session = db.createSession("Preview", "test")
        val root = RecordEditingFixture.definition()
        val workspace = db.createComposableWorkspace("Records", "", root.toString(), session.workspaceId)
        db.activateComposableWorkspace(session.id, workspace.id)
        db.addWorkspaceRecord(workspace.id, "entries", RecordEditingFixture.data("Existing").toString())
        val next = JSONObject(root.toString())
        next.getJSONObject("collections").getJSONObject("entries").getJSONObject("fields").put("rank", JSONObject().put("type", "integer"))
        val adapter = WorkspaceTools.create(db).single { it.definition.name == "workspace_validate_definition" }
        val args = JSONObject().put("workspace_id", workspace.id).put("definition", next)
        val call = AtlasToolCall("preview", session.id, "turn", adapter.definition.name, args.toString(), ToolCallStatus.PROPOSED, ToolRisk.READ_ONLY, false, "preview", createdAtMs = 0, updatedAtMs = 0)
        val result = JSONObject(adapter.execute(call).resultJson)
        assertTrue(result.getBoolean("valid")); assertFalse(result.getBoolean("ready_to_apply"))
        assertTrue(result.getJSONArray("record_migration_errors").getString(0).contains("rank"))
    }
    @Test fun deletionAndUndoPreserveIdentityOrderAndSurviveRestart() {
        val root = RecordEditingFixture.definition()
        val workspace = db.createComposableWorkspace("Undo", "", root.toString())
        val rows = (0..2).map { db.addWorkspaceRecord(workspace.id, "entries", RecordEditingFixture.data("Row $it").toString(), 10) }
        val before = db.listWorkspaceRecords(workspace.id, "entries").map { it.id }
        val selected = rows[1]
        val deletion = db.deleteWorkspaceRecord(workspace.id, workspace.liveRevisionId, "entries", selected.id, JSONObject(selected.dataJson))
        assertEquals(before.filter { it != selected.id }, db.listWorkspaceRecords(workspace.id, "entries").map { it.id })
        val token = deletion.undoTokens.single()
        db.close(); db = AtlasDatabase(RuntimeEnvironment.getApplication())
        db.restoreWorkspaceRecord(workspace.id, workspace.liveRevisionId, token)
        assertEquals(before, db.listWorkspaceRecords(workspace.id, "entries").map { it.id })
        val restored = db.listWorkspaceRecords(workspace.id, "entries").single { it.id == selected.id }
        assertEquals(selected.createdAtMs, restored.createdAtMs); assertEquals(selected.dataJson, restored.dataJson)
        assertThrows(IllegalStateException::class.java) { db.restoreWorkspaceRecord(workspace.id, workspace.liveRevisionId, token) }
    }
    @Test fun deleteAndUndoRespectInvariantsStaleValuesAndWorkspaceBoundaries() {
        val root = RecordEditingFixture.definition().put("invariants", JSONArray().put(JSONObject().put("assert", JSONObject().put("op", "gte").put("args", JSONArray().put(JSONObject().put("var", "count.entries")).put(1))).put("message", "Keep one")))
        val workspace = db.createComposableWorkspace("Guarded", "", root.toString())
        val row = db.addWorkspaceRecord(workspace.id, "entries", RecordEditingFixture.data("Only").toString())
        assertThrows(WorkspaceRuntimeException::class.java) { db.deleteWorkspaceRecord(workspace.id, workspace.liveRevisionId, "entries", row.id, JSONObject(row.dataJson)) }
        assertEquals(0, db.pendingWorkspaceUndo(workspace.id).length()); assertEquals(1, db.listWorkspaceRecords(workspace.id, "entries").size)
        assertThrows(IllegalArgumentException::class.java) { db.deleteWorkspaceRecord(workspace.id, workspace.liveRevisionId, "entries", row.id, RecordEditingFixture.data("Changed")) }
        val other = db.createComposableWorkspace("Other", "", RecordEditingFixture.definition().toString())
        assertThrows(IllegalStateException::class.java) { db.deleteWorkspaceRecord(other.id, other.liveRevisionId, "entries", row.id, JSONObject(row.dataJson)) }
    }
}
