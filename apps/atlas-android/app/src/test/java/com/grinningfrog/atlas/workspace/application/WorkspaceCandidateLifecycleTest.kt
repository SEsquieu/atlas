package com.grinningfrog.atlas.workspace.application

import android.app.Application
import com.grinningfrog.atlas.data.AtlasDatabase
import kotlinx.coroutines.CancellationException
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
class WorkspaceCandidateLifecycleTest {
    private lateinit var db: AtlasDatabase
    @Before fun setup() { RuntimeEnvironment.getApplication().deleteDatabase("atlas.db"); db = AtlasDatabase(RuntimeEnvironment.getApplication()) }
    @After fun cleanup() { db.close() }
    private fun candidate(): JSONObject {
        val session = db.createSession("Packages", "Test"); val turn = db.createTurn(session.id, "user")
        val workspace = db.createComposableWorkspace("App", "", realmId = session.workspaceId)
        val bundle = PackageFixture.bundle()
        return WorkspacePackageRepository(db).stage(workspace.id, workspace.realmId, workspace.liveRevisionId, null,
            bundle.getJSONObject("manifest"), bundle.getJSONObject("files"), session.id, turn.id).put("realm_id", workspace.realmId)
    }
    private fun workerResult(bundle: JSONObject, export: String, input: Any?): JSONObject {
        assertEquals("total", export)
        assertEquals(PackageFixture.bundle().getJSONObject("files").getString("tools/total.js"), bundle.getJSONObject("files").getString("tools/total.js"))
        assertEquals("[120,230,-50]", WorkspaceApplicationContract.canonical(input))
        return JSONObject().put("ok", true).put("value", 300)
    }
    private suspend fun checks(c: JSONObject, runner: suspend (JSONObject, String, Any?) -> JSONObject = { b, e, i -> workerResult(b, e, i) }) =
        WorkspaceCandidateChecks(db, runner).test(c.getString("workspace_id"), c.getString("realm_id"), c.getString("candidate_id"))
    private suspend fun activate(c: JSONObject, check: JSONObject, startup: suspend (JSONObject, WorkspacePrincipal) -> Boolean) =
        WorkspaceApplicationActivation(db).activate(c.getString("workspace_id"), c.getString("realm_id"), c.getString("candidate_id"), check.getString("check_id"), startup)
    @Test fun fixtureReceiptActivatesExactBytesAndSurvivesReopen() = runTest {
        val c = candidate(); val checked = checks(c)
        assertTrue(checked.toString(), checked.getBoolean("passed")); assertFalse(checked.getBoolean("activation_authorized"))
        val result = activate(c, checked) { b, p ->
            assertEquals(WorkspaceInstanceMode.PREVIEW, p.mode)
            assertEquals(c.getString("content_hash"), WorkspaceApplicationContract.hash(WorkspaceApplicationContract.canonical(b)))
            assertEquals(-1L, WorkspaceApplicationData(db).epoch(p.workspaceId))
            true
        }
        db.close(); db = AtlasDatabase(RuntimeEnvironment.getApplication())
        val workspace = db.loadComposableWorkspace(c.getString("workspace_id"))!!
        val inspected = WorkspacePackageRepository(db).inspect(workspace.id, workspace.realmId, c.getString("candidate_id"))
        assertTrue(inspected.getBoolean("executed")); assertTrue(inspected.getBoolean("activated"))
        assertEquals(checked.getString("check_id"), inspected.getJSONArray("checks").getJSONObject(0).getString("check_id"))
        assertEquals(result.getString("revision_id"), workspace.liveRevisionId)
        assertEquals(1L, WorkspaceApplicationData(db).epoch(workspace.id))
        assertEquals(c.getString("content_hash"), db.loadLiveWorkspaceRevision(workspace.id)!!.contentHash)
        val replay = activate(c, checked) { _, _ -> error("A committed activation must not rerun startup") }
        assertEquals(result.getString("revision_id"), replay.getString("revision_id"))
        assertEquals(1L, replay.getLong("epoch"))
    }
    @Test fun compatibleReplacementPreservesRecordsAndFencesOldPrincipal() = runTest {
        val first = candidate(); val original = activate(first, checks(first)) { _, _ -> true }
        val id = first.getString("workspace_id"); val realm = first.getString("realm_id")
        val oldPrincipal = WorkspacePrincipal(realm, id, original.getString("revision_id"), "old-view", original.getLong("epoch"), WorkspaceInstanceMode.FOREGROUND)
        db.addWorkspaceRecord(id, "notes", "{}")
        val session = db.createSession("Update", "Test", workspaceId = realm); val turn = db.createTurn(session.id, "user")
        val bundle = PackageFixture.bundle(); bundle.getJSONObject("files").put("ui/app.js", "document.body.textContent='Updated';")
        val second = WorkspacePackageRepository(db).stage(id, realm, original.getString("revision_id"), null,
            bundle.getJSONObject("manifest"), bundle.getJSONObject("files"), session.id, turn.id).put("realm_id", realm)
        val updated = activate(second, checks(second)) { _, _ -> true }
        assertEquals(2L, updated.getLong("epoch")); assertEquals(1, db.listWorkspaceRecords(id, "notes").size)
        assertThrows(IllegalArgumentException::class.java) { WorkspaceApplicationData(db).query(oldPrincipal, JSONObject().put("collection", "notes")) }
    }
    @Test fun failureAndCancellationNeverAuthorizeActivation() = runTest {
        val c = candidate()
        val failed = checks(c) { _, _, _ -> JSONObject().put("ok", true).put("value", 999) }
        assertFalse(failed.getBoolean("passed"))
        assertTrue(runCatching { activate(c, failed) { _, _ -> true } }.isFailure)
        assertTrue(runCatching { checks(c) { _, _, _ -> throw CancellationException("cancel") } }.isFailure)
        db.readableDatabase.rawQuery("SELECT COUNT(*) FROM workspace_application_checks", null).use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
    }
    @Test fun failedStartupLeavesRevisionAndRecordsUntouched() = runTest {
        val c = candidate(); val checked = checks(c); val id = c.getString("workspace_id")
        val base = db.loadComposableWorkspace(id)!!.liveRevisionId
        assertTrue(runCatching { activate(c, checked) { _, _ -> false } }.isFailure)
        assertEquals(base, db.loadComposableWorkspace(id)!!.liveRevisionId)
        assertEquals(-1L, WorkspaceApplicationData(db).epoch(id))
    }
    @Test fun dataChangesDuringExecutionCannotProduceFreshEvidence() = runTest {
        val c = candidate()
        assertTrue(runCatching { checks(c) { b, e, i ->
            db.addWorkspaceRecord(c.getString("workspace_id"), "notes", "{}")
            workerResult(b, e, i)
        } }.isFailure)
        db.readableDatabase.rawQuery("SELECT COUNT(*) FROM workspace_application_checks", null).use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
    }
    @Test fun dataChangesDuringStartupPreventPointerSwitch() = runTest {
        val c = candidate(); val checked = checks(c); val id = c.getString("workspace_id")
        val base = db.loadComposableWorkspace(id)!!.liveRevisionId
        assertTrue(runCatching { activate(c, checked) { _, _ -> db.addWorkspaceRecord(id, "notes", "{}"); true } }.isFailure)
        assertEquals(base, db.loadComposableWorkspace(id)!!.liveRevisionId)
        assertEquals(1, db.listWorkspaceRecords(id, "notes").size)
    }
    @Test fun checkFromAnotherCandidateCannotAuthorizeActivation() = runTest {
        val a = candidate(); val b = candidate(); val checked = checks(a)
        assertTrue(runCatching { activate(b, checked) { _, _ -> true } }.isFailure)
    }
}
