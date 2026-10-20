package com.grinningfrog.atlas.workspace

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import com.grinningfrog.atlas.data.AtlasDatabase
import com.grinningfrog.atlas.media.MediaRepository
import com.grinningfrog.atlas.model.*
import com.grinningfrog.atlas.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WorkspaceDiagnosticsTest {
    private lateinit var db: AtlasDatabase
    @Before fun setup() { RuntimeEnvironment.getApplication().deleteDatabase("atlas.db"); db = AtlasDatabase(RuntimeEnvironment.getApplication()); db.writableDatabase }
    @After fun cleanup() { db.close(); Dispatchers.resetMain() }
    private fun call(session: AtlasSession, name: String, args: String, turn: String) = AtlasToolCall(name, session.id, turn, name, args, ToolCallStatus.PROPOSED, ToolRisk.READ_ONLY, false, name, createdAtMs = 0, updatedAtMs = 0)

    @Test fun installedDiagnosticsRemainReadOnlyDuringDiscoveryAndUiMediaNeverReplacesPhysicalContext() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val session = db.createSession("UI", "Diagnose")
        val workspace = db.createComposableWorkspace("UI", "", RecordEditingFixture.definition().toString(), session.workspaceId)
        db.activateComposableWorkspace(session.id, workspace.id)
        val turn = db.createTurn(session.id, "user")
        val media = MediaRepository(RuntimeEnvironment.getApplication())
        val adapters = WorkspaceTools.create(db, media)
        val harness = ToolHarness(db, { _, _ -> error("Camera must not be invoked") }, { DeviceHealth() }, additionalTools = adapters)
        harness.execute(call(session, CapabilityDiscovery.TOOL, """{"stage":"blocked","objective":"Diagnose a UI bug","missing_capability":"Rendering evidence"}""", turn.id))
        val current = db.loadSession(session.id)!!
        assertTrue(harness.evaluate(current, ToolCallProposal("diagnose", "workspace_render_diagnostics", "{}")).allowed)
        val unavailable = JSONObject(harness.execute(call(session, "workspace_render_diagnostics", "{}", turn.id)).resultJson)
        assertFalse(unavailable.getBoolean("render_available"))
        assertTrue(runCatching { harness.execute(call(session, "workspace_capture_view", "{}", turn.id)) }.isFailure)
        val owner = Any()
        WorkspaceRenderSurface.register(owner, workspace.id, workspace.liveRevisionId) { Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) } }
        try {
            WorkspaceRenderSurface.drawn(owner, JSONObject().put("drawn_at_ms", 10).put("projection", JSONObject().put("view_id", "main")))
            val result = harness.execute(call(session, "workspace_capture_view", "{}", turn.id))
            val image = result.observation!!
            assertEquals(MediaPurpose.WORKSPACE_UI, image.media.purpose)
            assertTrue(media.inferenceImage(image.media).bytes.isNotEmpty())
            assertNull(db.loadLatestObservation(session.id))
            assertEquals(FreshnessDecision.REFRESH_REQUIRED, FreshnessPolicy.assess("What is this?", image, 100).decision)
            assertFalse(harness.evaluate(current, ToolCallProposal("update", "workspace_update_record", "{}")).allowed)
            val diagnostics = JSONObject(harness.execute(call(session, "workspace_render_diagnostics", "{}", turn.id)).resultJson)
            assertTrue(diagnostics.getBoolean("render_available"))
            db.activateComposableWorkspace(session.id, null)
            assertTrue(runCatching { harness.execute(call(session, "workspace_capture_view", "{}", turn.id)) }.isFailure)
        } finally { WorkspaceRenderSurface.unregister(owner) }
    }
    @Test fun draftAndWebPackageDiagnosticsReturnExplicitEvidence() = runTest {
        val session = db.createSession("Draft", "Diagnose")
        val workspace = db.createComposableWorkspace("Draft", "", realmId = session.workspaceId)
        db.activateComposableWorkspace(session.id, workspace.id)
        val turn = db.createTurn(session.id, "user")
        val adapter = WorkspaceTools.create(db).single { it.definition.name == "workspace_render_diagnostics" }
        val draft = JSONObject(adapter.execute(call(session, adapter.definition.name, "{}", turn.id)).resultJson)
        assertEquals("no_application_surface", draft.getString("status"))
        assertFalse(draft.getBoolean("render_available"))
        val app = db.createComposableWorkspace("App", "", com.grinningfrog.atlas.workspace.application.WorkspaceApplicationContract.example().toString(), session.workspaceId)
        db.activateComposableWorkspace(session.id, app.id)
        val result = JSONObject(adapter.execute(call(session, adapter.definition.name, "{}", turn.id)).resultJson)
        assertEquals(com.grinningfrog.atlas.workspace.application.WorkspaceApplicationContract.FORMAT, result.getString("format"))
        assertFalse(result.getBoolean("render_available"))
    }

    @Test fun staleRevisionAndDisposedSurfaceCannotBeCaptured() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val owner = Any()
        WorkspaceRenderSurface.register(owner, "workspace", "revision") { error("Wrong revision must not capture") }
        WorkspaceRenderSurface.drawn(owner, JSONObject().put("drawn_at_ms", 10))
        assertTrue(runCatching { WorkspaceRenderSurface.capture("workspace", "new-revision") }.isFailure)
        WorkspaceRenderSurface.unregister(owner)
        assertTrue(runCatching { WorkspaceRenderSurface.capture("workspace", "revision") }.isFailure)
    }

    @Test fun harmlessRedrawIsAllowedButChangedContentInvalidatesCapture() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val owner = Any()
        var epoch = "same"
        WorkspaceRenderSurface.register(owner, "workspace", "revision") {
            WorkspaceRenderSurface.drawn(owner, JSONObject().put("drawn_at_ms", 20).put("render_epoch", epoch))
            Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        }
        try {
            WorkspaceRenderSurface.drawn(owner, JSONObject().put("drawn_at_ms", 10).put("render_epoch", "same"))
            WorkspaceRenderSurface.capture("workspace", "revision").first.recycle()
            epoch = "changed"
            assertTrue(runCatching { WorkspaceRenderSurface.capture("workspace", "revision") }.isFailure)
        } finally { WorkspaceRenderSurface.unregister(owner) }
    }
}
