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
            assertTrue(read("atlas/files.json").getString("source").contains("ui/app.js"))
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
