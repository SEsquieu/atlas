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
    @Test fun durableNamedTargetEditsWithoutNavigatingAndSurvivesSliceAndDatabaseReopen() = runTest {
        var session = db.createSession("Targets", "Edit GraphTest")
        db.updateSessionStatus(session.id, SessionStatus.ACTIVE)
        val definition = """{"format":"atlas.workspace.v1","title":"Blank","components":[]}"""
        val graph = db.createComposableWorkspace("GraphTest", "", definition, session.workspaceId, session.id, null)
        val calculator = db.createComposableWorkspace("Calculator", "", definition, session.workspaceId, session.id, null)
        db.activateComposableWorkspace(session.id, calculator.id)
        session = db.loadSession(session.id)!!
        val turn = db.createTurn(session.id, "text")
        var store = ExecutionTaskStore(db)
        val task = store.create(session, "Edit GraphTest", turn.id, ExecutionBudget())
        val lease = store.claim(task.id, "runner")!!
        val tools = WorkspaceTools.create(db)
        val target = tools.single { it.definition.name == "workspace_target" }
        val bound = JSONObject(target.execute(call(session, turn, "workspace_target", """{"name":"graphtest"}""")).resultJson)
        assertTrue(bound.getBoolean("ok"))
        assertEquals(calculator.id, db.loadSession(session.id)!!.activeComposableWorkspaceId)
        val sequence = bound.getLong("navigation_sequence")
        db.activateComposableWorkspace(session.id, null)
        store.finish(lease, SliceOutcome.YIELDED, 1)
        val next = db.loadTurn(store.nextTurn(task.id))!!
        db.close(); db = AtlasDatabase(RuntimeEnvironment.getApplication()); store = ExecutionTaskStore(db)
        assertEquals(graph.id, store.workspaceSession(db.loadSession(session.id)!!, next.id).activeComposableWorkspaceId)
        store.claim(task.id, "next")!!
        val replace = WorkspaceTools.create(db).single { it.definition.name == "workspace_replace_definition" }
        val args = JSONObject().put("workspace_id", graph.id).put("base_revision_id", graph.liveRevisionId).put("navigation_sequence", sequence)
            .put("purpose", "Requested edit").put("definition", JSONObject(definition).put("title", "Edited"))
        val scoped = store.workspaceSession(db.loadSession(session.id)!!, next.id)
        assertTrue(replace.evaluate(scoped, ToolCallProposal("replace", replace.definition.name, args.toString())).allowed)
        replace.execute(call(session, next, replace.definition.name, args.toString()))
        assertEquals("Edited", JSONObject(db.loadLiveWorkspaceRevision(graph.id)!!.definitionJson).getString("title"))
        assertNull(db.loadSession(session.id)!!.activeComposableWorkspaceId)
        // Reusing the inspected revision after a successful edit must still fail.
        assertTrue(runCatching { replace.execute(call(session, next, replace.definition.name, args.toString())) }.isFailure)
        // Cross-pollination changes the task target, not the visible selection.
        store.bindWorkspace(next.id, calculator.id)
        assertFalse(replace.evaluate(store.workspaceSession(db.loadSession(session.id)!!, next.id), ToolCallProposal("stale", replace.definition.name, args.toString())).allowed)
        assertNull(db.loadSession(session.id)!!.activeComposableWorkspaceId)
    }
    @Test fun duplicateNamesDoNotRetargetAndNewTaskCapturesCurrentSelection() = runTest {
        var session = db.createSession("Targets", "Edit this")
        db.updateSessionStatus(session.id, SessionStatus.ACTIVE)
        val definition = """{"format":"atlas.workspace.v1","title":"Blank","components":[]}"""
        val a = db.createComposableWorkspace("Same", "", definition, session.workspaceId, session.id, null)
        db.createComposableWorkspace("same", "", definition, session.workspaceId, session.id, null)
        db.activateComposableWorkspace(session.id, a.id); session = db.loadSession(session.id)!!
        val turn = db.createTurn(session.id, "text"); val store = ExecutionTaskStore(db)
        val task = store.create(session, "Edit this", turn.id, ExecutionBudget()); store.claim(task.id, "runner")!!
        val target = WorkspaceTools.create(db).single { it.definition.name == "workspace_target" }
        val result = JSONObject(target.execute(call(session, turn, target.definition.name, """{"name":"Same"}""")).resultJson)
        assertEquals("ambiguous_workspace_name", result.getString("reason"))
        assertEquals(a.id, store.workspaceSession(session, turn.id).activeComposableWorkspaceId)
        db.activateComposableWorkspace(session.id, null)
        val next = db.createTurn(session.id, "text")
        store.create(db.loadSession(session.id)!!, "New command", next.id, ExecutionBudget())
        assertNull(store.workspaceSession(db.loadSession(session.id)!!, next.id).activeComposableWorkspaceId)
    }

}
