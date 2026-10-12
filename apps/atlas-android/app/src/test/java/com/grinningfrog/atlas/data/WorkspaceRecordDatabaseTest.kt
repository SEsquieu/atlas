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
class WorkspaceRecordDatabaseTest {
    private lateinit var db: AtlasDatabase
    @Before fun setup() { RuntimeEnvironment.getApplication().deleteDatabase("atlas.db"); db = AtlasDatabase(RuntimeEnvironment.getApplication()); db.writableDatabase }
    @After fun cleanup() { db.close() }

    @Test fun conversationalPatchAndUiTogglePersistOnlySelectedRecordAfterReopen() = runTest {
        val app = RecordEditingFixture.definition()
        val session = db.createSession("Edit", "Test records")
        val workspace = db.createComposableWorkspace("Records", "", app.toString(), session.workspaceId)
        val sequence = db.activateComposableWorkspace(session.id, workspace.id)
        val a = db.addWorkspaceRecord(workspace.id, "entries", RecordEditingFixture.data("Same name").toString())
        val b = db.addWorkspaceRecord(workspace.id, "entries", RecordEditingFixture.data("Same name").toString())
        val adapter = WorkspaceTools.create(db).single { it.definition.name == "workspace_update_record" }
        val args = JSONObject().put("workspace_id", workspace.id).put("base_revision_id", workspace.liveRevisionId)
            .put("navigation_sequence", sequence).put("collection", "entries").put("record_id", b.id)
            .put("expected_data", JSONObject(b.dataJson)).put("data", JSONObject().put("done", true))
        val current = db.loadSession(session.id)!!
        assertTrue(adapter.evaluate(current, ToolCallProposal("edit", adapter.definition.name, args.toString())).allowed)
        val call = AtlasToolCall("edit", session.id, "turn", adapter.definition.name, args.toString(), ToolCallStatus.PROPOSED, ToolRisk.SESSION_WRITE, false, "edit", createdAtMs = 0, updatedAtMs = 0)
        assertTrue(JSONObject(adapter.execute(call).resultJson).getJSONObject("data").getBoolean("done"))
        assertTrue(runCatching { adapter.execute(call) }.isFailure) // Stale replay must not silently overwrite.
        assertFalse(JSONObject(db.listWorkspaceRecords(workspace.id, "entries").single { it.id == a.id }.dataJson).getBoolean("done"))
        assertTrue(db.loadRecentEvents(session.id).any { it.type == "workspace.action_applied" })
        db.close(); db = AtlasDatabase(RuntimeEnvironment.getApplication())
        val saved = db.listWorkspaceRecords(workspace.id, "entries").single { it.id == b.id }
        assertTrue(JSONObject(saved.dataJson).getBoolean("done"))
        db.applyWorkspaceAction(workspace.id, app, "toggleEntry", rowCollection = "entries", rowId = b.id, expectedData = JSONObject(saved.dataJson))
        assertFalse(JSONObject(db.listWorkspaceRecords(workspace.id, "entries").single { it.id == b.id }.dataJson).getBoolean("done"))
        db.close(); db = AtlasDatabase(RuntimeEnvironment.getApplication())
        assertTrue(db.listWorkspaceRecords(workspace.id, "entries").all { !JSONObject(it.dataJson).getBoolean("done") })
        db.activateComposableWorkspace(session.id, null)
        val reopenedAdapter = WorkspaceTools.create(db).single { it.definition.name == "workspace_update_record" }
        assertFalse(reopenedAdapter.evaluate(db.loadSession(session.id)!!, ToolCallProposal("edit", reopenedAdapter.definition.name, args.toString())).allowed)
        assertTrue(runCatching { reopenedAdapter.execute(call) }.isFailure)
    }

    @Test fun failedLaterStepAndInvariantRollbackEntireRecordAction() {
        val app = RecordEditingFixture.definition()
        app.getJSONObject("actions").put("fail", JSONObject().put("steps", JSONArray()
            .put(JSONObject().put("type", "toggle_record").put("collection", "entries").put("record_id", JSONObject().put("var", "row.id")).put("field", "done"))
            .put(JSONObject().put("type", "update_record").put("collection", "entries").put("record_id", "missing").put("data", JSONObject().put("done", true)))))
        val workspace = db.createComposableWorkspace("Records", "", app.toString())
        val row = db.addWorkspaceRecord(workspace.id, "entries", RecordEditingFixture.data("First").toString())
        assertThrows(WorkspaceRuntimeException::class.java) { db.applyWorkspaceAction(workspace.id, app, "fail", rowCollection = "entries", rowId = row.id) }
        assertFalse(JSONObject(db.listWorkspaceRecords(workspace.id, "entries").single().dataJson).getBoolean("done"))
        assertNull(db.loadWorkspacePersistedState(workspace.id))
        val guarded = RecordEditingFixture.definition().put("invariants", JSONArray().put(JSONObject().put("assert", false).put("message", "Reject mutation")))
        val other = db.createComposableWorkspace("Guarded", "", guarded.toString())
        val otherRow = db.addWorkspaceRecord(other.id, "entries", RecordEditingFixture.data("Second").toString())
        assertThrows(WorkspaceRuntimeException::class.java) { db.updateWorkspaceRecord(other.id, other.liveRevisionId, "entries", otherRow.id, JSONObject().put("done", true), JSONObject(otherRow.dataJson)) }
        assertFalse(JSONObject(db.listWorkspaceRecords(other.id, "entries").single().dataJson).getBoolean("done"))
    }

    @Test fun staleRevisionForeignRecordBadInsertionAndStaleUiValueAreRejected() {
        val app = RecordEditingFixture.definition()
        val workspace = db.createComposableWorkspace("Records", "", app.toString())
        val other = db.createComposableWorkspace("Other", "", app.toString())
        val row = db.addWorkspaceRecord(workspace.id, "entries", RecordEditingFixture.data("First").toString())
        assertThrows(IllegalArgumentException::class.java) { db.addWorkspaceRecord(workspace.id, "entries", RecordEditingFixture.data("Invalid").put("done", "true").toString()) }
        assertThrows(IllegalStateException::class.java) { db.updateWorkspaceRecord(other.id, other.liveRevisionId, "entries", row.id, JSONObject().put("done", true), JSONObject(row.dataJson)) }
        db.updateWorkspaceRecord(workspace.id, workspace.liveRevisionId, "entries", row.id, JSONObject().put("done", true), JSONObject(row.dataJson))
        assertThrows(IllegalArgumentException::class.java) { db.applyWorkspaceAction(workspace.id, app, "toggleEntry", rowCollection = "entries", rowId = row.id, expectedData = JSONObject(row.dataJson)) }
        db.replaceWorkspaceDefinition(workspace.id, workspace.liveRevisionId, JSONObject(app.toString()).put("title", "New revision").toString(), "USER", null, null, "Edit")
        assertThrows(IllegalArgumentException::class.java) { db.applyWorkspaceAction(workspace.id, app, "toggleEntry", rowCollection = "entries", rowId = row.id) }
        assertThrows(IllegalArgumentException::class.java) { db.updateWorkspaceRecord(workspace.id, workspace.liveRevisionId, "entries", row.id, JSONObject().put("done", false), RecordEditingFixture.data("First", true)) }
    }
}
