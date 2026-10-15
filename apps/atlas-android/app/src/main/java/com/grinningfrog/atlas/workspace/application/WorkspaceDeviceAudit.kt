package com.grinningfrog.atlas.workspace.application

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.grinningfrog.atlas.data.AtlasDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONArray
import org.json.JSONObject

/** Bounded per-workspace diagnostic history, independent of active conversation/session lifetime. */
class WorkspaceDeviceAudit(private val database: AtlasDatabase) {
    companion object {
        fun createTables(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS workspace_device_audit(sequence INTEGER PRIMARY KEY AUTOINCREMENT,workspace_id TEXT NOT NULL,at_ms INTEGER NOT NULL,event_json TEXT NOT NULL,FOREIGN KEY(workspace_id) REFERENCES composable_workspaces(composable_workspace_id) ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS workspace_device_audit_workspace ON workspace_device_audit(workspace_id,sequence)")
        }
    }
    fun append(workspaceId: String, event: JSONObject) {
        val db=database.writableDatabase
        db.beginTransaction()
        try {
            // Teardown may race deletion. Never recreate a deleted workspace or orphan its diagnostics.
            if(database.loadComposableWorkspace(workspaceId)!=null) {
                val raw=event.toString();require(raw.toByteArray().size<=4096)
                db.insertOrThrow("workspace_device_audit",null,ContentValues().apply { put("workspace_id",workspaceId);put("at_ms",event.getLong("at_ms"));put("event_json",raw) })
                db.execSQL("DELETE FROM workspace_device_audit WHERE workspace_id=? AND sequence NOT IN (SELECT sequence FROM workspace_device_audit WHERE workspace_id=? ORDER BY sequence DESC LIMIT 512)",arrayOf(workspaceId,workspaceId))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun snapshot(workspaceId: String, limit: Int = 512): JSONObject {
        val bounded=limit.coerceIn(1,512)
        val events=JSONArray()
        database.readableDatabase.rawQuery("SELECT sequence,event_json FROM workspace_device_audit WHERE workspace_id=? ORDER BY sequence DESC LIMIT $bounded",arrayOf(workspaceId)).use { cursor ->
            val rows=mutableListOf<JSONObject>();while(cursor.moveToNext())rows+=JSONObject(cursor.getString(1)).put("audit_sequence",cursor.getLong(0));rows.asReversed().forEach { events.put(it) }
        }
        return JSONObject().put("scope","Newest 512 workspace device audit records, across sessions and revisions; payload bytes and credentials excluded. Diagnostic history conveys no executable authority.")
            .put("retention_limit",512).put("returned_limit",bounded).put("events",events)
    }
    /** Own scope outlives the panel long enough to drain teardown events; never blocks Android's UI thread. */
    internal class Writer(database: AtlasDatabase, private val workspaceId: String) {
        private val queue=Channel<JSONObject>(256)
        private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        private var dropped=0
        init { scope.launch { val store=WorkspaceDeviceAudit(database);for(event in queue)runCatching { store.append(workspaceId,event) };scope.cancel() } }
        fun record(event: JSONObject) {
            if(dropped>0) { event.put("audit_queue_dropped",dropped); dropped=0 }
            if(queue.trySend(JSONObject(event.toString())).isFailure)dropped++
        }
        fun close() { queue.close() }
    }
}
