package com.grinningfrog.atlas.workspace.application

import android.app.Application
import com.grinningfrog.atlas.data.AtlasDatabase
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
class WorkspaceApplicationDataTest {
    private lateinit var db: AtlasDatabase
    private lateinit var store: WorkspaceApplicationData
    @Before fun setup() {
        RuntimeEnvironment.getApplication().deleteDatabase("atlas.db")
        db = AtlasDatabase(RuntimeEnvironment.getApplication()); store = WorkspaceApplicationData(db)
    }
    @After fun cleanup() { db.close() }
    // Test-only installation. Production activation remains gated; no public bypass is added.
    private fun installed(): WorkspacePrincipal {
        val workspace = db.createComposableWorkspace("Application", "")
        db.writableDatabase.execSQL("UPDATE workspace_revisions SET definition_json=? WHERE revision_id=?",
            arrayOf(PackageFixture.bundle().toString(), workspace.liveRevisionId))
        db.writableDatabase.execSQL("INSERT INTO workspace_application_lifecycle(workspace_id,epoch,enabled) VALUES(?,1,1)", arrayOf(workspace.id))
        return WorkspacePrincipal(workspace.realmId, workspace.id, workspace.liveRevisionId, "test-instance", 1, WorkspaceInstanceMode.FOREGROUND)
    }
    private fun create(text: String) = JSONObject().put("kind", "create").put("collection", "notes").put("data", JSONObject().put("text", text))
    private fun prepare(principal: WorkspacePrincipal, vararg mutations: JSONObject): String = store.prepare(principal,
        JSONObject().put("expected_data_version", store.version(principal.workspaceId)).put("mutations", JSONArray(mutations.toList()))).getString("operation_id")
    @Test fun requestKeysRecoverPrepareAndCancelWithoutReplayingWrites() {
        val p = installed()
        val request = JSONObject().put("expected_data_version", 0).put("request_key", "stable-request").put("mutations", JSONArray().put(create("Once")))
        val first = store.prepare(p, request)
        assertEquals(first.getString("operation_id"), store.prepare(p, request).getString("operation_id"))
        store.commit(p, first.getString("operation_id"))
        assertEquals("succeeded", store.prepare(p, request).getString("status"))
        assertEquals(1, db.listWorkspaceRecords(p.workspaceId, "notes").size)
        val changed = JSONObject(request.toString()).put("mutations", JSONArray().put(create("Other")))
        assertThrows(IllegalArgumentException::class.java) { store.prepare(p, changed) }
        val cancelled = prepare(p, create("Never"))
        assertEquals("cancelled", store.cancel(p, cancelled).getString("status"))
        assertEquals("cancelled", store.commit(p, cancelled).getString("status"))
        assertEquals(1, db.listWorkspaceRecords(p.workspaceId, "notes").size)
    }
    @Test fun registryUsesBoundPrincipalAndNeverInstallsPreviewStorage() {
        val p = installed()
        val result = WorkspaceHostRegistry.dispatch(p, "data.query", JSONObject().put("collection", "notes"), store)
        assertEquals(0, result.getJSONArray("records").length())
        assertThrows(IllegalArgumentException::class.java) { WorkspaceHostRegistry.dispatch(p.copy(mode = WorkspaceInstanceMode.PREVIEW), "data.query", JSONObject().put("collection", "notes"), store) }
        assertThrows(IllegalArgumentException::class.java) { WorkspaceHostRegistry.dispatch(p, "data.query", JSONObject().put("collection", "notes").put("workspace_id", "other"), store) }
        assertFalse(WorkspaceHostRegistry.validate("device.ble.connect", JSONObject(), true))
        assertFalse(WorkspaceHostRegistry.describe(p.copy(mode = WorkspaceInstanceMode.PREVIEW)).getJSONObject("methods").has("data.query"))
    }
    @Test fun commitRetryAndReopenPreserveExactlyOneWriteAndReceipt() {
        val p = installed(); val id = prepare(p, create("Keep"))
        assertEquals("prepared", store.inspect(p, id).getString("status"))
        val first = store.commit(p, id)
        assertEquals(1L, first.getLong("data_version"))
        db.close(); db = AtlasDatabase(RuntimeEnvironment.getApplication()); store = WorkspaceApplicationData(db)
        assertEquals(first.toString(), store.commit(p, id).toString())
        assertEquals(first.toString(), store.inspect(p, id).toString())
        assertEquals(1, store.query(p, JSONObject().put("collection", "notes")).getJSONArray("records").length())
    }
    @Test fun updateDeleteAndVersionedPaginationUseTheSameStore() {
        val p = installed()
        val receipt = store.commit(p, prepare(p, create("First"), create("Second")))
        val id = receipt.getJSONArray("record_ids").getString(0)
        val page = store.query(p, JSONObject().put("collection", "notes").put("limit", 1).put("expected_data_version", 2))
        assertEquals(1, page.getJSONArray("records").length())
        assertEquals(1, page.getInt("next_offset"))
        val update = JSONObject().put("kind", "update").put("collection", "notes").put("record_id", id).put("data", JSONObject().put("text", "Updated"))
        store.commit(p, prepare(p, update))
        assertEquals("Updated", db.listWorkspaceRecords(p.workspaceId, "notes").single { it.id == id }.let { JSONObject(it.dataJson).getString("text") })
        assertThrows(IllegalArgumentException::class.java) { store.query(p, JSONObject().put("collection", "notes").put("expected_data_version", 2)) }
        val deletion = JSONObject().put("kind", "delete").put("collection", "notes").put("record_id", id)
        store.commit(p, prepare(p, deletion))
        assertEquals(4L, store.version(p.workspaceId))
        assertEquals(1, db.listWorkspaceRecords(p.workspaceId, "notes").size)
    }
    @Test fun failedBatchRollsBackRecordsVersionAndReceipt() {
        val p = installed()
        val missing = JSONObject().put("kind", "delete").put("collection", "notes").put("record_id", "missing")
        val id = prepare(p, create("Rollback"), missing)
        assertThrows(IllegalArgumentException::class.java) { store.commit(p, id) }
        assertEquals(0L, store.version(p.workspaceId))
        assertTrue(db.listWorkspaceRecords(p.workspaceId, "notes").isEmpty())
        assertEquals("prepared", store.inspect(p, id).getString("status"))
    }
    @Test fun concurrentNativeWriteInvalidatesPreparedBatch() {
        val p = installed(); val id = prepare(p, create("Stale"))
        db.addWorkspaceRecord(p.workspaceId, "notes", "{\"text\":\"External\"}")
        assertThrows(IllegalArgumentException::class.java) { store.commit(p, id) }
        assertEquals(1, db.listWorkspaceRecords(p.workspaceId, "notes").size)
    }
    @Test fun receiptsCannotCrossWorkspacesEpochsModesOrRealms() {
        val p = installed(); val other = installed(); val id = prepare(p, create("Private"))
        for (forged in listOf(other, p.copy(epoch = 2), p.copy(realmId = "wrong"), p.copy(mode = WorkspaceInstanceMode.TEST))) {
            assertThrows(IllegalArgumentException::class.java) { store.commit(forged, id) }
            assertThrows(IllegalArgumentException::class.java) { store.inspect(forged, id) }
        }
        db.setComposableWorkspaceArchived(p.workspaceId, true)
        assertThrows(IllegalArgumentException::class.java) { store.commit(p, id) }
    }
    @Test fun numericParametersRejectCoercionAndFractionalValues() {
        val p = installed()
        for (bad in listOf<Any>("1", 1.5, -1, true)) {
            assertThrows(IllegalArgumentException::class.java) { store.query(p, JSONObject().put("collection", "notes").put("offset", bad)) }
            assertThrows(IllegalArgumentException::class.java) { store.prepare(p, JSONObject().put("expected_data_version", bad).put("mutations", JSONArray().put(create("Bad")))) }
        }
    }
    @Test fun deletionRemovesReceiptsAndTriggerCreatedVersionRows() {
        val p = installed(); store.commit(p, prepare(p, create("Delete")))
        db.setComposableWorkspaceArchived(p.workspaceId, true)
        db.deleteComposableWorkspace(p.workspaceId)
        for (table in listOf("workspace_application_operations", "workspace_application_lifecycle", "workspace_data_versions")) {
            db.readableDatabase.rawQuery("SELECT COUNT(*) FROM $table WHERE workspace_id=?", arrayOf(p.workspaceId)).use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        }
    }
    @Test fun upgradeFromVersion15PreservesExistingRecordsAndTracksFutureWrites() {
        val p = installed(); db.addWorkspaceRecord(p.workspaceId, "notes", "{\"text\":\"Old\"}")
        for (event in listOf("insert", "update", "delete")) db.writableDatabase.execSQL("DROP TRIGGER workspace_data_$event")
        for (table in listOf("workspace_application_checks", "workspace_application_operations", "workspace_application_lifecycle", "workspace_data_versions")) db.writableDatabase.execSQL("DROP TABLE $table")
        db.writableDatabase.version = 15; db.close()
        db = AtlasDatabase(RuntimeEnvironment.getApplication()); store = WorkspaceApplicationData(db)
        assertEquals(1, db.listWorkspaceRecords(p.workspaceId, "notes").size)
        assertEquals(0L, store.version(p.workspaceId))
        db.addWorkspaceRecord(p.workspaceId, "notes", "{\"text\":\"New\"}")
        assertEquals(1L, store.version(p.workspaceId))
    }
}
