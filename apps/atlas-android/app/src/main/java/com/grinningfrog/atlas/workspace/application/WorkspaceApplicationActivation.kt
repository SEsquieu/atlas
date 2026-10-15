package com.grinningfrog.atlas.workspace.application

import android.content.ContentValues
import com.grinningfrog.atlas.data.AtlasDatabase
import org.json.JSONObject
import kotlinx.coroutines.withTimeout
import java.util.UUID

/** Internal coordinator. No agent-supplied boolean can substitute for host presentation proof. */
internal class WorkspaceApplicationActivation(private val database: AtlasDatabase) {
    /**
     * Host must own the preview and dispose it on success, failure, or cancellation. Preview has no
     * live data/device bindings. Only the live-pointer/epoch transaction below exposes live state.
     * Not registered as an agent tool until on-device presentation containment is established.
     */
    suspend fun activate(workspaceId: String, realmId: String, candidateId: String, checkId: String,
        startup: suspend (JSONObject, WorkspacePrincipal) -> Boolean): JSONObject {
        val repository = WorkspacePackageRepository(database)
        val data = WorkspaceApplicationData(database)
        val candidate = repository.inspect(workspaceId, realmId, candidateId)
        val bundle = candidate.getJSONObject("bundle")
        val base = candidate.getString("base_revision_id")
        val version = data.version(workspaceId)
        val hash = candidate.getString("content_hash")
        fun verify() {
            repository.inspect(workspaceId, realmId, candidateId)
            require(database.loadComposableWorkspace(workspaceId)!!.liveRevisionId == base) { "REVISION_CONFLICT" }
            require(data.version(workspaceId) == version) { "DATA_CONFLICT" }
            database.readableDatabase.rawQuery("SELECT content_hash,base_revision_id,data_version FROM workspace_application_checks WHERE check_id=? AND workspace_id=? AND candidate_id=? AND kind='pure_fixtures' AND passed=1",
                arrayOf(checkId, workspaceId, candidateId)).use { row ->
                require(row.moveToFirst() && row.getString(0) == hash && row.getString(1) == base && row.getLong(2) == version) { "CHECK_REQUIRED" }
            }
            require(bundle.getJSONObject("manifest").getJSONArray("capabilities").length() == 0) { "WORKSPACE_GRANT_REQUIRED" }
            val old = JSONObject(database.loadLiveWorkspaceRevision(workspaceId)!!.definitionJson)
            if (WorkspaceApplicationData.isApplication(old)) {
                require(old.getJSONObject("manifest").getInt("data_schema_version") == bundle.getJSONObject("manifest").getInt("data_schema_version")) { "MIGRATION_REQUIRED" }
            } else {
                val count = database.readableDatabase.rawQuery("SELECT COUNT(*) FROM workspace_records WHERE composable_workspace_id=?", arrayOf(workspaceId)).use { it.moveToFirst(); it.getInt(0) }
                require(count == 0) { "MIGRATION_REQUIRED" }
            }
        }
        data.atomic { verify() }
        val revision = UUID.randomUUID().toString()
        // Epoch allocation never resets after a disable/re-enable.
        val priorEpoch = database.readableDatabase.rawQuery("SELECT epoch FROM workspace_application_lifecycle WHERE workspace_id=?", arrayOf(workspaceId)).use { if (it.moveToFirst()) it.getLong(0) else 0L }
        val epoch = Math.addExact(priorEpoch, 1L)
        val preview = WorkspacePrincipal(realmId, workspaceId, revision, UUID.randomUUID().toString(), epoch, WorkspaceInstanceMode.PREVIEW)
        require(withTimeout(10_000L) { startup(JSONObject(bundle.toString()), preview) }) { "STARTUP_FAILED" }
        return data.atomic {
            verify()
            val currentEpoch = rawQuery("SELECT epoch FROM workspace_application_lifecycle WHERE workspace_id=?", arrayOf(workspaceId)).use { if (it.moveToFirst()) it.getLong(0) else 0L }
            require(currentEpoch == priorEpoch) { "REVISION_CONFLICT" }
            val now = System.currentTimeMillis()
            val source = rawQuery("SELECT source_session_id,source_turn_id FROM workspace_package_candidates WHERE candidate_id=? AND workspace_id=?", arrayOf(candidateId, workspaceId)).use { it.moveToFirst(); it.getString(0) to it.getString(1) }
            val canonical = WorkspaceApplicationContract.canonical(bundle)
            insertOrThrow("workspace_revisions", null, ContentValues().apply {
                put("revision_id", revision); put("composable_workspace_id", workspaceId); put("parent_revision_id", base)
                put("definition_json", canonical); put("content_hash", hash); put("author_type", "MODEL")
                put("source_session_id", source.first); put("source_turn_id", source.second); put("purpose", "Application package activation"); put("created_at", now)
            })
            require(update("composable_workspaces", ContentValues().apply { put("live_revision_id", revision); put("updated_at", now) },
                "composable_workspace_id=? AND live_revision_id=?", arrayOf(workspaceId, base)) == 1) { "REVISION_CONFLICT" }
            execSQL("INSERT OR REPLACE INTO workspace_application_lifecycle(workspace_id,epoch,enabled) VALUES(?,?,1)", arrayOf(workspaceId, epoch))
            JSONObject().put("workspace_id", workspaceId).put("candidate_id", candidateId).put("revision_id", revision).put("previous_revision_id", base)
                .put("epoch", epoch).put("data_version", version).put("activated", true)
        }
    }
}
