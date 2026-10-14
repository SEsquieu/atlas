package com.grinningfrog.atlas.workspace.application

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.grinningfrog.atlas.data.AtlasDatabase
import com.grinningfrog.atlas.workspace.WorkspaceStatus
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Immutable staging only. No live pointer or executor exists in this repository. */
class WorkspacePackageRepository(private val database: AtlasDatabase) {
    companion object {
        fun createTables(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE IF NOT EXISTS workspace_package_candidates (
                candidate_id TEXT PRIMARY KEY, workspace_id TEXT NOT NULL, base_revision_id TEXT NOT NULL,
                parent_candidate_id TEXT, content_hash TEXT NOT NULL, bundle_json TEXT NOT NULL,
                source_session_id TEXT NOT NULL, source_turn_id TEXT NOT NULL, created_at INTEGER NOT NULL,
                FOREIGN KEY(workspace_id) REFERENCES composable_workspaces(composable_workspace_id) ON DELETE CASCADE,
                FOREIGN KEY(parent_candidate_id) REFERENCES workspace_package_candidates(candidate_id)
            )""".trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS workspace_package_candidate_owner ON workspace_package_candidates(workspace_id,created_at DESC)")
        }
    }

    fun inspect(workspaceId: String, realmId: String, candidateId: String? = null): JSONObject {
        owner(workspaceId, realmId)
        if (candidateId != null) return load(workspaceId, candidateId)
        val rows = JSONArray()
        database.readableDatabase.rawQuery("SELECT candidate_id,base_revision_id,parent_candidate_id,content_hash,created_at FROM workspace_package_candidates WHERE workspace_id=? ORDER BY created_at DESC,candidate_id DESC LIMIT 20", arrayOf(workspaceId)).use { cursor ->
            while (cursor.moveToNext()) rows.put(JSONObject().put("candidate_id", cursor.getString(0)).put("base_revision_id", cursor.getString(1))
                .put("parent_candidate_id", if (cursor.isNull(2)) JSONObject.NULL else cursor.getString(2)).put("content_hash", cursor.getString(3)).put("created_at_ms", cursor.getLong(4)))
        }
        return JSONObject().put("workspace_id", workspaceId).put("live_revision_id", database.loadComposableWorkspace(workspaceId)!!.liveRevisionId)
            .put("candidates", rows).put("contract", WorkspaceApplicationContract.descriptor())
    }

    /** Entire file batch is validated before any row is inserted. Old candidates are never updated. */
    fun stage(workspaceId: String, realmId: String, baseRevisionId: String, parentCandidateId: String?, manifest: JSONObject?, changes: JSONObject,
        sessionId: String, turnId: String): JSONObject {
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            val workspace = owner(workspaceId, realmId)
            require(workspace.liveRevisionId == baseRevisionId) { "REVISION_CONFLICT" }
            val session = database.loadSession(sessionId) ?: error("Session is unavailable")
            require(session.workspaceId == realmId) { "TARGET_CONFLICT" }
            require(database.loadTurn(turnId)?.sessionId == sessionId) { "Turn does not belong to the source session" }
            require(changes.length() <= WorkspaceApplicationContract.MAX_FILES) { "Too many file changes" }
            val bundle = if (parentCandidateId == null) JSONObject().put("files", JSONObject()) else {
                val parent = load(workspaceId, parentCandidateId)
                require(parent.getString("base_revision_id") == baseRevisionId) { "REVISION_CONFLICT" }
                JSONObject(parent.getJSONObject("bundle").toString())
            }
            manifest?.let { bundle.put("manifest", JSONObject(it.toString())) }
            val files = bundle.getJSONObject("files")
            changes.keys().asSequence().forEach { path ->
                require(WorkspaceApplicationContract.validPath(path)) { "Invalid package path: $path" }
                if (changes.isNull(path)) files.remove(path) else {
                    require(changes.get(path) is String) { "File changes must be string or null" }
                    files.put(path, changes.getString(path))
                }
            }
            val errors = WorkspaceApplicationContract.validate(bundle)
            require(errors.isEmpty()) { "VALIDATION_FAILED: ${errors.joinToString("; ")}" }
            val used = db.rawQuery("SELECT COUNT(*),coalesce(SUM(length(CAST(bundle_json AS BLOB))),0) FROM workspace_package_candidates WHERE workspace_id=?", arrayOf(workspaceId)).use { it.moveToFirst(); it.getInt(0) to it.getLong(1) }
            require(used.first < 64 && used.second + bundle.toString().toByteArray(Charsets.UTF_8).size <= 16L * 1024 * 1024) { "QUOTA_EXCEEDED: staging retention limit" }
            val canonical = WorkspaceApplicationContract.canonical(bundle)
            val hash = WorkspaceApplicationContract.hash(canonical)
            val candidate = UUID.randomUUID().toString()
            db.insertOrThrow("workspace_package_candidates", null, ContentValues().apply {
                put("candidate_id", candidate); put("workspace_id", workspaceId); put("base_revision_id", baseRevisionId)
                put("parent_candidate_id", parentCandidateId); put("content_hash", hash); put("bundle_json", canonical)
                put("source_session_id", sessionId); put("source_turn_id", turnId); put("created_at", System.currentTimeMillis())
            })
            db.setTransactionSuccessful()
            return JSONObject().put("workspace_id", workspaceId).put("candidate_id", candidate).put("content_hash", hash)
                .put("structurally_valid", true).put("executed", false).put("activated", false).put("tests_run", 0)
        } finally { db.endTransaction() }
    }

    private fun owner(workspaceId: String, realmId: String) = (database.loadComposableWorkspace(workspaceId) ?: error("Workspace is unavailable")).also {
        require(it.realmId == realmId && it.status == WorkspaceStatus.ACTIVE) { "TARGET_CONFLICT" }
    }

    private fun load(workspaceId: String, candidateId: String): JSONObject = database.readableDatabase.rawQuery(
        "SELECT base_revision_id,parent_candidate_id,content_hash,bundle_json,created_at FROM workspace_package_candidates WHERE workspace_id=? AND candidate_id=?",
        arrayOf(workspaceId, candidateId)
    ).use { cursor ->
        require(cursor.moveToFirst()) { "Candidate is unavailable in this workspace" }
        val bundle = JSONObject(cursor.getString(3))
        require(WorkspaceApplicationContract.hash(WorkspaceApplicationContract.canonical(bundle)) == cursor.getString(2)) { "PACKAGE_CORRUPT" }
        JSONObject().put("workspace_id", workspaceId).put("candidate_id", candidateId).put("base_revision_id", cursor.getString(0))
            .put("parent_candidate_id", if (cursor.isNull(1)) JSONObject.NULL else cursor.getString(1)).put("content_hash", cursor.getString(2))
            .put("bundle", bundle).put("created_at_ms", cursor.getLong(4)).put("executed", false).put("activated", false)
    }
}
