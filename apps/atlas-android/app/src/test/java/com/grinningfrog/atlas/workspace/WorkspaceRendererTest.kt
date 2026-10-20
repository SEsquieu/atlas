package com.grinningfrog.atlas.workspace

import android.app.Application
import android.graphics.Canvas
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.grinningfrog.atlas.data.AtlasDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WorkspaceRendererTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var db: AtlasDatabase
    @Before fun setup() { RuntimeEnvironment.getApplication().deleteDatabase("atlas.db"); db = AtlasDatabase(RuntimeEnvironment.getApplication()); db.writableDatabase }
    @After fun cleanup() { db.close() }

    @Test fun nativeChartDrawsSignedBarsAndTapSelectionResetsWithNewData() {
        val initial = WorkspaceCapabilityCatalog.chartExample()
        val session = db.createSession("Chart", "Test")
        val workspace = db.createComposableWorkspace("Chart", "", initial.toString(), session.workspaceId)
        db.activateComposableWorkspace(session.id, workspace.id)
        val root = mutableStateOf(initial)
        val refresh = mutableIntStateOf(0)
        compose.setContent { MaterialTheme { WorkspaceV2Renderer(workspace, root.value, db, refresh.intValue, {}, Modifier.fillMaxSize()) } }
        compose.waitForIdle()
        compose.onNodeWithText("Second: -4.0").performClick()
        compose.onNodeWithText("Selected: Second = -4.0").assertExists()
        compose.runOnIdle {
            val view = compose.activity.findViewById<android.view.View>(android.R.id.content)
            val target = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try { view.draw(Canvas(target)) } finally { target.recycle() }
        }
        val chart = WorkspaceRenderSurface.diagnostics(workspace.id, workspace.liveRevisionId)!!.getJSONObject("projection").getJSONArray("charts").getJSONObject(0)
        assertEquals(-4.0, chart.getDouble("minimum"), 0.0)
        assertEquals(3, chart.getJSONArray("points").length())
        compose.runOnIdle {
            val updated = JSONObject(initial.toString())
            updated.getJSONArray("views").getJSONObject(0).getJSONArray("components").getJSONObject(0).getJSONArray("points").getJSONObject(1).put("value", -8)
            root.value = updated; refresh.intValue++
        }
        compose.waitForIdle()
        compose.onNodeWithText("Second: -8.0").assertExists()
        compose.onNodeWithText("Selected: Second = -4.0").assertDoesNotExist()
    }

    @Test fun realRendererKeepsOrderAfterToggleRemoveUndoAndCapturesItsViewport() {
        val root = RecordEditingFixture.definition()
        root.getJSONObject("actions").put("remove", JSONObject().put("steps", JSONArray().put(JSONObject().put("type", "remove_record").put("collection", "entries").put("record_id", JSONObject().put("var", "row.id")))))
        root.getJSONArray("views").getJSONObject(0).getJSONArray("components").getJSONObject(0).put("remove_action", "remove")
        val session = db.createSession("Render", "Test")
        val workspace = db.createComposableWorkspace("Render", "", root.toString(), session.workspaceId)
        db.activateComposableWorkspace(session.id, workspace.id)
        val a = db.addWorkspaceRecord(workspace.id, "entries", RecordEditingFixture.data("First").toString(), 10)
        val b = db.addWorkspaceRecord(workspace.id, "entries", RecordEditingFixture.data("Second").toString(), 20)
        val refresh = mutableIntStateOf(0)
        val visible = mutableStateOf(true)
        lateinit var scope: CoroutineScope
        compose.setContent {
            scope = rememberCoroutineScope()
            MaterialTheme { if (visible.value) WorkspaceV2Renderer(workspace, root, db, refresh.intValue, { refresh.intValue++ }, Modifier.fillMaxSize()) }
        }
        compose.waitForIdle()
        fun draw() {
            compose.waitForIdle()
            compose.runOnIdle {
                val view = compose.activity.findViewById<android.view.View>(android.R.id.content)
                val target = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                try { view.draw(Canvas(target)) } finally { target.recycle() }
            }
        }
        fun order(): List<String> {
            draw()
            val snapshot = WorkspaceRenderSurface.diagnostics(workspace.id, workspace.liveRevisionId)!!
            val ids = snapshot.getJSONObject("projection").getJSONArray("lists").getJSONObject(0).getJSONArray("ordered_record_ids")
            return (0 until ids.length()).map(ids::getString)
        }
        assertEquals(listOf(a.id, b.id), order())
        compose.onAllNodes(isToggleable())[0].performClick()
        compose.waitForIdle()
        assertTrue(JSONObject(db.listWorkspaceRecords(workspace.id, "entries").first().dataJson).getBoolean("done"))
        assertEquals(listOf(a.id, b.id), order())
        compose.onAllNodesWithText("Remove")[0].performClick()
        compose.waitForIdle(); assertEquals(listOf(b.id), order())
        compose.onNodeWithText("Undo removal").performClick()
        compose.waitForIdle(); assertEquals(listOf(a.id, b.id), order())
        val capture = AtomicReference<Pair<Bitmap, JSONObject>?>()
        val failure = AtomicReference<Throwable?>()
        compose.runOnIdle { scope.launch { runCatching { WorkspaceRenderSurface.capture(workspace.id, workspace.liveRevisionId) }.onSuccess(capture::set).onFailure(failure::set) } }
        compose.waitUntil(10_000) { capture.get() != null || failure.get() != null }
        assertNull(failure.get())
        val bitmap = capture.get()!!.first
        assertTrue(bitmap.width > 0 && bitmap.height > 0)
        assertEquals("visible_workspace_viewport", capture.get()!!.second.getString("scope"))
        val readable = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val colors = mutableSetOf<Int>()
        for (y in 0 until readable.height step maxOf(1, readable.height / 30)) for (x in 0 until readable.width step maxOf(1, readable.width / 30)) colors += readable.getPixel(x, y)
        assertTrue("Captured renderer must contain visible content, not a blank bitmap", colors.size > 1)
        readable.recycle(); bitmap.recycle()
        compose.waitUntil(10_000) { db.loadRecentEvents(session.id).any { it.type == "workspace.rendered" } }
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        assertNull(WorkspaceRenderSurface.diagnostics(workspace.id, workspace.liveRevisionId))
    }
}
