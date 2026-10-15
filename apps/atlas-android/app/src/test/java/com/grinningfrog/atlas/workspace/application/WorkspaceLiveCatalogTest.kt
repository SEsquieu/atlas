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
    @Test fun productionCatalogHasOneAuthoringPathAndEnoughRoomForPackageFiles() {
        val context = RuntimeEnvironment.getApplication<Application>()
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
