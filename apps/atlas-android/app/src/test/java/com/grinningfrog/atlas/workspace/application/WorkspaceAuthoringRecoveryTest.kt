package com.grinningfrog.atlas.workspace.application

import android.app.Application
import com.grinningfrog.atlas.data.AtlasDatabase
import com.grinningfrog.atlas.model.*
import com.grinningfrog.atlas.runtime.*
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class WorkspaceAuthoringRecoveryTest {
    private val context get()=RuntimeEnvironment.getApplication()
    @Test fun testStorageUsesRealValidationAndCannotAuthorizeTestModeOnPhoneDatabase() {
        context.deleteDatabase("atlas.db")
        val live=AtlasDatabase(context)
        val bundle=WorkspaceApplicationContract.example()
        try {
            val workspace=live.createComposableWorkspace("Phone records","",bundle.toString())
            val data=WorkspaceApplicationData(live);data.setEnabled(workspace.id,workspace.liveRevisionId,true)
            val p=WorkspacePrincipal(workspace.realmId,workspace.id,workspace.liveRevisionId,"phone",data.epoch(workspace.id),WorkspaceInstanceMode.TEST)
            assertFalse(data.acceptsMode(p));assertTrue(runCatching {data.query(p,JSONObject().put("collection","notes"))}.isFailure)
            WorkspaceTestStorage(context,bundle).use { isolated ->
                val principal=isolated.principal()
                assertEquals("isolated_test_database",WorkspaceHostRegistry.dispatch(principal,"host.describe",JSONObject(),isolated.data).getString("storage_scope"))
                val invalid=runCatching {isolated.data.query(principal,JSONObject().put("collection","notes").put("limit",200))}.exceptionOrNull()!!
                assertTrue(invalid.message.orEmpty().contains("limit must be an integer in 1..100"))
                val prep=isolated.data.prepare(principal,JSONObject().put("expected_data_version",0).put("request_key","save").put("mutations",org.json.JSONArray().put(JSONObject().put("kind","create").put("collection","notes").put("data",JSONObject().put("text","Saved")))))
                isolated.data.commit(principal,prep.getString("operation_id"))
                assertEquals("Saved",isolated.data.query(isolated.principal(),JSONObject().put("collection","notes")).getJSONArray("records").getJSONObject(0).getJSONObject("data").getString("text"))
            }
            assertEquals(1,live.listComposableWorkspaces().size)
            assertEquals(0,live.listWorkspaceRecords(workspace.id).size)
        } finally {live.close()}
    }
    @Test fun draftRecoverySurvivesNewTaskAndIdenticalStagingRetainsCandidate() = runTest {
        context.deleteDatabase("atlas.db");val db=AtlasDatabase(context)
        try {
            val session=db.createSession("Build","App");db.updateSessionStatus(session.id,SessionStatus.ACTIVE)
            val store=ExecutionTaskStore(db)
            val tool=WorkspaceLiveTools.create(db,context).single {it.definition.name=="workspace_package_create"} as AtlasLocalMutationAdapter
            fun create(): JSONObject {
                val turn=db.createTurn(session.id,"text");val task=store.create(db.loadSession(session.id)!!,"Build App",turn.id,ExecutionBudget())
                val lease=store.claim(task.id,"test")!!
                val c=AtlasToolCall(UUID.randomUUID().toString(),session.id,turn.id,tool.definition.name,"""{"name":"App","delivery":"activation","require_persistence":true}""",ToolCallStatus.PROPOSED,ToolRisk.SESSION_WRITE,false,UUID.randomUUID().toString(),createdAtMs=0,updatedAtMs=0)
                db.insertToolCall(c)
                val result=JSONObject(store.localOperation(c,lease){tool.executeLocal(c)}.resultJson)
                store.control(task.id,ExecutionState.CANCELLED,"user interrupted")
                return result
            }
            val first=create();val second=create()
            assertEquals(first.getString("workspace_id"),second.getString("workspace_id"));assertTrue(second.getBoolean("reused_draft"));assertEquals(1,db.listComposableWorkspaces().size)
            val w=db.loadComposableWorkspace(first.getString("workspace_id"))!!;assertTrue(w.applicationDraft)
            val bundle=WorkspaceApplicationContract.example();val turn=db.createTurn(session.id,"stage");val repository=WorkspacePackageRepository(db)
            val a=repository.stage(w.id,w.realmId,w.liveRevisionId,null,bundle.getJSONObject("manifest"),bundle.getJSONObject("files"),session.id,turn.id)
            val b=repository.stage(w.id,w.realmId,w.liveRevisionId,a.getString("candidate_id"),null,bundle.getJSONObject("files"),session.id,turn.id)
            assertEquals(a.getString("candidate_id"),b.getString("candidate_id"));assertTrue(b.getBoolean("reused_unchanged_candidate"))
        } finally {db.close()}
    }
}
