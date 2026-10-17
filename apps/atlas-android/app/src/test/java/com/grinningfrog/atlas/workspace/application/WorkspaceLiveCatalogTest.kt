package com.grinningfrog.atlas.workspace.application

import android.app.Application
import com.grinningfrog.atlas.data.AtlasDatabase
import com.grinningfrog.atlas.workspace.WorkspaceTools
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WorkspaceLiveCatalogTest {
    @Test fun activationReturnsReusablePureCheckIdsAndKeepsDeclaredBehaviorGate() = kotlinx.coroutines.test.runTest {
        val context=RuntimeEnvironment.getApplication();context.deleteDatabase("atlas.db");val db=AtlasDatabase(context)
        try {
            val session=db.createSession("Activate","test");db.updateSessionStatus(session.id,com.grinningfrog.atlas.model.SessionStatus.ACTIVE)
            val turn=db.createTurn(session.id,"text");val store=com.grinningfrog.atlas.runtime.ExecutionTaskStore(db)
            val task=store.create(db.loadSession(session.id)!!,"Update filters",turn.id,com.grinningfrog.atlas.runtime.ExecutionBudget())
            store.claim(task.id,"test")!!
            val w=db.createComposableWorkspace("App","",realmId=session.workspaceId)
            val sequence=store.bindWorkspace(turn.id,w.id,"activation")
            val bundle=WorkspaceApplicationContract.example()
            val candidate=WorkspacePackageRepository(db).stage(w.id,w.realmId,w.liveRevisionId,null,bundle.getJSONObject("manifest"),bundle.getJSONObject("files"),session.id,turn.id)
            val pure=WorkspaceCandidateChecks(db) {_,_,_->JSONObject().put("ok",true).put("value",true)}.test(w.id,w.realmId,candidate.getString("candidate_id"))
            val tool=WorkspaceLiveTools.create(db,context).single {it.definition.name=="workspace_package_activate"}
            val args=JSONObject().put("workspace_id",w.id).put("candidate_id",candidate.getString("candidate_id")).put("check_id","ui-check-is-not-pure").put("navigation_sequence",sequence)
            val call=com.grinningfrog.atlas.model.AtlasToolCall("wrong-check",session.id,turn.id,tool.definition.name,args.toString(),com.grinningfrog.atlas.model.ToolCallStatus.PROPOSED,com.grinningfrog.atlas.model.ToolRisk.SESSION_WRITE,false,"wrong-check",createdAtMs=0,updatedAtMs=0)
            val result=JSONObject(tool.execute(call).resultJson)
            assertEquals("CHECK_REQUIRED",result.getString("error"));assertEquals("pure_fixtures",result.getString("required_check_kind"))
            assertEquals(pure.getString("check_id"),result.getJSONArray("valid_check_ids").getString(0))
            store.bindWorkspace(turn.id,w.id,criteria=org.json.JSONArray(listOf("Filter hides other events")))
            val gated=JSONObject(tool.execute(call.copy(id="missing-criteria",argumentsJson=args.put("check_id",pure.getString("check_id")).toString())).resultJson)
            assertEquals("ACCEPTANCE_REQUIRED",gated.getString("error"))
            assertEquals(w.liveRevisionId,db.loadComposableWorkspace(w.id)!!.liveRevisionId)
        } finally {db.close()}
    }
    @Test fun pinnedManifestAndFileIndexReadsCannotCrossWorkspaceRevision() = kotlinx.coroutines.test.runTest {
        val context = RuntimeEnvironment.getApplication(); context.deleteDatabase("atlas.db")
        val db = AtlasDatabase(context)
        try {
            val session = db.createSession("Source", "Read"); val turn = db.createTurn(session.id, "user")
            val workspace = db.createComposableWorkspace("Package", "", WorkspaceApplicationContract.example().toString(), realmId = session.workspaceId)
            val other = db.createComposableWorkspace("Other", "", WorkspaceApplicationContract.example().toString(), realmId = session.workspaceId)
            val tool = WorkspaceLiveTools.create(db, context).single { it.definition.name == "workspace_package_read_file" }
            suspend fun read(path: String, revision: String = workspace.liveRevisionId): JSONObject {
                val args = JSONObject().put("workspace_id", workspace.id).put("revision_id", revision).put("path", path)
                val call = com.grinningfrog.atlas.model.AtlasToolCall("source-$path", session.id, turn.id, tool.definition.name, args.toString(),
                    com.grinningfrog.atlas.model.ToolCallStatus.PROPOSED, com.grinningfrog.atlas.model.ToolRisk.READ_ONLY, false, "source-$path", createdAtMs = 0, updatedAtMs = 0)
                return JSONObject(tool.execute(call).resultJson)
            }
            assertEquals(WorkspaceApplicationContract.FORMAT, JSONObject(read("atlas/manifest.json").getString("source")).getString("format"))
            val index = org.json.JSONArray(read("atlas/files.json").getString("source"))
            assertTrue((0 until index.length()).map(index::getString).contains("ui/app.js"))
            assertTrue(runCatching { read("atlas/manifest.json", other.liveRevisionId) }.isFailure)
        } finally { db.close() }
    }
    @Test fun productionCatalogHasOneAuthoringPathAndEnoughRoomForPackageFiles() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("atlas.db")
        val db = AtlasDatabase(context)
        try {
            val tools = WorkspaceTools.create(db, applicationContext = context).associateBy { it.definition.name }
            assertTrue(tools.keys.containsAll(listOf("workspace_package_create", "workspace_package_stage", "workspace_package_test", "workspace_package_activate", "workspace_package_read_file", "workspace_package_run", "workspace_target")))
            for (old in listOf("workspace_create", "workspace_capabilities", "workspace_replace_definition", "workspace_patch_definition", "workspace_validate_definition", "workspace_add_record")) assertFalse(old, tools.containsKey(old))
            assertTrue(tools.getValue("workspace_package_stage").definition.maxArgumentBytes > 1024 * 1024)
            val schema = JSONObject(tools.getValue("workspace_package_test").definition.parametersJson)
            assertTrue(schema.getJSONArray("required").toString().contains("candidate_id"))
        } finally { db.close() }
    }
}
