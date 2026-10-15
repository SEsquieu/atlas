package com.grinningfrog.atlas.workspace.application

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.grinningfrog.atlas.data.AtlasDatabase
import com.grinningfrog.atlas.workspace.WorkspaceStatus
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Host-owned records and receipts. JavaScript never runs within these transactions. */
class WorkspaceApplicationData(private val database: AtlasDatabase) {
    companion object {
        fun createTables(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE IF NOT EXISTS workspace_data_versions (
                workspace_id TEXT PRIMARY KEY, version INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY(workspace_id) REFERENCES composable_workspaces(composable_workspace_id) ON DELETE CASCADE
            )""")
            db.execSQL("""CREATE TABLE IF NOT EXISTS workspace_application_lifecycle (
                workspace_id TEXT PRIMARY KEY, epoch INTEGER NOT NULL, enabled INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY(workspace_id) REFERENCES composable_workspaces(composable_workspace_id) ON DELETE CASCADE
            )""")
            db.execSQL("""CREATE TABLE IF NOT EXISTS workspace_application_operations (
                operation_id TEXT PRIMARY KEY, workspace_id TEXT NOT NULL, revision_id TEXT NOT NULL, epoch INTEGER NOT NULL,
                request_json TEXT NOT NULL, result_json TEXT, created_at INTEGER NOT NULL,
                FOREIGN KEY(workspace_id) REFERENCES composable_workspaces(composable_workspace_id) ON DELETE CASCADE
            )""")
            db.execSQL("""CREATE TABLE IF NOT EXISTS workspace_application_checks (
                check_id TEXT PRIMARY KEY, workspace_id TEXT NOT NULL, candidate_id TEXT NOT NULL, content_hash TEXT NOT NULL,
                kind TEXT NOT NULL, passed INTEGER NOT NULL, base_revision_id TEXT NOT NULL, data_version INTEGER NOT NULL,
                result_json TEXT NOT NULL, created_at INTEGER NOT NULL,
                FOREIGN KEY(workspace_id) REFERENCES composable_workspaces(composable_workspace_id) ON DELETE CASCADE,
                FOREIGN KEY(candidate_id) REFERENCES workspace_package_candidates(candidate_id) ON DELETE CASCADE
            )""")
            db.execSQL("""CREATE TABLE IF NOT EXISTS workspace_application_activations (
                workspace_id TEXT NOT NULL, candidate_id TEXT NOT NULL, check_id TEXT NOT NULL,
                result_json TEXT NOT NULL, created_at INTEGER NOT NULL, PRIMARY KEY(workspace_id,candidate_id),
                FOREIGN KEY(workspace_id) REFERENCES composable_workspaces(composable_workspace_id) ON DELETE CASCADE
            )""")
            for ((event, row) in listOf("INSERT" to "NEW", "UPDATE" to "NEW", "DELETE" to "OLD")) db.execSQL("""CREATE TRIGGER IF NOT EXISTS workspace_data_${event.lowercase()} AFTER $event ON workspace_records BEGIN
                INSERT OR IGNORE INTO workspace_data_versions(workspace_id,version) VALUES($row.composable_workspace_id,0);
                UPDATE workspace_data_versions SET version=version+1 WHERE workspace_id=$row.composable_workspace_id;
            END""")
        }
        fun isApplication(document: JSONObject) = document.optJSONObject("manifest")?.optString("format") == WorkspaceApplicationContract.FORMAT
    }
    fun version(workspaceId: String): Long = database.readableDatabase.rawQuery("SELECT version FROM workspace_data_versions WHERE workspace_id=?", arrayOf(workspaceId)).use { if (it.moveToFirst()) it.getLong(0) else 0 }
    fun epoch(workspaceId: String): Long = database.readableDatabase.rawQuery("SELECT epoch FROM workspace_application_lifecycle WHERE workspace_id=? AND enabled=1", arrayOf(workspaceId)).use { if (it.moveToFirst()) it.getLong(0) else -1 }
    fun fence(principal: WorkspacePrincipal) {
        val workspace = database.loadComposableWorkspace(principal.workspaceId) ?: error("TARGET_CONFLICT")
        require(workspace.realmId == principal.realmId && workspace.status == WorkspaceStatus.ACTIVE) { "TARGET_CONFLICT" }
        require(workspace.liveRevisionId == principal.revisionId && epoch(workspace.id) == principal.epoch) { "REVISION_CONFLICT" }
        require(principal.mode == WorkspaceInstanceMode.FOREGROUND) { "EXECUTION_MODE_DENIED" }
        require(isApplication(JSONObject(database.loadLiveWorkspaceRevision(workspace.id)!!.definitionJson))) { "FORMAT_TOOL_MISMATCH" }
    }
    fun query(principal: WorkspacePrincipal, params: JSONObject): JSONObject {
        fence(principal)
        WorkspaceApplicationContract.exactKeys(params, setOf("collection", "limit", "offset", "expected_data_version"), setOf("collection"))
        val collection = params.getString("collection"); collection(collection)
        val limit = if (params.has("limit")) integer(params, "limit", 1, 100).toInt() else 50
        val offset = if (params.has("offset")) integer(params, "offset", 0, 30_000).toInt() else 0
        require(limit in 1..100 && offset in 0..30_000) { "QUOTA_EXCEEDED" }
        return atomic {
            fence(principal)
            val version = version(principal.workspaceId)
            if (params.has("expected_data_version")) require(integer(params, "expected_data_version") == version) { "DATA_CONFLICT" }
            val records = JSONArray()
            rawQuery("SELECT record_id,data_json,created_at,updated_at FROM workspace_records WHERE composable_workspace_id=? AND collection_name=? ORDER BY created_at ASC,record_id ASC LIMIT ? OFFSET ?",
                arrayOf(principal.workspaceId, collection, limit.toString(), offset.toString())).use { cursor -> while (cursor.moveToNext()) {
                    records.put(JSONObject().put("id", cursor.getString(0).removePrefix("${principal.workspaceId}:")).put("data", JSONObject(cursor.getString(1))).put("created_at_ms", cursor.getLong(2)).put("updated_at_ms", cursor.getLong(3)))
                    if (records.toString().toByteArray(Charsets.UTF_8).size > 48 * 1024) { records.remove(records.length() - 1); break }
                } }
            JSONObject().put("records", records).put("data_version", version).put("next_offset", if (records.length() > 0) offset + records.length() else JSONObject.NULL)
        }
    }
    fun prepare(principal: WorkspacePrincipal, params: JSONObject): JSONObject {
        fence(principal)
        WorkspaceApplicationContract.exactKeys(params, setOf("expected_data_version", "mutations", "request_key"), setOf("expected_data_version", "mutations"))
        WorkspaceApplicationContract.checkJson(params)
        integer(params, "expected_data_version")
        require(params.toString().toByteArray(Charsets.UTF_8).size <= WorkspaceApplicationContract.MAX_MESSAGE_BYTES - 512) { "QUOTA_EXCEEDED" }
        val requestKey = if (params.has("request_key")) params.getString("request_key").also { require(it.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "VALIDATION_FAILED" } } else UUID.randomUUID().toString()
        val operationId = WorkspaceApplicationContract.hash("${principal.workspaceId}/${principal.revisionId}/${principal.epoch}/$requestKey")
        val original = JSONObject(params.toString()).apply { remove("request_key") }
        val copy = JSONObject(original.toString()); val mutations = copy.getJSONArray("mutations")
        require(mutations.length() in 1..32) { "QUOTA_EXCEEDED" }
        for (i in 0 until mutations.length()) {
            val mutation = mutations.getJSONObject(i); val kind = mutation.getString("kind")
            require(kind in setOf("create", "update", "delete")) { "VALIDATION_FAILED" }
            val fields = when (kind) { "create" -> setOf("kind", "collection", "data"); "update" -> setOf("kind", "collection", "record_id", "data"); else -> setOf("kind", "collection", "record_id") }
            WorkspaceApplicationContract.exactKeys(mutation, fields, fields)
            collection(mutation.getString("collection"))
            if (kind != "delete") require(mutation.getJSONObject("data").toString().toByteArray(Charsets.UTF_8).size <= 32 * 1024) { "QUOTA_EXCEEDED" }
            if (kind == "create") mutation.put("record_id", UUID.randomUUID().toString())
            require(mutation.getString("record_id").length in 1..200) { "VALIDATION_FAILED" }
        }
        return atomic {
            fence(principal)
            rawQuery("SELECT request_json,result_json FROM workspace_application_operations WHERE operation_id=? AND workspace_id=? AND revision_id=? AND epoch=?",
                arrayOf(operationId, principal.workspaceId, principal.revisionId, principal.epoch.toString())).use { prior ->
                if (prior.moveToFirst()) {
                    val normalized = JSONObject(prior.getString(0))
                    val rows = normalized.getJSONArray("mutations")
                    for (i in 0 until rows.length()) if (rows.getJSONObject(i).getString("kind") == "create") rows.getJSONObject(i).remove("record_id")
                    require(WorkspaceApplicationContract.canonical(normalized) == WorkspaceApplicationContract.canonical(original)) { "OPERATION_CONFLICT" }
                    return@atomic if (prior.isNull(1)) JSONObject().put("operation_id", operationId).put("status", "prepared") else JSONObject(prior.getString(1))
                }
            }
            require(version(principal.workspaceId) == copy.getLong("expected_data_version")) { "DATA_CONFLICT" }
            val retained = rawQuery("SELECT COUNT(*) FROM workspace_application_operations WHERE workspace_id=? AND result_json IS NULL", arrayOf(principal.workspaceId)).use { it.moveToFirst(); it.getInt(0) }
            require(retained < 64) { "QUOTA_EXCEEDED" }
            val total = rawQuery("SELECT COUNT(*),coalesce(SUM(length(CAST(request_json AS BLOB))+coalesce(length(CAST(result_json AS BLOB)),0)),0) FROM workspace_application_operations WHERE workspace_id=?", arrayOf(principal.workspaceId)).use { it.moveToFirst(); it.getInt(0) to it.getLong(1) }
            require(total.first < 4096 && total.second + copy.toString().toByteArray(Charsets.UTF_8).size < 16L * 1024 * 1024) { "RECEIPT_QUOTA_EXCEEDED" }
            val id = operationId
            insertOrThrow("workspace_application_operations", null, ContentValues().apply {
                put("operation_id", id); put("workspace_id", principal.workspaceId); put("revision_id", principal.revisionId); put("epoch", principal.epoch)
                put("request_json", copy.toString()); put("created_at", System.currentTimeMillis())
            })
            JSONObject().put("operation_id", id).put("status", "prepared")
        }
    }
    fun commit(principal: WorkspacePrincipal, operationId: String): JSONObject = atomic {
        fence(principal)
        val stored = rawQuery("SELECT request_json,result_json FROM workspace_application_operations WHERE operation_id=? AND workspace_id=? AND revision_id=? AND epoch=?",
            arrayOf(operationId, principal.workspaceId, principal.revisionId, principal.epoch.toString())).use { cursor ->
            require(cursor.moveToFirst()) { "OPERATION_UNAVAILABLE" }
            cursor.getString(0) to if (cursor.isNull(1)) null else cursor.getString(1)
        }
        if (stored.second != null) return@atomic JSONObject(stored.second!!)
        val request = JSONObject(stored.first)
        require(version(principal.workspaceId) == request.getLong("expected_data_version")) { "DATA_CONFLICT" }
        val mutations = request.getJSONArray("mutations"); val ids = JSONArray(); val now = System.currentTimeMillis()
        for (i in 0 until mutations.length()) {
            val mutation = mutations.getJSONObject(i); val collection = mutation.getString("collection"); val id = mutation.getString("record_id")
            val scopedId = "${principal.workspaceId}:$id"
            val storedId = rawQuery("SELECT record_id FROM workspace_records WHERE composable_workspace_id=? AND collection_name=? AND record_id=?", arrayOf(principal.workspaceId, collection, scopedId)).use { if (it.moveToFirst()) it.getString(0) else id }
            when (mutation.getString("kind")) {
                "create" -> {
                    val count = rawQuery("SELECT COUNT(*) FROM workspace_records WHERE composable_workspace_id=? AND collection_name=?", arrayOf(principal.workspaceId, collection)).use { it.moveToFirst(); it.getInt(0) }
                    require(count < 5000) { "QUOTA_EXCEEDED" }
                    insertOrThrow("workspace_records", null, ContentValues().apply {
                        put("record_id", "${principal.workspaceId}:$id"); put("composable_workspace_id", principal.workspaceId); put("collection_name", collection)
                        put("data_json", mutation.getJSONObject("data").toString()); put("created_at", now); put("updated_at", now)
                    })
                }
                "update" -> require(update("workspace_records", ContentValues().apply { put("data_json", mutation.getJSONObject("data").toString()); put("updated_at", now) },
                    "record_id=? AND composable_workspace_id=? AND collection_name=?", arrayOf(storedId, principal.workspaceId, collection)) == 1) { "DATA_CONFLICT" }
                "delete" -> require(delete("workspace_records", "record_id=? AND composable_workspace_id=? AND collection_name=?", arrayOf(storedId, principal.workspaceId, collection)) == 1) { "DATA_CONFLICT" }
            }
            ids.put(id)
        }
        val size = rawQuery("SELECT COUNT(*),coalesce(SUM(length(CAST(data_json AS BLOB))),0) FROM workspace_records WHERE composable_workspace_id=?", arrayOf(principal.workspaceId)).use { it.moveToFirst(); it.getInt(0) to it.getLong(1) }
        require(size.first <= 30_000 && size.second <= 32L * 1024 * 1024) { "QUOTA_EXCEEDED" }
        val result = JSONObject().put("operation_id", operationId).put("status", "succeeded").put("record_ids", ids).put("data_version", version(principal.workspaceId))
        require(update("workspace_application_operations", ContentValues().apply { put("result_json", result.toString()) }, "operation_id=? AND result_json IS NULL", arrayOf(operationId)) == 1)
        result
    }
    /** Scoped durable receipts let a caller recover an interrupted response without replaying writes. */
    fun inspect(principal: WorkspacePrincipal, operationId: String): JSONObject = atomic {
        fence(principal)
        rawQuery("SELECT result_json FROM workspace_application_operations WHERE operation_id=? AND workspace_id=? AND revision_id=? AND epoch=?",
            arrayOf(operationId, principal.workspaceId, principal.revisionId, principal.epoch.toString())).use { cursor ->
            require(cursor.moveToFirst()) { "OPERATION_UNAVAILABLE" }
            if (cursor.isNull(0)) JSONObject().put("operation_id", operationId).put("status", "prepared") else JSONObject(cursor.getString(0))
        }
    }
    fun cancel(principal: WorkspacePrincipal, operationId: String): JSONObject = atomic {
        val prior = inspect(principal, operationId)
        if (prior.getString("status") != "prepared") return@atomic prior
        val result = JSONObject().put("operation_id", operationId).put("status", "cancelled")
        update("workspace_application_operations", ContentValues().apply { put("result_json", result.toString()) }, "operation_id=? AND result_json IS NULL", arrayOf(operationId))
        result
    }
    /** Host UI recovery control; generated packages cannot invoke this method. */
    fun setEnabled(workspaceId: String, revisionId: String, enabled: Boolean) = atomic {
        val workspace = database.loadComposableWorkspace(workspaceId) ?: error("TARGET_CONFLICT")
        require(workspace.liveRevisionId == revisionId && workspace.status == WorkspaceStatus.ACTIVE) { "REVISION_CONFLICT" }
        val bundle = JSONObject(database.loadLiveWorkspaceRevision(workspaceId)!!.definitionJson)
        require(isApplication(bundle) && WorkspaceApplicationContract.validate(bundle).isEmpty()) { "VALIDATION_FAILED" }
        WorkspaceBleContract.requested(bundle.getJSONObject("manifest"))
        execSQL("UPDATE workspace_application_lifecycle SET epoch=epoch+1,enabled=? WHERE workspace_id=?", arrayOf(if (enabled) 1 else 0, workspaceId))
    }
    private fun integer(value: JSONObject, key: String, minimum: Long = 0, maximum: Long = 9_007_199_254_740_991L): Long {
        val raw = value.get(key)
        require(raw is Number) { "VALIDATION_FAILED" }
        val number = raw.toDouble()
        require(number.isFinite() && number % 1.0 == 0.0 && number >= minimum.toDouble() && number <= maximum.toDouble()) { "VALIDATION_FAILED" }
        return number.toLong()
    }
    private fun collection(value: String) { require(value.matches(Regex("[A-Za-z][A-Za-z0-9_-]{0,63}")) && value != "AtlasRuntimeState") { "Invalid or reserved collection" } }
    internal fun <T> atomic(block: SQLiteDatabase.() -> T): T {
        val db = database.writableDatabase; db.beginTransaction()
        return try { val result = db.block(); db.setTransactionSuccessful(); result } finally { db.endTransaction() }
    }
}
