package com.grinningfrog.atlas.workspace.application

import android.app.Application
import com.grinningfrog.atlas.data.AtlasDatabase
import com.grinningfrog.atlas.data.ArchiveCodec
import org.json.JSONObject
import org.json.JSONArray
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WorkspacePackageArchiveTest {
    @Test fun packageRoundTripKeepsLogicalRecordReferencesAndStartsWithoutAuthority() {
        val context = RuntimeEnvironment.getApplication(); context.deleteDatabase("atlas.db")
        val db = AtlasDatabase(context)
        try {
            val original = db.createComposableWorkspace("Linked records", "", WorkspaceApplicationContract.example().toString())
            val store = WorkspaceApplicationData(db); store.setEnabled(original.id, original.liveRevisionId, true)
            val p = WorkspacePrincipal(original.realmId, original.id, original.liveRevisionId, "original", store.epoch(original.id), WorkspaceInstanceMode.FOREGROUND)
            val first = store.commit(p, store.prepare(p, JSONObject().put("expected_data_version", 0).put("mutations", JSONArray().put(JSONObject().put("kind", "create").put("collection", "notes").put("data", JSONObject().put("text", "Parent"))))).getString("operation_id"))
            val logical = first.getJSONArray("record_ids").getString(0)
            store.commit(p, store.prepare(p, JSONObject().put("expected_data_version", 1).put("mutations", JSONArray().put(JSONObject().put("kind", "create").put("collection", "notes").put("data", JSONObject().put("parent_id", logical))))).getString("operation_id"))
            val archive = db.exportComposableWorkspace(original.id)
            val imported = db.importArchive(ArchiveCodec.preview(archive))
            assertNotEquals(original.id, imported.id)
            assertEquals(-1L, store.epoch(imported.id))
            val restored = db.loadComposableWorkspace(imported.id)!!; store.setEnabled(restored.id, restored.liveRevisionId, true)
            val next = WorkspacePrincipal(restored.realmId, restored.id, restored.liveRevisionId, "restored", store.epoch(restored.id), WorkspaceInstanceMode.FOREGROUND)
            val records = store.query(next, JSONObject().put("collection", "notes")).getJSONArray("records")
            assertEquals(2, records.length())
            val ids = (0 until records.length()).map { records.getJSONObject(it).getString("id") }
            assertTrue(ids.contains(logical))
            assertTrue((0 until records.length()).any { records.getJSONObject(it).getJSONObject("data").optString("parent_id") == logical })
            assertEquals(2, store.query(p, JSONObject().put("collection", "notes")).getJSONArray("records").length())
        } finally { db.close() }
    }
}
