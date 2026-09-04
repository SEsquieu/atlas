package com.grinningfrog.atlas.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.grinningfrog.atlas.model.AtlasEvent
import com.grinningfrog.atlas.model.AtlasSession
import com.grinningfrog.atlas.model.ContextStability
import com.grinningfrog.atlas.model.MotionState
import com.grinningfrog.atlas.model.MediaPurpose
import com.grinningfrog.atlas.model.MediaRef
import com.grinningfrog.atlas.model.ObservationTiming
import com.grinningfrog.atlas.model.SessionStatus
import com.grinningfrog.atlas.model.VisualObservation
import com.grinningfrog.atlas.model.AtlasTaskRun
import com.grinningfrog.atlas.model.AtlasWorkspace
import com.grinningfrog.atlas.model.DEFAULT_PERSONAL_WORKSPACE_ID
import com.grinningfrog.atlas.model.MemoryScope
import com.grinningfrog.atlas.model.TaskRunStatus
import org.json.JSONObject
import java.util.UUID

class AtlasDatabase(context: Context) : SQLiteOpenHelper(context, "atlas.db", null, 3) {
    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE workspaces (
                workspace_id TEXT PRIMARY KEY,
                kind TEXT NOT NULL,
                name TEXT NOT NULL,
                organization_id TEXT,
                created_at INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL("INSERT INTO workspaces(workspace_id,kind,name,created_at) VALUES ('$DEFAULT_PERSONAL_WORKSPACE_ID','PERSONAL','Personal',strftime('%s','now') * 1000)")
        db.execSQL(
            """CREATE TABLE sessions (
                session_id TEXT PRIMARY KEY,
                workspace_id TEXT NOT NULL,
                actor_id TEXT,
                site_id TEXT,
                station_id TEXT,
                task_run_id TEXT,
                policy_id TEXT,
                policy_revision INTEGER,
                name TEXT NOT NULL,
                goal TEXT NOT NULL,
                status TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                FOREIGN KEY(workspace_id) REFERENCES workspaces(workspace_id)
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE events (
                sequence INTEGER PRIMARY KEY AUTOINCREMENT,
                event_id TEXT NOT NULL UNIQUE,
                session_id TEXT NOT NULL,
                workspace_id TEXT NOT NULL,
                task_run_id TEXT,
                event_type TEXT NOT NULL,
                at_ms INTEGER NOT NULL,
                data_json TEXT NOT NULL,
                FOREIGN KEY(session_id) REFERENCES sessions(session_id),
                FOREIGN KEY(workspace_id) REFERENCES workspaces(workspace_id)
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX events_session_sequence ON events(session_id, sequence)")
        db.execSQL(
            """CREATE TABLE observations (
                observation_id TEXT PRIMARY KEY,
                session_id TEXT NOT NULL,
                workspace_id TEXT NOT NULL,
                task_run_id TEXT,
                media_path TEXT NOT NULL,
                media_id TEXT NOT NULL,
                media_mime TEXT NOT NULL,
                media_width INTEGER NOT NULL,
                media_height INTEGER NOT NULL,
                media_bytes INTEGER NOT NULL,
                media_sha256 TEXT NOT NULL,
                media_purpose TEXT NOT NULL,
                media_raw_bytes INTEGER NOT NULL,
                media_processing_ms INTEGER NOT NULL,
                observed_at INTEGER NOT NULL,
                available_at INTEGER NOT NULL,
                total_ms INTEGER NOT NULL,
                capture_ms INTEGER NOT NULL,
                processing_ms INTEGER NOT NULL,
                confidence REAL,
                stability TEXT NOT NULL,
                motion_state TEXT NOT NULL,
                fingerprint TEXT,
                summary TEXT,
                FOREIGN KEY(session_id) REFERENCES sessions(session_id),
                FOREIGN KEY(workspace_id) REFERENCES workspaces(workspace_id)
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX observations_session_time ON observations(session_id, observed_at DESC)")
        createEnterpriseBoundaryTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE observations ADD COLUMN media_id TEXT")
            db.execSQL("ALTER TABLE observations ADD COLUMN media_mime TEXT NOT NULL DEFAULT 'image/jpeg'")
            db.execSQL("ALTER TABLE observations ADD COLUMN media_width INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE observations ADD COLUMN media_height INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE observations ADD COLUMN media_bytes INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE observations ADD COLUMN media_sha256 TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE observations ADD COLUMN media_purpose TEXT NOT NULL DEFAULT 'STANDARD_VISION'")
            db.execSQL("ALTER TABLE observations ADD COLUMN media_raw_bytes INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE observations ADD COLUMN media_processing_ms INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE observations SET media_id = observation_id")
        }
        if (oldVersion < 3) {
            db.execSQL("CREATE TABLE workspaces (workspace_id TEXT PRIMARY KEY, kind TEXT NOT NULL, name TEXT NOT NULL, organization_id TEXT, created_at INTEGER NOT NULL)")
            db.execSQL("INSERT INTO workspaces(workspace_id,kind,name,created_at) VALUES ('$DEFAULT_PERSONAL_WORKSPACE_ID','PERSONAL','Personal',strftime('%s','now') * 1000)")
            db.execSQL("ALTER TABLE sessions ADD COLUMN workspace_id TEXT NOT NULL DEFAULT '$DEFAULT_PERSONAL_WORKSPACE_ID'")
            db.execSQL("ALTER TABLE sessions ADD COLUMN actor_id TEXT")
            db.execSQL("ALTER TABLE sessions ADD COLUMN site_id TEXT")
            db.execSQL("ALTER TABLE sessions ADD COLUMN station_id TEXT")
            db.execSQL("ALTER TABLE sessions ADD COLUMN task_run_id TEXT")
            db.execSQL("ALTER TABLE sessions ADD COLUMN policy_id TEXT")
            db.execSQL("ALTER TABLE sessions ADD COLUMN policy_revision INTEGER")
            db.execSQL("ALTER TABLE events ADD COLUMN workspace_id TEXT NOT NULL DEFAULT '$DEFAULT_PERSONAL_WORKSPACE_ID'")
            db.execSQL("ALTER TABLE events ADD COLUMN task_run_id TEXT")
            db.execSQL("ALTER TABLE observations ADD COLUMN workspace_id TEXT NOT NULL DEFAULT '$DEFAULT_PERSONAL_WORKSPACE_ID'")
            db.execSQL("ALTER TABLE observations ADD COLUMN task_run_id TEXT")
            createEnterpriseBoundaryTables(db)
        }
    }

    private fun createEnterpriseBoundaryTables(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE task_runs (
                task_run_id TEXT PRIMARY KEY,
                workspace_id TEXT NOT NULL,
                status TEXT NOT NULL,
                goal TEXT NOT NULL,
                procedure_id TEXT,
                procedure_revision_id TEXT,
                external_ref TEXT,
                current_step_id TEXT,
                started_at INTEGER,
                completed_at INTEGER,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                FOREIGN KEY(workspace_id) REFERENCES workspaces(workspace_id)
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX task_runs_workspace_status ON task_runs(workspace_id,status,updated_at DESC)")
        db.execSQL(
            """CREATE TABLE scoped_memory (
                memory_id TEXT PRIMARY KEY,
                workspace_id TEXT NOT NULL,
                scope TEXT NOT NULL,
                scope_id TEXT NOT NULL,
                kind TEXT NOT NULL,
                content TEXT NOT NULL,
                confidence REAL,
                source_event_id TEXT,
                expires_at INTEGER,
                metadata_json TEXT NOT NULL DEFAULT '{}',
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                FOREIGN KEY(workspace_id) REFERENCES workspaces(workspace_id)
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX scoped_memory_lookup ON scoped_memory(workspace_id,scope,scope_id,updated_at DESC)")
        db.execSQL(
            """CREATE TABLE runtime_policies (
                policy_id TEXT NOT NULL,
                revision INTEGER NOT NULL,
                workspace_id TEXT NOT NULL,
                scope TEXT NOT NULL,
                scope_id TEXT NOT NULL,
                policy_json TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                PRIMARY KEY(policy_id,revision),
                FOREIGN KEY(workspace_id) REFERENCES workspaces(workspace_id)
            )""".trimIndent()
        )
    }

    @Synchronized
    fun createSession(
        name: String,
        goal: String,
        workspaceId: String = DEFAULT_PERSONAL_WORKSPACE_ID,
        actorId: String? = null,
        siteId: String? = null,
        stationId: String? = null,
        taskRunId: String? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): AtlasSession {
        val session = AtlasSession(
            id = UUID.randomUUID().toString(), name = name, goal = goal,
            status = SessionStatus.IDLE, createdAtMs = nowMs, updatedAtMs = nowMs,
            workspaceId = workspaceId, actorId = actorId, siteId = siteId, stationId = stationId, taskRunId = taskRunId,
        )
        writableDatabase.transaction {
            if (workspaceId == DEFAULT_PERSONAL_WORKSPACE_ID) ensurePersonalWorkspaceLocked(this, nowMs)
            insertOrThrow("sessions", null, ContentValues().apply {
                put("session_id", session.id); put("name", name); put("goal", goal)
                put("workspace_id", workspaceId); put("actor_id", actorId); put("site_id", siteId); put("station_id", stationId); put("task_run_id", taskRunId)
                put("status", session.status.name); put("created_at", nowMs); put("updated_at", nowMs)
            })
            appendEventLocked(this, session.id, "session.created", nowMs, JSONObject().put("source", "atlas-android").toString())
        }
        return session
    }

    @Synchronized
    fun updateSessionStatus(sessionId: String, status: SessionStatus, nowMs: Long = System.currentTimeMillis()) {
        writableDatabase.transaction {
            update("sessions", ContentValues().apply { put("status", status.name); put("updated_at", nowMs) }, "session_id = ?", arrayOf(sessionId))
            appendEventLocked(this, sessionId, "session.${status.name.lowercase()}", nowMs, "{}")
        }
    }

    fun loadLatestSession(): AtlasSession? = readableDatabase.rawQuery(
        "SELECT session_id,name,goal,status,created_at,updated_at,workspace_id,actor_id,site_id,station_id,task_run_id,policy_id,policy_revision FROM sessions ORDER BY updated_at DESC LIMIT 1", null
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else AtlasSession(
            id = cursor.getString(0), name = cursor.getString(1), goal = cursor.getString(2),
            status = SessionStatus.valueOf(cursor.getString(3)), createdAtMs = cursor.getLong(4), updatedAtMs = cursor.getLong(5),
            workspaceId = cursor.getString(6), actorId = cursor.nullableString(7), siteId = cursor.nullableString(8),
            stationId = cursor.nullableString(9), taskRunId = cursor.nullableString(10), policyId = cursor.nullableString(11),
            policyRevision = if (cursor.isNull(12)) null else cursor.getInt(12),
        )
    }

    @Synchronized
    fun appendEvent(sessionId: String, type: String, data: JSONObject = JSONObject(), atMs: Long = System.currentTimeMillis()): AtlasEvent =
        appendEventLocked(writableDatabase, sessionId, type, atMs, data.toString())

    private fun appendEventLocked(db: SQLiteDatabase, sessionId: String, type: String, atMs: Long, json: String): AtlasEvent {
        val eventId = UUID.randomUUID().toString()
        val scope = sessionScope(db, sessionId)
        val sequence = db.insertOrThrow("events", null, ContentValues().apply {
            put("event_id", eventId); put("session_id", sessionId); put("event_type", type); put("at_ms", atMs); put("data_json", json)
            put("workspace_id", scope.first); put("task_run_id", scope.second)
        })
        return AtlasEvent(sequence, eventId, sessionId, type, atMs, json, scope.first, scope.second)
    }

    @Synchronized
    fun saveObservation(observation: VisualObservation) {
        writableDatabase.transaction {
            insertOrThrow("observations", null, ContentValues().apply {
                put("observation_id", observation.id); put("session_id", observation.sessionId); put("media_path", observation.media.storageKey)
                val scope = sessionScope(this@transaction, observation.sessionId)
                put("workspace_id", scope.first); put("task_run_id", scope.second)
                put("media_id", observation.media.id); put("media_mime", observation.media.mimeType)
                put("media_width", observation.media.width); put("media_height", observation.media.height); put("media_bytes", observation.media.byteSize)
                put("media_sha256", observation.media.sha256); put("media_purpose", observation.media.purpose.name)
                put("media_raw_bytes", observation.media.rawByteSize); put("media_processing_ms", observation.media.processingMs)
                put("observed_at", observation.observedAtMs); put("available_at", observation.availableAtMs)
                put("total_ms", observation.timing.totalMs); put("capture_ms", observation.timing.captureMs); put("processing_ms", observation.timing.processingMs)
                observation.confidence?.let { put("confidence", it) }
                put("stability", observation.stability.name); put("motion_state", observation.motionState.name)
                put("fingerprint", observation.sceneFingerprint); put("summary", observation.summary)
            })
            appendEventLocked(this, observation.sessionId, "observation.captured", observation.availableAtMs, JSONObject().apply {
                put("observationId", observation.id); put("observedAtMs", observation.observedAtMs); put("availableAtMs", observation.availableAtMs)
                put("mediaId", observation.media.id); put("mediaBytes", observation.media.byteSize); put("mediaSha256", observation.media.sha256)
                put("totalMs", observation.timing.totalMs); put("captureMs", observation.timing.captureMs)
                put("processingMs", observation.timing.processingMs); put("motionState", observation.motionState.name)
            }.toString())
            update("sessions", ContentValues().apply { put("updated_at", observation.availableAtMs) }, "session_id = ?", arrayOf(observation.sessionId))
        }
    }

    fun loadLatestObservation(sessionId: String): VisualObservation? = readableDatabase.rawQuery(
        """SELECT observation_id,media_path,media_id,media_mime,media_width,media_height,media_bytes,media_sha256,media_purpose,
            media_raw_bytes,media_processing_ms,observed_at,available_at,total_ms,capture_ms,processing_ms,
            confidence,stability,motion_state,fingerprint,summary FROM observations WHERE session_id = ? ORDER BY observed_at DESC LIMIT 1""".trimIndent(),
        arrayOf(sessionId)
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else VisualObservation(
            id = cursor.getString(0), sessionId = sessionId,
            media = MediaRef(cursor.getString(2) ?: cursor.getString(0), cursor.getString(1), cursor.getString(3), cursor.getInt(4), cursor.getInt(5), cursor.getLong(6), cursor.getString(7),
                MediaPurpose.valueOf(cursor.getString(8)), cursor.getLong(9), cursor.getLong(10)),
            observedAtMs = cursor.getLong(11), availableAtMs = cursor.getLong(12),
            timing = ObservationTiming(cursor.getLong(13), cursor.getLong(14), cursor.getLong(15)),
            confidence = if (cursor.isNull(16)) null else cursor.getDouble(16),
            stability = ContextStability.valueOf(cursor.getString(17)), motionState = MotionState.valueOf(cursor.getString(18)),
            sceneFingerprint = if (cursor.isNull(19)) null else cursor.getString(19), summary = if (cursor.isNull(20)) null else cursor.getString(20),
        )
    }

    fun loadRecentEvents(sessionId: String, limit: Int = 100): List<AtlasEvent> = readableDatabase.rawQuery(
        "SELECT sequence,event_id,session_id,event_type,at_ms,data_json,workspace_id,task_run_id FROM events WHERE session_id = ? ORDER BY sequence DESC LIMIT ?",
        arrayOf(sessionId, limit.toString())
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(AtlasEvent(cursor.getLong(0), cursor.getString(1), cursor.getString(2), cursor.getString(3), cursor.getLong(4), cursor.getString(5), cursor.getString(6), cursor.nullableString(7)))
        }.reversed()
    }

    @Synchronized
    fun upsertWorkspace(workspace: AtlasWorkspace, nowMs: Long = System.currentTimeMillis()) {
        writableDatabase.transaction {
            insertWithOnConflict("workspaces", null, ContentValues().apply {
                put("workspace_id", workspace.id); put("kind", workspace.kind.name); put("name", workspace.name)
                put("organization_id", workspace.organizationId); put("created_at", nowMs)
            }, SQLiteDatabase.CONFLICT_IGNORE)
            update("workspaces", ContentValues().apply {
                put("kind", workspace.kind.name); put("name", workspace.name); put("organization_id", workspace.organizationId)
            }, "workspace_id = ?", arrayOf(workspace.id))
        }
    }

    @Synchronized
    fun createTaskRun(workspaceId: String, goal: String, procedureId: String? = null, procedureRevisionId: String? = null, externalRef: String? = null, nowMs: Long = System.currentTimeMillis()): AtlasTaskRun {
        val task = AtlasTaskRun(UUID.randomUUID().toString(), workspaceId, TaskRunStatus.PENDING, goal, procedureId, procedureRevisionId, externalRef)
        writableDatabase.insertOrThrow("task_runs", null, ContentValues().apply {
            put("task_run_id", task.id); put("workspace_id", workspaceId); put("status", task.status.name); put("goal", goal)
            put("procedure_id", procedureId); put("procedure_revision_id", procedureRevisionId); put("external_ref", externalRef)
            put("created_at", nowMs); put("updated_at", nowMs)
        })
        return task
    }

    @Synchronized
    fun putScopedMemory(workspaceId: String, scope: MemoryScope, scopeId: String, kind: String, content: String, confidence: Double? = null, expiresAtMs: Long? = null, nowMs: Long = System.currentTimeMillis()): String {
        val id = UUID.randomUUID().toString()
        writableDatabase.insertOrThrow("scoped_memory", null, ContentValues().apply {
            put("memory_id", id); put("workspace_id", workspaceId); put("scope", scope.name); put("scope_id", scopeId)
            put("kind", kind); put("content", content); put("confidence", confidence); put("expires_at", expiresAtMs)
            put("created_at", nowMs); put("updated_at", nowMs)
        })
        return id
    }

    private fun ensurePersonalWorkspaceLocked(db: SQLiteDatabase, nowMs: Long) {
        db.insertWithOnConflict("workspaces", null, ContentValues().apply {
            put("workspace_id", DEFAULT_PERSONAL_WORKSPACE_ID); put("kind", "PERSONAL"); put("name", "Personal"); put("created_at", nowMs)
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    private fun sessionScope(db: SQLiteDatabase, sessionId: String): Pair<String, String?> = db.rawQuery(
        "SELECT workspace_id,task_run_id FROM sessions WHERE session_id = ?", arrayOf(sessionId)
    ).use { cursor ->
        check(cursor.moveToFirst()) { "Session not found: $sessionId" }
        cursor.getString(0) to cursor.nullableString(1)
    }
}

private fun android.database.Cursor.nullableString(index: Int): String? = if (isNull(index)) null else getString(index)

private inline fun <T> SQLiteDatabase.transaction(block: SQLiteDatabase.() -> T): T {
    beginTransaction()
    return try { val result = block(); setTransactionSuccessful(); result } finally { endTransaction() }
}
