package com.grinningfrog.atlas.data

import android.app.Application
import com.grinningfrog.atlas.model.*
import com.grinningfrog.atlas.runtime.*
import com.grinningfrog.atlas.workspace.*
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
class WorkspaceCatalogToolTest {
    private lateinit var db: AtlasDatabase
    @Before fun setup() { RuntimeEnvironment.getApplication().deleteDatabase("atlas.db"); db = AtlasDatabase(RuntimeEnvironment.getApplication()); db.writableDatabase }
    @After fun cleanup() { db.close() }
    private fun call(session: AtlasSession, turn: AgentTurn, name: String, args: String) = AtlasToolCall(name, session.id, turn.id, name, args, ToolCallStatus.PROPOSED, ToolRisk.READ_ONLY, false, name, createdAtMs = 0, updatedAtMs = 0)
    @Test fun catalogIsReadOnlyWithoutOpenWorkspaceAndRemainsAvailableDuringDiscovery() = runTest {
        val session = db.createSession("Catalog", "Inspect supported behavior")
        val turn = db.createTurn(session.id, "user")
        val h = ToolHarness(db, { _, _ -> error("No camera") }, { DeviceHealth() }, additionalTools = WorkspaceTools.create(db))
        h.execute(call(session, turn, CapabilityDiscovery.TOOL, """{"stage":"blocked","objective":"Inspect available behavior","missing_capability":"Capability information"}"""))
        assertTrue(h.evaluate(session, ToolCallProposal("inspect", "workspace_capabilities", "{}")).allowed)
        val result = h.execute(call(session, turn, "workspace_capabilities", """{"format":"atlas.workspace.v1","component":"canvas","include_examples":true}"""))
        assertTrue(JSONObject(result.resultJson).getJSONArray("formats").getJSONObject(0).getBoolean("supported"))
        assertEquals(0, db.listComposableWorkspaces().size)
        assertNull(db.loadSession(session.id)!!.activeComposableWorkspaceId)
        assertTrue(db.loadRecentEvents(session.id).any { it.type == "workspace.capabilities_inspected" })
        assertFalse(h.evaluate(session, ToolCallProposal("create", "workspace_create", """{"name":"Drawing pad"}""")).allowed)
    }
    @Test fun catalogCanvasExampleCreatesAndStoresDrawingWithoutV2Schema() = runTest {
        val session = db.createSession("Drawing", "Draw")
        val turn = db.createTurn(session.id, "user")
        val create = WorkspaceTools.create(db).single { it.definition.name == "workspace_create" }
        val args = JSONObject().put("name", "Drawing pad").put("definition", WorkspaceCapabilityCatalog.canvasExample())
        val result = JSONObject(create.execute(call(session, turn, create.definition.name, args.toString())).resultJson)
        val id = result.getString("workspace_id")
        db.addWorkspaceRecord(id, "drawing", """{"strokes":[[[1,2],[3,4]]]}""")
        db.close(); db = AtlasDatabase(RuntimeEnvironment.getApplication())
        assertEquals(2, JSONObject(db.listWorkspaceRecords(id, "drawing", 1).single().dataJson).getJSONArray("strokes").getJSONArray(0).length())
        assertEquals(WorkspaceDefinitionValidator.FORMAT, JSONObject(db.loadLiveWorkspaceRevision(id)!!.definitionJson).getString("format"))
    }
}
