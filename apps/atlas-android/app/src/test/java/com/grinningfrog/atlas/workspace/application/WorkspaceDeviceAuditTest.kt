package com.grinningfrog.atlas.workspace.application

import android.app.Application
import com.grinningfrog.atlas.data.AtlasDatabase
import com.grinningfrog.atlas.data.ArchiveCodec
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class WorkspaceDeviceAuditTest {
    private lateinit var db:AtlasDatabase
    @Before fun setup() { RuntimeEnvironment.getApplication().deleteDatabase("atlas.db");db=AtlasDatabase(RuntimeEnvironment.getApplication()) }
    @After fun cleanup() { db.close() }
    @Test fun auditPersistsWithoutLiveSessionIsBoundedAndExportsWithoutAuthority() {
        val workspace=db.createComposableWorkspace("Device", "", WorkspaceApplicationContract.example().toString())
        val store=WorkspaceDeviceAudit(db)
        repeat(520) { store.append(workspace.id,JSONObject().put("at_ms",it).put("kind","grant_reused").put("revision_id",workspace.liveRevisionId)) }
        db.close();db=AtlasDatabase(RuntimeEnvironment.getApplication())
        val exported=db.exportComposableWorkspace(workspace.id)
        val events=exported.getJSONObject("device_audit").getJSONArray("events")
        assertEquals(512,events.length());assertEquals(8,events.getJSONObject(0).getInt("at_ms"))
        val imported=db.importArchive(ArchiveCodec.preview(exported))
        assertEquals(0,db.exportComposableWorkspace(imported.id).getJSONObject("device_audit").getJSONArray("events").length())
        db.deleteComposableWorkspace(workspace.id)
        WorkspaceDeviceAudit(db).append(workspace.id,JSONObject().put("at_ms",600).put("kind","disposed"))
        assertEquals(0,WorkspaceDeviceAudit(db).snapshot(workspace.id).getJSONArray("events").length())
    }
}
