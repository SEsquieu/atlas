package com.grinningfrog.atlas.workspace.application

import android.app.Application
import com.grinningfrog.atlas.data.AtlasDatabase
import com.grinningfrog.atlas.model.*
import com.grinningfrog.atlas.workspace.WorkspaceTools
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WorkspacePackageRepositoryTest {
    private lateinit var db: AtlasDatabase
    @Before fun setup() { RuntimeEnvironment.getApplication().deleteDatabase("atlas.db"); db = AtlasDatabase(RuntimeEnvironment.getApplication()); db.writableDatabase }
    @After fun cleanup() { db.close() }
    private fun stage(id: String, realm: String, base: String, session: String, turn: String, parent: String? = null, changes: JSONObject = PackageFixture.bundle().getJSONObject("files")) =
        WorkspacePackageRepository(db).stage(id, realm, base, parent, if (parent == null) PackageFixture.bundle().getJSONObject("manifest") else null, changes, session, turn)

    @Test fun immutableBranchingPreservesLiveRevisionDataAndReopens() {
        val session = db.createSession("Staging", "Test")
        val turn = db.createTurn(session.id, "user")
        val workspace = db.createComposableWorkspace("Keep", "", realmId = session.workspaceId)
        val record = db.addWorkspaceRecord(workspace.id, "notes", """{"text":"Keep this"}""")
        val first = stage(workspace.id, session.workspaceId, workspace.liveRevisionId, session.id, turn.id)
        val next = stage(workspace.id, session.workspaceId, workspace.liveRevisionId, session.id, turn.id, first.getString("candidate_id"), JSONObject().put("ui/app.js", "document.body.textContent='Next';"))
        assertNotEquals(first.getString("candidate_id"), next.getString("candidate_id"))
        assertNotEquals(first.getString("content_hash"), next.getString("content_hash"))
        assertEquals(workspace.liveRevisionId, db.loadComposableWorkspace(workspace.id)!!.liveRevisionId)
        assertEquals(record.dataJson, db.listWorkspaceRecords(workspace.id, "notes").single().dataJson)
        db.close(); db = AtlasDatabase(RuntimeEnvironment.getApplication())
        val stored = WorkspacePackageRepository(db).inspect(workspace.id, session.workspaceId, first.getString("candidate_id"))
        assertEquals(PackageFixture.bundle().getJSONObject("files").getString("ui/app.js"), stored.getJSONObject("bundle").getJSONObject("files").getString("ui/app.js"))
        assertFalse(stored.getBoolean("executed"))
    }
    @Test fun invalidBatchRollsBackAndCandidatesAreScopedToWorkspace() {
        val session = db.createSession("Staging", "Test"); val turn = db.createTurn(session.id, "user")
        val workspace = db.createComposableWorkspace("First", "", realmId = session.workspaceId)
        val other = db.createComposableWorkspace("Other", "", realmId = session.workspaceId)
        val first = stage(workspace.id, session.workspaceId, workspace.liveRevisionId, session.id, turn.id)
        assertThrows(IllegalArgumentException::class.java) { stage(workspace.id, session.workspaceId, workspace.liveRevisionId, session.id, turn.id,
            first.getString("candidate_id"), JSONObject().put("ui/app.js", "new").put("../outside.js", "bad")) }
        assertEquals(1, WorkspacePackageRepository(db).inspect(workspace.id, session.workspaceId).getJSONArray("candidates").length())
        assertThrows(IllegalArgumentException::class.java) { WorkspacePackageRepository(db).inspect(other.id, session.workspaceId, first.getString("candidate_id")) }
        assertThrows(IllegalArgumentException::class.java) { WorkspacePackageRepository(db).inspect(workspace.id, "different-realm") }
        assertThrows(IllegalArgumentException::class.java) { stage(workspace.id, session.workspaceId, "stale", session.id, turn.id) }
    }
    @Test fun archiveRejectsStagingAndDeleteRemovesItsRows() {
        val session = db.createSession("Staging", "Test"); val turn = db.createTurn(session.id, "user")
        val workspace = db.createComposableWorkspace("Keep", "", realmId = session.workspaceId)
        val first = stage(workspace.id, session.workspaceId, workspace.liveRevisionId, session.id, turn.id)
        stage(workspace.id, session.workspaceId, workspace.liveRevisionId, session.id, turn.id, first.getString("candidate_id"), JSONObject().put("ui/app.js", "new"))
        db.setComposableWorkspaceArchived(workspace.id, true)
        assertThrows(IllegalArgumentException::class.java) { stage(workspace.id, session.workspaceId, workspace.liveRevisionId, session.id, turn.id) }
        db.deleteComposableWorkspace(workspace.id)
        db.readableDatabase.rawQuery("SELECT COUNT(*) FROM workspace_package_candidates", null).use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
    }
    @Test fun stagingToolRechecksTargetAndDoesNotNavigateOrActivate() = runTest {
        val session = db.createSession("Staging", "Test"); val turn = db.createTurn(session.id, "user")
        val first = db.createComposableWorkspace("First", "", realmId = session.workspaceId)
        val other = db.createComposableWorkspace("Other", "", realmId = session.workspaceId)
        val sequence = db.activateComposableWorkspace(session.id, first.id)
        val adapter = WorkspaceTools.create(db).single { it.definition.name == "workspace_package_stage" }
        val bundle = PackageFixture.bundle()
        val args = JSONObject().put("workspace_id", first.id).put("base_revision_id", first.liveRevisionId).put("navigation_sequence", sequence)
            .put("manifest", bundle.getJSONObject("manifest")).put("files", bundle.getJSONObject("files"))
        val call = AtlasToolCall("stage", session.id, turn.id, adapter.definition.name, args.toString(), ToolCallStatus.PROPOSED, ToolRisk.SESSION_WRITE, false, "stage", createdAtMs = 0, updatedAtMs = 0)
        val result = JSONObject(adapter.execute(call).resultJson)
        assertFalse(result.getBoolean("activated")); assertEquals(0, result.getInt("tests_run"))
        db.activateComposableWorkspace(session.id, other.id)
        assertFalse(adapter.evaluate(db.loadSession(session.id)!!, ToolCallProposal("stage", adapter.definition.name, args.toString())).allowed)
        assertTrue(runCatching { adapter.execute(call) }.isFailure)
        assertEquals(other.id, db.loadSession(session.id)!!.activeComposableWorkspaceId)
    }
    @Test fun upgradeFromAlpha17PreservesNativeData() {
        val workspace = db.createComposableWorkspace("Native", "")
        val record = db.addWorkspaceRecord(workspace.id, "notes", """{"text":"Before upgrade"}""")
        db.writableDatabase.execSQL("DROP TABLE workspace_package_candidates")
        db.writableDatabase.version = 14
        db.close(); db = AtlasDatabase(RuntimeEnvironment.getApplication()); db.writableDatabase
        assertEquals(workspace.liveRevisionId, db.loadComposableWorkspace(workspace.id)!!.liveRevisionId)
        assertEquals(record.dataJson, db.listWorkspaceRecords(workspace.id, "notes").single().dataJson)
        assertEquals(0, WorkspacePackageRepository(db).inspect(workspace.id, workspace.realmId).getJSONArray("candidates").length())
    }
}
