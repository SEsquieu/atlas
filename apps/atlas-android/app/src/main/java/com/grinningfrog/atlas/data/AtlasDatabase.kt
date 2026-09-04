package com.grinningfrog.atlas.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.Cursor
import com.grinningfrog.atlas.model.AtlasEvent
import com.grinningfrog.atlas.model.AgentTurn
import com.grinningfrog.atlas.model.AtlasMessage
import com.grinningfrog.atlas.model.AtlasSession
import com.grinningfrog.atlas.model.AtlasToolCall
import com.grinningfrog.atlas.model.AtlasTaskRun
import com.grinningfrog.atlas.model.AtlasWorkspace
import com.grinningfrog.atlas.model.ContextStability
import com.grinningfrog.atlas.model.ContextMode
import com.grinningfrog.atlas.model.ClarificationAmbiguity
import com.grinningfrog.atlas.model.ClarificationStatus
import com.grinningfrog.atlas.model.DeliveryStatus
import com.grinningfrog.atlas.model.DEFAULT_PERSONAL_WORKSPACE_ID
import com.grinningfrog.atlas.model.MotionState
import com.grinningfrog.atlas.model.MemoryItem
import com.grinningfrog.atlas.model.MemoryKind
import com.grinningfrog.atlas.model.MemoryStatus
import com.grinningfrog.atlas.model.MemoryScope
import com.grinningfrog.atlas.model.MessageKind
import com.grinningfrog.atlas.model.MessageRole
import com.grinningfrog.atlas.model.MediaPurpose
import com.grinningfrog.atlas.model.MediaRef
import com.grinningfrog.atlas.model.ObservationTiming
import com.grinningfrog.atlas.model.PermissionPolicy
import com.grinningfrog.atlas.model.PendingClarification
import com.grinningfrog.atlas.model.SessionPermissions
import com.grinningfrog.atlas.model.SessionStatus
import com.grinningfrog.atlas.model.SessionSummary
import com.grinningfrog.atlas.model.SpeechSegment
import com.grinningfrog.atlas.model.SpeechSegmentStatus
import com.grinningfrog.atlas.model.ToolCallStatus
import com.grinningfrog.atlas.model.ToolRisk
import com.grinningfrog.atlas.model.TaskRunStatus
import com.grinningfrog.atlas.model.TurnStatus
import com.grinningfrog.atlas.model.VisualObservation
import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID

class AtlasDatabase(context: Context) : SQLiteOpenHelper(context, "atlas.db", null, 8) {
    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    private fun createOwnershipTables(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS workspaces (
            workspace_id TEXT PRIMARY KEY, kind TEXT NOT NULL, name TEXT NOT NULL,
            organization_id TEXT, created_at INTEGER NOT NULL
        )""".trimIndent())
        db.insertWithOnConflict("workspaces", null, ContentValues().apply {
            put("workspace_id", DEFAULT_PERSONAL_WORKSPACE_ID); put("kind", "PERSONAL"); put("name", "Personal"); put("created_at", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_IGNORE)
        db.execSQL("""CREATE TABLE IF NOT EXISTS task_runs (
            task_run_id TEXT PRIMARY KEY, workspace_id TEXT NOT NULL, status TEXT NOT NULL, goal TEXT NOT NULL,
            procedure_id TEXT, procedure_revision_id TEXT, external_ref TEXT, current_step_id TEXT,
            started_at INTEGER, completed_at INTEGER, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
            FOREIGN KEY(workspace_id) REFERENCES workspaces(workspace_id)
        )""".trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS task_runs_workspace_status ON task_runs(workspace_id,status,updated_at DESC)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS runtime_policies (
            policy_id TEXT NOT NULL, revision INTEGER NOT NULL, workspace_id TEXT NOT NULL,
            scope TEXT NOT NULL, scope_id TEXT NOT NULL, policy_json TEXT NOT NULL, created_at INTEGER NOT NULL,
            PRIMARY KEY(policy_id,revision), FOREIGN KEY(workspace_id) REFERENCES workspaces(workspace_id)
        )""".trimIndent())
    }

    private fun createAgentRuntimeTables(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS turns (
                turn_id TEXT PRIMARY KEY,
                session_id TEXT NOT NULL,
                status TEXT NOT NULL,
                trigger TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                step_count INTEGER NOT NULL DEFAULT 0,
                error TEXT,
                FOREIGN KEY(session_id) REFERENCES sessions(session_id)
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS turns_session_time ON turns(session_id, created_at DESC)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS messages (
                sequence INTEGER PRIMARY KEY AUTOINCREMENT,
                message_id TEXT NOT NULL UNIQUE,
                session_id TEXT NOT NULL,
                turn_id TEXT NOT NULL,
                role TEXT NOT NULL,
                kind TEXT NOT NULL,
                content TEXT NOT NULL,
                tool_call_id TEXT,
                tool_calls_json TEXT,
                delivery_status TEXT NOT NULL DEFAULT 'NOT_APPLICABLE',
                delivered_content TEXT,
                interrupted_sentence TEXT,
                created_at INTEGER NOT NULL,
                FOREIGN KEY(session_id) REFERENCES sessions(session_id),
                FOREIGN KEY(turn_id) REFERENCES turns(turn_id)
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS messages_session_sequence ON messages(session_id, sequence)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS speech_segments (
                segment_id TEXT PRIMARY KEY,
                message_id TEXT NOT NULL,
                session_id TEXT NOT NULL,
                turn_id TEXT NOT NULL,
                sentence_index INTEGER NOT NULL,
                text TEXT NOT NULL,
                status TEXT NOT NULL,
                queued_at INTEGER NOT NULL,
                started_at INTEGER,
                completed_at INTEGER,
                UNIQUE(message_id, sentence_index),
                FOREIGN KEY(session_id) REFERENCES sessions(session_id),
                FOREIGN KEY(turn_id) REFERENCES turns(turn_id)
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS speech_message_index ON speech_segments(message_id, sentence_index)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS tool_calls (
                tool_call_id TEXT PRIMARY KEY,
                session_id TEXT NOT NULL,
                turn_id TEXT NOT NULL,
                name TEXT NOT NULL,
                arguments_json TEXT NOT NULL,
                status TEXT NOT NULL,
                risk TEXT NOT NULL,
                requires_confirmation INTEGER NOT NULL,
                idempotency_key TEXT NOT NULL UNIQUE,
                reason TEXT,
                result_json TEXT,
                error TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                FOREIGN KEY(session_id) REFERENCES sessions(session_id),
                FOREIGN KEY(turn_id) REFERENCES turns(turn_id)
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS tool_calls_turn_status ON tool_calls(turn_id, status)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS memory_items (
                memory_id TEXT PRIMARY KEY,
                session_id TEXT NOT NULL,
                workspace_id TEXT NOT NULL,
                scope TEXT NOT NULL,
                scope_id TEXT NOT NULL,
                kind TEXT NOT NULL,
                content TEXT NOT NULL,
                status TEXT NOT NULL,
                confidence REAL NOT NULL,
                source_turn_id TEXT,
                evidence_observation_id TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                expires_at INTEGER,
                FOREIGN KEY(session_id) REFERENCES sessions(session_id),
                FOREIGN KEY(workspace_id) REFERENCES workspaces(workspace_id)
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS memory_session_status ON memory_items(session_id, status, kind)")
        db.execSQL("CREATE INDEX IF NOT EXISTS memory_scope_status ON memory_items(workspace_id,scope,scope_id,status,updated_at DESC)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS session_summaries (
                session_id TEXT PRIMARY KEY,
                summary TEXT NOT NULL,
                through_message_sequence INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                FOREIGN KEY(session_id) REFERENCES sessions(session_id)
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS clarifications (
                clarification_id TEXT PRIMARY KEY,
                session_id TEXT NOT NULL,
                source_turn_id TEXT NOT NULL,
                question TEXT NOT NULL,
                reason TEXT NOT NULL,
                ambiguity TEXT NOT NULL,
                options_json TEXT NOT NULL DEFAULT '[]',
                blocking INTEGER NOT NULL DEFAULT 1,
                status TEXT NOT NULL,
                context_observation_ids_json TEXT NOT NULL DEFAULT '[]',
                freshness_requirement TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                expires_at INTEGER,
                deferred_count INTEGER NOT NULL DEFAULT 0,
                normalized_answer TEXT,
                resolved_by_turn_id TEXT,
                FOREIGN KEY(session_id) REFERENCES sessions(session_id),
                FOREIGN KEY(source_turn_id) REFERENCES turns(turn_id)
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS clarifications_session_status ON clarifications(session_id,status,updated_at DESC)")
    }

    override fun onCreate(db: SQLiteDatabase) {
        createOwnershipTables(db)
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
                context_mode TEXT NOT NULL DEFAULT 'MANUAL',
                permissions_json TEXT NOT NULL DEFAULT '{}',
                FOREIGN KEY(workspace_id) REFERENCES workspaces(workspace_id),
                FOREIGN KEY(task_run_id) REFERENCES task_runs(task_run_id)
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
                FOREIGN KEY(workspace_id) REFERENCES workspaces(workspace_id),
                FOREIGN KEY(task_run_id) REFERENCES task_runs(task_run_id)
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
                interpreted_at INTEGER,
                FOREIGN KEY(session_id) REFERENCES sessions(session_id),
                FOREIGN KEY(workspace_id) REFERENCES workspaces(workspace_id),
                FOREIGN KEY(task_run_id) REFERENCES task_runs(task_run_id)
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX observations_session_time ON observations(session_id, observed_at DESC)")
        createAgentRuntimeTables(db)
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
            db.execSQL("ALTER TABLE sessions ADD COLUMN context_mode TEXT NOT NULL DEFAULT 'MANUAL'")
            db.execSQL("ALTER TABLE observations ADD COLUMN interpreted_at INTEGER")
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE sessions ADD COLUMN permissions_json TEXT NOT NULL DEFAULT '{}'")
            createAgentRuntimeTables(db)
        }
        if (oldVersion in 4 until 5) {
            db.execSQL("ALTER TABLE memory_items ADD COLUMN evidence_observation_id TEXT")
        }
        if (oldVersion in 4 until 6) {
            db.execSQL("ALTER TABLE messages ADD COLUMN delivery_status TEXT NOT NULL DEFAULT 'NOT_APPLICABLE'")
            db.execSQL("ALTER TABLE messages ADD COLUMN delivered_content TEXT")
            db.execSQL("ALTER TABLE messages ADD COLUMN interrupted_sentence TEXT")
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS speech_segments (
                    segment_id TEXT PRIMARY KEY,
                    message_id TEXT NOT NULL,
                    session_id TEXT NOT NULL,
                    turn_id TEXT NOT NULL,
                    sentence_index INTEGER NOT NULL,
                    text TEXT NOT NULL,
                    status TEXT NOT NULL,
                    queued_at INTEGER NOT NULL,
                    started_at INTEGER,
                    completed_at INTEGER,
                    UNIQUE(message_id, sentence_index),
                    FOREIGN KEY(session_id) REFERENCES sessions(session_id),
                    FOREIGN KEY(turn_id) REFERENCES turns(turn_id)
                )""".trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS speech_message_index ON speech_segments(message_id, sentence_index)")
        }
        if (oldVersion < 7) {
            createOwnershipTables(db)
            addColumnIfMissing(db, "sessions", "workspace_id", "TEXT NOT NULL DEFAULT '$DEFAULT_PERSONAL_WORKSPACE_ID'")
            addColumnIfMissing(db, "sessions", "actor_id", "TEXT")
            addColumnIfMissing(db, "sessions", "site_id", "TEXT")
            addColumnIfMissing(db, "sessions", "station_id", "TEXT")
            addColumnIfMissing(db, "sessions", "task_run_id", "TEXT")
            addColumnIfMissing(db, "sessions", "policy_id", "TEXT")
            addColumnIfMissing(db, "sessions", "policy_revision", "INTEGER")
            addColumnIfMissing(db, "events", "workspace_id", "TEXT NOT NULL DEFAULT '$DEFAULT_PERSONAL_WORKSPACE_ID'")
            addColumnIfMissing(db, "events", "task_run_id", "TEXT")
            addColumnIfMissing(db, "observations", "workspace_id", "TEXT NOT NULL DEFAULT '$DEFAULT_PERSONAL_WORKSPACE_ID'")
            addColumnIfMissing(db, "observations", "task_run_id", "TEXT")
            addColumnIfMissing(db, "memory_items", "workspace_id", "TEXT NOT NULL DEFAULT '$DEFAULT_PERSONAL_WORKSPACE_ID'")
            addColumnIfMissing(db, "memory_items", "scope", "TEXT NOT NULL DEFAULT 'SESSION'")
            addColumnIfMissing(db, "memory_items", "scope_id", "TEXT")
            db.execSQL("UPDATE memory_items SET workspace_id=coalesce((SELECT workspace_id FROM sessions WHERE sessions.session_id=memory_items.session_id),'$DEFAULT_PERSONAL_WORKSPACE_ID')")
            db.execSQL("UPDATE memory_items SET scope=CASE WHEN kind='DURABLE' THEN 'WORKSPACE' ELSE 'SESSION' END")
            db.execSQL("UPDATE memory_items SET scope_id=CASE WHEN scope='WORKSPACE' THEN workspace_id ELSE session_id END")
            db.execSQL("CREATE INDEX IF NOT EXISTS memory_scope_status ON memory_items(workspace_id,scope,scope_id,status,updated_at DESC)")
        }
        if (oldVersion < 8) createAgentRuntimeTables(db)
    }

    @Synchronized
    fun saveClarification(clarification: PendingClarification) {
        writableDatabase.transaction {
            val supersededIds = mutableListOf<String>()
            rawQuery("SELECT clarification_id FROM clarifications WHERE session_id = ? AND status IN ('WAITING','DEFERRED')", arrayOf(clarification.sessionId)).use { cursor ->
                while (cursor.moveToNext()) supersededIds += cursor.getString(0)
            }
            update("clarifications", ContentValues().apply {
                put("status", ClarificationStatus.ABANDONED.name); put("updated_at", clarification.createdAtMs)
            }, "session_id = ? AND status IN ('WAITING','DEFERRED')", arrayOf(clarification.sessionId))
            supersededIds.forEach { clarificationId ->
                appendEventLocked(this, clarification.sessionId, "clarification.superseded", clarification.createdAtMs,
                    JSONObject().put("clarificationId", clarificationId).put("supersededByClarificationId", clarification.id).toString())
            }
            insertOrThrow("clarifications", null, ContentValues().apply {
                put("clarification_id", clarification.id); put("session_id", clarification.sessionId); put("source_turn_id", clarification.sourceTurnId)
                put("question", clarification.question); put("reason", clarification.reason); put("ambiguity", clarification.ambiguity.name)
                put("options_json", JSONArray(clarification.options).toString()); put("blocking", if (clarification.blocking) 1 else 0)
                put("status", clarification.status.name); put("context_observation_ids_json", JSONArray(clarification.contextObservationIds).toString())
                put("freshness_requirement", clarification.freshnessRequirement); put("created_at", clarification.createdAtMs)
                put("updated_at", clarification.updatedAtMs); put("expires_at", clarification.expiresAtMs); put("deferred_count", clarification.deferredCount)
            })
            appendEventLocked(this, clarification.sessionId, "clarification.requested", clarification.createdAtMs, JSONObject().apply {
                put("clarificationId", clarification.id); put("sourceTurnId", clarification.sourceTurnId); put("question", clarification.question)
                put("reason", clarification.reason); put("ambiguity", clarification.ambiguity.name.lowercase()); put("blocking", clarification.blocking)
                put("options", JSONArray(clarification.options)); clarification.expiresAtMs?.let { put("expiresAtMs", it) }
            }.toString())
        }
    }

    @Synchronized
    fun updateClarification(
        clarificationId: String,
        status: ClarificationStatus,
        resolvedByTurnId: String? = null,
        normalizedAnswer: String? = null,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        writableDatabase.transaction {
            val identity = rawQuery("SELECT session_id,deferred_count FROM clarifications WHERE clarification_id = ?", arrayOf(clarificationId)).use { cursor ->
                if (!cursor.moveToFirst()) null else cursor.getString(0) to cursor.getInt(1)
            } ?: error("Unknown clarification $clarificationId")
            update("clarifications", ContentValues().apply {
                put("status", status.name); put("updated_at", nowMs); put("resolved_by_turn_id", resolvedByTurnId)
                put("normalized_answer", normalizedAnswer); if (status == ClarificationStatus.DEFERRED) put("deferred_count", identity.second + 1)
            }, "clarification_id = ?", arrayOf(clarificationId))
            appendEventLocked(this, identity.first, "clarification.${status.name.lowercase()}", nowMs, JSONObject().apply {
                put("clarificationId", clarificationId); resolvedByTurnId?.let { put("resolvedByTurnId", it) }
                normalizedAnswer?.let { put("normalizedAnswer", it) }
            }.toString())
        }
    }

    fun loadPendingClarification(sessionId: String): PendingClarification? = readableDatabase.rawQuery(
        """SELECT clarification_id,session_id,source_turn_id,question,reason,ambiguity,options_json,blocking,status,
            context_observation_ids_json,freshness_requirement,created_at,updated_at,expires_at,deferred_count,normalized_answer,resolved_by_turn_id
            FROM clarifications WHERE session_id = ? AND status IN ('WAITING','DEFERRED') ORDER BY updated_at DESC LIMIT 1""".trimIndent(), arrayOf(sessionId)
    ).use { cursor -> if (!cursor.moveToFirst()) null else cursor.toClarification() }

    @Synchronized
    fun expirePendingClarification(sessionId: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val pending = loadPendingClarification(sessionId) ?: return false
        if (pending.expiresAtMs == null || pending.expiresAtMs > nowMs) return false
        updateClarification(pending.id, ClarificationStatus.EXPIRED, nowMs = nowMs)
        return true
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
            workspaceId = workspaceId, actorId = actorId, siteId = siteId,
            stationId = stationId, taskRunId = taskRunId,
        )
        writableDatabase.transaction {
            insertOrThrow("sessions", null, ContentValues().apply {
                put("session_id", session.id); put("name", name); put("goal", goal)
                put("workspace_id", workspaceId); put("actor_id", actorId); put("site_id", siteId)
                put("station_id", stationId); put("task_run_id", taskRunId)
                put("status", session.status.name); put("created_at", nowMs); put("updated_at", nowMs); put("context_mode", session.contextMode.name)
                put("permissions_json", session.permissions.toJson().toString())
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

    @Synchronized
    fun updateContextMode(sessionId: String, mode: ContextMode, nowMs: Long = System.currentTimeMillis()) {
        writableDatabase.transaction {
            update("sessions", ContentValues().apply { put("context_mode", mode.name); put("updated_at", nowMs) }, "session_id = ?", arrayOf(sessionId))
            appendEventLocked(this, sessionId, "context.${mode.name.lowercase()}", nowMs, JSONObject().put("mode", mode.name).toString())
        }
    }

    fun loadLatestSession(): AtlasSession? = readableDatabase.rawQuery(
        """SELECT session_id,name,goal,status,created_at,updated_at,context_mode,permissions_json,
            workspace_id,actor_id,site_id,station_id,task_run_id,policy_id,policy_revision
            FROM sessions ORDER BY updated_at DESC LIMIT 1""".trimIndent(), null
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else AtlasSession(
            id = cursor.getString(0), name = cursor.getString(1), goal = cursor.getString(2),
            status = SessionStatus.valueOf(cursor.getString(3)), createdAtMs = cursor.getLong(4), updatedAtMs = cursor.getLong(5),
            contextMode = ContextMode.valueOf(cursor.getString(6)), permissions = permissionsFromJson(cursor.getString(7)),
            workspaceId = cursor.getString(8), actorId = cursor.nullableString(9), siteId = cursor.nullableString(10),
            stationId = cursor.nullableString(11), taskRunId = cursor.nullableString(12), policyId = cursor.nullableString(13),
            policyRevision = if (cursor.isNull(14)) null else cursor.getInt(14),
        )
    }

    /** Complete, portable state bundle for an explicit user export. Secrets are never stored here. */
    fun exportSession(sessionId: String): JSONObject = JSONObject().apply {
        put("format", "atlas.session.v1")
        put("exportedAtMs", System.currentTimeMillis())
        put("session", queryRows("sessions", "session_id = ?", arrayOf(sessionId)))
        put("turns", queryRows("turns", "session_id = ?", arrayOf(sessionId)))
        put("messages", queryRows("messages", "session_id = ?", arrayOf(sessionId), "sequence"))
        put("speechSegments", queryRows("speech_segments", "session_id = ?", arrayOf(sessionId), "queued_at"))
        put("toolCalls", queryRows("tool_calls", "session_id = ?", arrayOf(sessionId), "created_at"))
        put("clarifications", queryRows("clarifications", "session_id = ?", arrayOf(sessionId), "created_at"))
        put("memory", queryRows("memory_items", "session_id = ?", arrayOf(sessionId), "created_at"))
        put("summaries", queryRows("session_summaries", "session_id = ?", arrayOf(sessionId)))
        put("observations", queryRows("observations", "session_id = ?", arrayOf(sessionId), "observed_at"))
        put("events", queryRows("events", "session_id = ?", arrayOf(sessionId), "sequence"))
    }

    fun exportTranscript(sessionId: String): String {
        val session = readableDatabase.rawQuery("SELECT name,goal,created_at FROM sessions WHERE session_id = ?", arrayOf(sessionId)).use { cursor ->
            check(cursor.moveToFirst()) { "Unknown session" }
            Triple(cursor.getString(0), cursor.getString(1), cursor.getLong(2))
        }
        return buildString {
            appendLine(session.first)
            appendLine("Goal: ${session.second}")
            appendLine("Session ID: $sessionId")
            appendLine()
            loadMessages(sessionId, limit = 10_000).filter { it.kind == MessageKind.DIALOGUE }.forEach { message ->
                append(if (message.role == MessageRole.USER) "You: " else "Atlas: ")
                appendLine(message.content)
            }
        }
    }

    /** Deletes all session-owned durable state and returns private media keys for secure removal. */
    @Synchronized
    fun deleteSession(sessionId: String): List<String> {
        val mediaKeys = mediaKeys("o.session_id = ?", arrayOf(sessionId))
        writableDatabase.transaction {
            delete("speech_segments", "session_id = ?", arrayOf(sessionId))
            delete("tool_calls", "session_id = ?", arrayOf(sessionId))
            delete("messages", "session_id = ?", arrayOf(sessionId))
            delete("clarifications", "session_id = ?", arrayOf(sessionId))
            delete("session_summaries", "session_id = ?", arrayOf(sessionId))
            delete("memory_items", "session_id = ?", arrayOf(sessionId))
            delete("observations", "session_id = ?", arrayOf(sessionId))
            delete("events", "session_id = ?", arrayOf(sessionId))
            delete("turns", "session_id = ?", arrayOf(sessionId))
            check(delete("sessions", "session_id = ?", arrayOf(sessionId)) == 1) { "Unknown session" }
        }
        return mediaKeys
    }

    /** Removes image derivatives from completed sessions after the configured retention window. */
    @Synchronized
    fun pruneExpiredObservations(cutoffMs: Long): List<String> {
        val where = "s.status = 'DONE' AND o.observed_at < ?"
        val args = arrayOf(cutoffMs.toString())
        val mediaKeys = mediaKeys(where, args)
        writableDatabase.execSQL(
            "DELETE FROM observations WHERE observation_id IN (SELECT o.observation_id FROM observations o JOIN sessions s ON s.session_id=o.session_id WHERE $where)",
            args,
        )
        return mediaKeys
    }

    private fun mediaKeys(where: String, args: Array<String>): List<String> = readableDatabase.rawQuery(
        "SELECT o.media_path FROM observations o JOIN sessions s ON s.session_id=o.session_id WHERE $where", args,
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }

    private fun queryRows(table: String, selection: String, args: Array<String>, orderBy: String? = null): JSONArray {
        val result = JSONArray()
        readableDatabase.query(table, null, selection, args, null, null, orderBy).use { cursor ->
            while (cursor.moveToNext()) result.put(cursor.toJson())
        }
        return result
    }

    private fun Cursor.toJson() = JSONObject().also { row ->
        columnNames.forEachIndexed { index, name ->
            row.put(name, when (getType(index)) {
                Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                Cursor.FIELD_TYPE_INTEGER -> getLong(index)
                Cursor.FIELD_TYPE_FLOAT -> getDouble(index)
                Cursor.FIELD_TYPE_BLOB -> "<binary omitted>"
                else -> getString(index)
            })
        }
    }

    @Synchronized
    fun upsertWorkspace(workspace: AtlasWorkspace, nowMs: Long = System.currentTimeMillis()) {
        writableDatabase.insertWithOnConflict("workspaces", null, ContentValues().apply {
            put("workspace_id", workspace.id); put("kind", workspace.kind.name); put("name", workspace.name)
            put("organization_id", workspace.organizationId); put("created_at", nowMs)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun createTaskRun(
        workspaceId: String,
        goal: String,
        procedureId: String? = null,
        procedureRevisionId: String? = null,
        externalRef: String? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): AtlasTaskRun {
        val run = AtlasTaskRun(UUID.randomUUID().toString(), workspaceId, TaskRunStatus.PENDING, goal, procedureId, procedureRevisionId, externalRef)
        writableDatabase.insertOrThrow("task_runs", null, ContentValues().apply {
            put("task_run_id", run.id); put("workspace_id", workspaceId); put("status", run.status.name); put("goal", goal)
            put("procedure_id", procedureId); put("procedure_revision_id", procedureRevisionId); put("external_ref", externalRef)
            put("created_at", nowMs); put("updated_at", nowMs)
        })
        return run
    }

    @Synchronized
    fun appendEvent(sessionId: String, type: String, data: JSONObject = JSONObject(), atMs: Long = System.currentTimeMillis()): AtlasEvent =
        appendEventLocked(writableDatabase, sessionId, type, atMs, data.toString())

    private fun appendEventLocked(db: SQLiteDatabase, sessionId: String, type: String, atMs: Long, json: String): AtlasEvent {
        val eventId = UUID.randomUUID().toString()
        val scope = sessionScope(db, sessionId)
        val sequence = db.insertOrThrow("events", null, ContentValues().apply {
            put("event_id", eventId); put("session_id", sessionId); put("workspace_id", scope.first); put("task_run_id", scope.second)
            put("event_type", type); put("at_ms", atMs); put("data_json", json)
        })
        return AtlasEvent(sequence, eventId, sessionId, type, atMs, json, scope.first, scope.second)
    }

    @Synchronized
    fun saveObservation(observation: VisualObservation) {
        writableDatabase.transaction {
            val scope = sessionScope(this, observation.sessionId)
            insertOrThrow("observations", null, ContentValues().apply {
                put("observation_id", observation.id); put("session_id", observation.sessionId); put("media_path", observation.media.storageKey)
                put("workspace_id", scope.first); put("task_run_id", scope.second)
                put("media_id", observation.media.id); put("media_mime", observation.media.mimeType)
                put("media_width", observation.media.width); put("media_height", observation.media.height); put("media_bytes", observation.media.byteSize)
                put("media_sha256", observation.media.sha256); put("media_purpose", observation.media.purpose.name)
                put("media_raw_bytes", observation.media.rawByteSize); put("media_processing_ms", observation.media.processingMs)
                put("observed_at", observation.observedAtMs); put("available_at", observation.availableAtMs)
                put("total_ms", observation.timing.totalMs); put("capture_ms", observation.timing.captureMs); put("processing_ms", observation.timing.processingMs)
                observation.confidence?.let { put("confidence", it) }
                put("stability", observation.stability.name); put("motion_state", observation.motionState.name)
                put("fingerprint", observation.sceneFingerprint); put("summary", observation.summary); put("interpreted_at", observation.interpretedAtMs)
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

    @Synchronized
    fun updateObservationInterpretation(observationId: String, summary: String, interpretedAtMs: Long) {
        writableDatabase.update("observations", ContentValues().apply {
            put("summary", summary); put("interpreted_at", interpretedAtMs)
        }, "observation_id = ?", arrayOf(observationId))
    }

    fun loadLatestObservation(sessionId: String): VisualObservation? = readableDatabase.rawQuery(
        """SELECT observation_id,media_path,media_id,media_mime,media_width,media_height,media_bytes,media_sha256,media_purpose,
            media_raw_bytes,media_processing_ms,observed_at,available_at,total_ms,capture_ms,processing_ms,
            confidence,stability,motion_state,fingerprint,summary,interpreted_at FROM observations WHERE session_id = ? ORDER BY observed_at DESC LIMIT 1""".trimIndent(),
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
            interpretedAtMs = if (cursor.isNull(21)) null else cursor.getLong(21),
        )
    }

    fun loadHeartbeatInferenceTimes(sessionId: String, sinceMs: Long): List<Long> = readableDatabase.rawQuery(
        "SELECT at_ms,data_json FROM events WHERE session_id = ? AND event_type = 'provider.requested' AND at_ms >= ? ORDER BY at_ms DESC",
        arrayOf(sessionId, sinceMs.toString())
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                if (runCatching { JSONObject(cursor.getString(1)).optString("source") }.getOrNull() == "heartbeat") add(cursor.getLong(0))
            }
        }
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
    fun createTurn(sessionId: String, trigger: String, turnId: String = UUID.randomUUID().toString(), nowMs: Long = System.currentTimeMillis()): AgentTurn {
        val turn = AgentTurn(turnId, sessionId, TurnStatus.CREATED, trigger, nowMs, nowMs)
        writableDatabase.transaction {
            insertOrThrow("turns", null, ContentValues().apply {
                put("turn_id", turn.id); put("session_id", sessionId); put("status", turn.status.name)
                put("trigger", trigger); put("created_at", nowMs); put("updated_at", nowMs); put("step_count", 0)
            })
            appendEventLocked(this, sessionId, "turn.created", nowMs, JSONObject().put("turnId", turn.id).put("trigger", trigger).toString())
        }
        return turn
    }

    @Synchronized
    fun updateTurn(turnId: String, status: TurnStatus, stepCount: Int? = null, error: String? = null, nowMs: Long = System.currentTimeMillis()) {
        writableDatabase.transaction {
            val sessionId = checkNotNull(sessionIdForTurn(this, turnId)) { "Unknown turn $turnId" }
            update("turns", ContentValues().apply {
                put("status", status.name); put("updated_at", nowMs); if (stepCount != null) put("step_count", stepCount)
                if (error == null) putNull("error") else put("error", error.take(1_000))
            }, "turn_id = ?", arrayOf(turnId))
            appendEventLocked(this, sessionId, "turn.${status.name.lowercase()}", nowMs, JSONObject().put("turnId", turnId).apply {
                stepCount?.let { put("stepCount", it) }; error?.let { put("error", it.take(1_000)) }
            }.toString())
        }
    }

    fun loadTurn(turnId: String): AgentTurn? = readableDatabase.rawQuery(
        "SELECT turn_id,session_id,status,trigger,created_at,updated_at,step_count,error FROM turns WHERE turn_id = ?", arrayOf(turnId)
    ).use { cursor -> if (!cursor.moveToFirst()) null else cursor.toTurn() }

    fun loadActiveTurn(sessionId: String): AgentTurn? = readableDatabase.rawQuery(
        "SELECT turn_id,session_id,status,trigger,created_at,updated_at,step_count,error FROM turns WHERE session_id = ? AND status NOT IN ('COMPLETED','FAILED','CANCELLED','INTERRUPTED') ORDER BY created_at DESC LIMIT 1",
        arrayOf(sessionId),
    ).use { cursor -> if (!cursor.moveToFirst()) null else cursor.toTurn() }

    @Synchronized
    fun insertMessage(message: AtlasMessage): AtlasMessage {
        var sequence = 0L
        writableDatabase.transaction {
            sequence = insertOrThrow("messages", null, ContentValues().apply {
                put("message_id", message.id); put("session_id", message.sessionId); put("turn_id", message.turnId)
                put("role", message.role.name); put("kind", message.kind.name); put("content", message.content)
                put("tool_call_id", message.toolCallId); put("tool_calls_json", message.toolCallsJson); put("created_at", message.createdAtMs)
                put("delivery_status", message.deliveryStatus.name); put("delivered_content", message.deliveredContent)
                put("interrupted_sentence", message.interruptedSentence)
            })
            appendEventLocked(this, message.sessionId, "message.recorded", message.createdAtMs, JSONObject().apply {
                put("messageId", message.id); put("turnId", message.turnId); put("role", message.role.name.lowercase())
                put("kind", message.kind.name.lowercase()); put("content", message.content)
                message.toolCallId?.let { put("toolCallId", it) }
            }.toString())
        }
        return message.copy(sequence = sequence)
    }

    @Synchronized
    fun updateAssistantMessage(messageId: String, content: String, toolCallsJson: String? = null) {
        writableDatabase.update("messages", ContentValues().apply {
            put("content", content)
            put("tool_calls_json", toolCallsJson)
        }, "message_id = ?", arrayOf(messageId))
    }

    fun loadMessages(sessionId: String, afterSequence: Long = 0, limit: Int = 80): List<AtlasMessage> = readableDatabase.rawQuery(
        """SELECT sequence,message_id,session_id,turn_id,role,content,created_at,kind,tool_call_id,tool_calls_json,delivery_status,delivered_content,interrupted_sentence
            FROM messages WHERE session_id = ? AND sequence > ? ORDER BY sequence DESC LIMIT ?""".trimIndent(),
        arrayOf(sessionId, afterSequence.toString(), limit.toString()),
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toMessage()) }.reversed() }

    /** Oldest-first page used by checkpointing so a failed backlog can never be skipped. */
    fun loadMessagesForCompaction(sessionId: String, afterSequence: Long, limit: Int): List<AtlasMessage> = readableDatabase.rawQuery(
        """SELECT sequence,message_id,session_id,turn_id,role,content,created_at,kind,tool_call_id,tool_calls_json,delivery_status,delivered_content,interrupted_sentence
            FROM messages WHERE session_id = ? AND sequence > ? ORDER BY sequence ASC LIMIT ?""".trimIndent(),
        arrayOf(sessionId, afterSequence.toString(), limit.toString()),
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toMessage()) } }

    fun messageCountAfter(sessionId: String, afterSequence: Long): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM messages WHERE session_id = ? AND sequence > ?", arrayOf(sessionId, afterSequence.toString())
    ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }

    @Synchronized
    fun saveSpeechSegment(segment: SpeechSegment) {
        writableDatabase.transaction {
            insertOrThrow("speech_segments", null, ContentValues().apply {
                put("segment_id", segment.id); put("message_id", segment.messageId); put("session_id", segment.sessionId)
                put("turn_id", segment.turnId); put("sentence_index", segment.sentenceIndex); put("text", segment.text)
                put("status", segment.status.name); put("queued_at", segment.queuedAtMs)
                put("started_at", segment.startedAtMs); put("completed_at", segment.completedAtMs)
            })
            appendEventLocked(this, segment.sessionId, "speech.segment_queued", segment.queuedAtMs, JSONObject().apply {
                put("segmentId", segment.id); put("messageId", segment.messageId); put("turnId", segment.turnId)
                put("sentenceIndex", segment.sentenceIndex); put("text", segment.text)
            }.toString())
        }
    }

    @Synchronized
    fun updateSpeechSegment(segmentId: String, status: SpeechSegmentStatus, atMs: Long = System.currentTimeMillis(), error: String? = null) {
        writableDatabase.transaction {
            val identity = rawQuery("SELECT message_id,session_id,turn_id FROM speech_segments WHERE segment_id = ?", arrayOf(segmentId)).use { cursor ->
                if (!cursor.moveToFirst()) null else Triple(cursor.getString(0), cursor.getString(1), cursor.getString(2))
            } ?: return@transaction
            update("speech_segments", ContentValues().apply {
                put("status", status.name)
                if (status == SpeechSegmentStatus.STARTED) put("started_at", atMs)
                if (status in setOf(SpeechSegmentStatus.COMPLETED, SpeechSegmentStatus.INTERRUPTED, SpeechSegmentStatus.SKIPPED, SpeechSegmentStatus.FAILED)) put("completed_at", atMs)
            }, "segment_id = ?", arrayOf(segmentId))
            appendEventLocked(this, identity.second, "speech.segment_${status.name.lowercase()}", atMs, JSONObject().apply {
                put("segmentId", segmentId); put("messageId", identity.first); put("turnId", identity.third); error?.let { put("error", it.take(500)) }
            }.toString())
            refreshMessageDeliveryLocked(this, identity.first)
        }
    }

    @Synchronized
    fun refreshMessageDelivery(messageId: String, fallbackStatus: DeliveryStatus? = null) {
        writableDatabase.transaction { refreshMessageDeliveryLocked(this, messageId, fallbackStatus) }
    }

    private fun refreshMessageDeliveryLocked(db: SQLiteDatabase, messageId: String, fallbackStatus: DeliveryStatus? = null) {
        val completed = mutableListOf<String>()
        var interrupted: String? = null
        var skipped = false
        var failed = false
        var pending = false
        db.rawQuery("SELECT text,status FROM speech_segments WHERE message_id = ? ORDER BY sentence_index", arrayOf(messageId)).use { cursor ->
            while (cursor.moveToNext()) when (SpeechSegmentStatus.valueOf(cursor.getString(1))) {
                SpeechSegmentStatus.COMPLETED -> completed += cursor.getString(0)
                SpeechSegmentStatus.INTERRUPTED -> if (interrupted == null) interrupted = cursor.getString(0)
                SpeechSegmentStatus.SKIPPED -> skipped = true
                SpeechSegmentStatus.FAILED -> failed = true
                SpeechSegmentStatus.QUEUED, SpeechSegmentStatus.STARTED -> pending = true
            }
        }
        val status = when {
            interrupted != null || skipped -> DeliveryStatus.INTERRUPTED
            pending -> DeliveryStatus.PENDING
            failed -> DeliveryStatus.FAILED
            completed.isNotEmpty() -> DeliveryStatus.DELIVERED
            else -> fallbackStatus ?: return
        }
        db.update("messages", ContentValues().apply {
            put("delivery_status", status.name); put("delivered_content", completed.joinToString(" ").ifBlank { null })
            put("interrupted_sentence", interrupted)
        }, "message_id = ?", arrayOf(messageId))
    }

    @Synchronized
    fun insertToolCall(call: AtlasToolCall) {
        writableDatabase.transaction {
            insertOrThrow("tool_calls", null, ContentValues().apply {
                put("tool_call_id", call.id); put("session_id", call.sessionId); put("turn_id", call.turnId); put("name", call.name)
                put("arguments_json", call.argumentsJson); put("status", call.status.name); put("risk", call.risk.name)
                put("requires_confirmation", if (call.requiresConfirmation) 1 else 0); put("idempotency_key", call.idempotencyKey)
                put("reason", call.reason); put("created_at", call.createdAtMs); put("updated_at", call.updatedAtMs)
            })
            appendEventLocked(this, call.sessionId, "tool.proposed", call.createdAtMs, JSONObject().apply {
                put("turnId", call.turnId); put("toolCallId", call.id); put("tool", call.name); put("arguments", JSONObject(call.argumentsJson))
                put("risk", call.risk.name.lowercase()); put("requiresConfirmation", call.requiresConfirmation)
            }.toString())
        }
    }

    @Synchronized
    fun updateToolCall(callId: String, status: ToolCallStatus, resultJson: String? = null, error: String? = null, nowMs: Long = System.currentTimeMillis()) {
        writableDatabase.transaction {
            val call = loadToolCallLocked(this, callId) ?: error("Unknown tool call $callId")
            update("tool_calls", ContentValues().apply {
                put("status", status.name); put("updated_at", nowMs)
                if (resultJson != null) put("result_json", resultJson); if (error != null) put("error", error.take(1_000))
            }, "tool_call_id = ?", arrayOf(callId))
            appendEventLocked(this, call.sessionId, "tool.${status.name.lowercase()}", nowMs, JSONObject().apply {
                put("turnId", call.turnId); put("toolCallId", call.id); put("tool", call.name)
                resultJson?.let { put("result", runCatching { JSONObject(it) }.getOrElse { it }) }; error?.let { put("error", it.take(1_000)) }
            }.toString())
        }
    }

    fun loadToolCall(callId: String): AtlasToolCall? = loadToolCallLocked(readableDatabase, callId)

    fun loadPendingToolCalls(sessionId: String): List<AtlasToolCall> = readableDatabase.rawQuery(
        """SELECT tool_call_id,session_id,turn_id,name,arguments_json,status,risk,requires_confirmation,idempotency_key,reason,result_json,error,created_at,updated_at
            FROM tool_calls WHERE session_id = ? AND status IN ('PROPOSED','WAITING_FOR_CONFIRMATION','APPROVED','RUNNING','UNKNOWN') ORDER BY created_at""".trimIndent(), arrayOf(sessionId)
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toToolCall()) } }

    fun loadTurnToolCalls(turnId: String): List<AtlasToolCall> = readableDatabase.rawQuery(
        """SELECT tool_call_id,session_id,turn_id,name,arguments_json,status,risk,requires_confirmation,idempotency_key,reason,result_json,error,created_at,updated_at
            FROM tool_calls WHERE turn_id = ? ORDER BY created_at""".trimIndent(), arrayOf(turnId)
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toToolCall()) } }

    @Synchronized
    fun markRunningToolsUnknown(turnId: String, reason: String, nowMs: Long = System.currentTimeMillis()) {
        writableDatabase.transaction {
            val sessionId = sessionIdForTurn(this, turnId) ?: return@transaction
            val changed = update("tool_calls", ContentValues().apply {
                put("status", ToolCallStatus.UNKNOWN.name); put("updated_at", nowMs); put("error", reason.take(1_000))
            }, "turn_id = ? AND status = 'RUNNING'", arrayOf(turnId))
            if (changed > 0) appendEventLocked(this, sessionId, "tool.unknown", nowMs, JSONObject().put("turnId", turnId).put("reason", reason).toString())
        }
    }

    @Synchronized
    fun cancelOpenSessionWork(sessionId: String, reason: String, nowMs: Long = System.currentTimeMillis()) {
        writableDatabase.transaction {
            update("turns", ContentValues().apply { put("status", TurnStatus.CANCELLED.name); put("updated_at", nowMs); put("error", reason.take(1_000)) },
                "session_id = ? AND status NOT IN ('COMPLETED','FAILED','CANCELLED','INTERRUPTED')", arrayOf(sessionId))
            update("tool_calls", ContentValues().apply { put("status", ToolCallStatus.REJECTED.name); put("updated_at", nowMs); put("error", reason.take(1_000)) },
                "session_id = ? AND status IN ('PROPOSED','WAITING_FOR_CONFIRMATION','APPROVED')", arrayOf(sessionId))
            appendEventLocked(this, sessionId, "runtime.open_work_cancelled", nowMs, JSONObject().put("reason", reason).toString())
        }
    }

    @Synchronized
    fun saveMemory(item: MemoryItem) {
        writableDatabase.transaction {
            val sessionScope = sessionScope(this, item.sessionId)
            check(item.workspaceId == sessionScope.first) { "Memory workspace must match its session workspace" }
            insertWithOnConflict("memory_items", null, ContentValues().apply {
                put("memory_id", item.id); put("session_id", item.sessionId); put("workspace_id", item.workspaceId)
                put("scope", item.scope.name); put("scope_id", item.scopeId); put("kind", item.kind.name); put("content", item.content)
                put("status", item.status.name); put("confidence", item.confidence); put("source_turn_id", item.sourceTurnId)
                put("evidence_observation_id", item.evidenceObservationId)
                put("created_at", item.createdAtMs); put("updated_at", item.updatedAtMs); put("expires_at", item.expiresAtMs)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            appendEventLocked(this, item.sessionId, "memory.${item.status.name.lowercase()}", item.updatedAtMs, JSONObject().apply {
                put("memoryId", item.id); put("kind", item.kind.name.lowercase()); put("content", item.content)
                put("scope", item.scope.name.lowercase()); put("scopeId", item.scopeId)
                put("confidence", item.confidence); item.sourceTurnId?.let { put("sourceTurnId", it) }
                item.evidenceObservationId?.let { put("evidenceObservationId", it) }
            }.toString())
        }
    }

    @Synchronized
    fun forgetMemory(memoryId: String, nowMs: Long = System.currentTimeMillis(), actorSessionId: String? = null) {
        writableDatabase.transaction {
            val sessionId = rawQuery("SELECT session_id FROM memory_items WHERE memory_id = ?", arrayOf(memoryId)).use { cursor ->
                if (!cursor.moveToFirst()) null else cursor.getString(0)
            } ?: return@transaction
            update("memory_items", ContentValues().apply { put("status", MemoryStatus.FORGOTTEN.name); put("updated_at", nowMs) }, "memory_id = ?", arrayOf(memoryId))
            appendEventLocked(this, sessionId, "memory.forgotten", nowMs, JSONObject().put("memoryId", memoryId).toString())
            if (actorSessionId != null && actorSessionId != sessionId) {
                appendEventLocked(this, actorSessionId, "memory.forgotten", nowMs, JSONObject().put("memoryId", memoryId).put("originSessionId", sessionId).toString())
            }
        }
    }

    fun memoryIsAccessible(memoryId: String, sessionId: String): Boolean = readableDatabase.rawQuery(
        """SELECT 1 FROM memory_items m JOIN sessions s ON s.session_id = ?
            WHERE m.memory_id = ? AND m.workspace_id = s.workspace_id AND
            ((m.scope = 'SESSION' AND m.scope_id = s.session_id) OR
             (m.scope = 'TASK' AND s.task_run_id IS NOT NULL AND m.scope_id = s.task_run_id) OR
             (m.scope = 'PRINCIPAL' AND s.actor_id IS NOT NULL AND m.scope_id = s.actor_id) OR
             (m.scope = 'WORKSPACE' AND m.scope_id = s.workspace_id) OR
             (m.scope = 'ENVIRONMENT' AND m.scope_id IN (s.station_id,s.site_id))) LIMIT 1""".trimIndent(),
        arrayOf(sessionId, memoryId),
    ).use { it.moveToFirst() }

    fun accessibleMemoryKind(memoryId: String, sessionId: String): MemoryKind? = readableDatabase.rawQuery(
        """SELECT m.kind FROM memory_items m JOIN sessions s ON s.session_id = ?
            WHERE m.memory_id = ? AND m.workspace_id = s.workspace_id AND
            ((m.scope = 'SESSION' AND m.scope_id = s.session_id) OR
             (m.scope = 'TASK' AND s.task_run_id IS NOT NULL AND m.scope_id = s.task_run_id) OR
             (m.scope = 'PRINCIPAL' AND s.actor_id IS NOT NULL AND m.scope_id = s.actor_id) OR
             (m.scope = 'WORKSPACE' AND m.scope_id = s.workspace_id) OR
             (m.scope = 'ENVIRONMENT' AND m.scope_id IN (s.station_id,s.site_id))) LIMIT 1""".trimIndent(),
        arrayOf(sessionId, memoryId),
    ).use { cursor -> if (cursor.moveToFirst()) MemoryKind.valueOf(cursor.getString(0)) else null }

    fun observationBelongsToSession(observationId: String, sessionId: String): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM observations WHERE observation_id = ? AND session_id = ? LIMIT 1",
        arrayOf(observationId, sessionId),
    ).use { it.moveToFirst() }

    fun loadActiveMemories(sessionId: String, nowMs: Long = System.currentTimeMillis()): List<MemoryItem> = readableDatabase.rawQuery(
        """SELECT m.memory_id,m.session_id,m.kind,m.content,m.status,m.confidence,m.source_turn_id,m.evidence_observation_id,
            m.created_at,m.updated_at,m.expires_at,m.workspace_id,m.scope,m.scope_id
            FROM memory_items m JOIN sessions s ON s.session_id = ?
            WHERE m.workspace_id = s.workspace_id AND m.status = 'ACTIVE' AND (m.expires_at IS NULL OR m.expires_at > ?) AND
            ((m.scope = 'SESSION' AND m.scope_id = s.session_id) OR
             (m.scope = 'TASK' AND s.task_run_id IS NOT NULL AND m.scope_id = s.task_run_id) OR
             (m.scope = 'PRINCIPAL' AND s.actor_id IS NOT NULL AND m.scope_id = s.actor_id) OR
             (m.scope = 'WORKSPACE' AND m.scope_id = s.workspace_id) OR
             (m.scope = 'ENVIRONMENT' AND m.scope_id IN (s.station_id,s.site_id)))
            ORDER BY m.kind,m.updated_at DESC""".trimIndent(),
        arrayOf(sessionId, nowMs.toString()),
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toMemory()) } }

    @Synchronized
    fun saveSummary(summary: SessionSummary) {
        writableDatabase.transaction {
            insertWithOnConflict("session_summaries", null, ContentValues().apply {
                put("session_id", summary.sessionId); put("summary", summary.summary)
                put("through_message_sequence", summary.throughMessageSequence); put("updated_at", summary.updatedAtMs)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            appendEventLocked(this, summary.sessionId, "memory.summary_checkpointed", summary.updatedAtMs, JSONObject().apply {
                put("throughMessageSequence", summary.throughMessageSequence); put("summary", summary.summary)
            }.toString())
        }
    }

    fun loadSummary(sessionId: String): SessionSummary? = readableDatabase.rawQuery(
        "SELECT session_id,summary,through_message_sequence,updated_at FROM session_summaries WHERE session_id = ?", arrayOf(sessionId)
    ).use { cursor -> if (!cursor.moveToFirst()) null else SessionSummary(cursor.getString(0), cursor.getString(1), cursor.getLong(2), cursor.getLong(3)) }

    /** Never retries an ambiguous external operation after process death. */
    @Synchronized
    fun recoverInterruptedRuntime(sessionId: String, nowMs: Long = System.currentTimeMillis()) {
        writableDatabase.transaction {
            val turnIds = mutableListOf<String>()
            rawQuery("SELECT turn_id FROM turns WHERE session_id = ? AND status IN ('CREATED','ASSEMBLING_CONTEXT','WAITING_FOR_MODEL','EXECUTING_TOOL')", arrayOf(sessionId)).use { cursor ->
                while (cursor.moveToNext()) turnIds += cursor.getString(0)
            }
            turnIds.forEach { turnId ->
                update("turns", ContentValues().apply { put("status", TurnStatus.INTERRUPTED.name); put("updated_at", nowMs); put("error", "Interrupted by process restart; no external operation was retried") }, "turn_id = ?", arrayOf(turnId))
                appendEventLocked(this, sessionId, "turn.interrupted", nowMs, JSONObject().put("turnId", turnId).put("reason", "process_restart").toString())
            }
            val unknown = update("tool_calls", ContentValues().apply { put("status", ToolCallStatus.UNKNOWN.name); put("updated_at", nowMs); put("error", "Outcome unknown after process restart") },
                "session_id = ? AND status = 'RUNNING'", arrayOf(sessionId))
            if (unknown > 0) appendEventLocked(this, sessionId, "tool.unknown", nowMs, JSONObject().put("reason", "process_restart").put("count", unknown).toString())
            val cancelled = update("tool_calls", ContentValues().apply { put("status", ToolCallStatus.REJECTED.name); put("updated_at", nowMs); put("error", "Cancelled before execution by process restart") },
                "session_id = ? AND status IN ('PROPOSED','APPROVED')", arrayOf(sessionId))
            if (cancelled > 0) appendEventLocked(this, sessionId, "tool.pre_execution_cancelled", nowMs, JSONObject().put("reason", "process_restart").put("count", cancelled).toString())
            val interruptedSpeech = mutableSetOf<String>()
            rawQuery("SELECT DISTINCT message_id FROM speech_segments WHERE session_id = ? AND status IN ('QUEUED','STARTED')", arrayOf(sessionId)).use { cursor ->
                while (cursor.moveToNext()) interruptedSpeech += cursor.getString(0)
            }
            update("speech_segments", ContentValues().apply { put("status", SpeechSegmentStatus.SKIPPED.name); put("completed_at", nowMs) },
                "session_id = ? AND status = 'QUEUED'", arrayOf(sessionId))
            update("speech_segments", ContentValues().apply { put("status", SpeechSegmentStatus.INTERRUPTED.name); put("completed_at", nowMs) },
                "session_id = ? AND status = 'STARTED'", arrayOf(sessionId))
            interruptedSpeech.forEach { messageId -> refreshMessageDeliveryLocked(this, messageId, DeliveryStatus.INTERRUPTED) }
            if (interruptedSpeech.isNotEmpty()) appendEventLocked(this, sessionId, "speech.recovered_interrupted", nowMs, JSONObject().put("messageCount", interruptedSpeech.size).toString())
        }
    }

    private fun sessionIdForTurn(db: SQLiteDatabase, turnId: String): String? = db.rawQuery(
        "SELECT session_id FROM turns WHERE turn_id = ?", arrayOf(turnId)
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun addColumnIfMissing(db: SQLiteDatabase, table: String, column: String, definition: String) {
        val exists = db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            var found = false
            while (cursor.moveToNext()) if (cursor.getString(nameIndex) == column) { found = true; break }
            found
        }
        if (!exists) db.execSQL("ALTER TABLE $table ADD COLUMN $column $definition")
    }

    private fun sessionScope(db: SQLiteDatabase, sessionId: String): Pair<String, String?> = db.rawQuery(
        "SELECT workspace_id,task_run_id FROM sessions WHERE session_id = ?", arrayOf(sessionId)
    ).use { cursor ->
        check(cursor.moveToFirst()) { "Unknown session $sessionId" }
        cursor.getString(0) to cursor.nullableString(1)
    }

    private fun loadToolCallLocked(db: SQLiteDatabase, callId: String): AtlasToolCall? = db.rawQuery(
        """SELECT tool_call_id,session_id,turn_id,name,arguments_json,status,risk,requires_confirmation,idempotency_key,reason,result_json,error,created_at,updated_at
            FROM tool_calls WHERE tool_call_id = ?""".trimIndent(), arrayOf(callId)
    ).use { cursor -> if (!cursor.moveToFirst()) null else cursor.toToolCall() }
}

private fun android.database.Cursor.toTurn() = AgentTurn(
    getString(0), getString(1), TurnStatus.valueOf(getString(2)), getString(3), getLong(4), getLong(5), getInt(6), if (isNull(7)) null else getString(7),
)

private fun android.database.Cursor.toMessage() = AtlasMessage(
    getLong(0), getString(1), getString(2), getString(3), MessageRole.valueOf(getString(4)), getString(5), getLong(6),
    MessageKind.valueOf(getString(7)), if (isNull(8)) null else getString(8), if (isNull(9)) null else getString(9),
    DeliveryStatus.valueOf(getString(10)), if (isNull(11)) null else getString(11), if (isNull(12)) null else getString(12),
)

private fun android.database.Cursor.toToolCall() = AtlasToolCall(
    id = getString(0), sessionId = getString(1), turnId = getString(2), name = getString(3), argumentsJson = getString(4),
    status = ToolCallStatus.valueOf(getString(5)), risk = ToolRisk.valueOf(getString(6)), requiresConfirmation = getInt(7) != 0,
    idempotencyKey = getString(8), reason = if (isNull(9)) null else getString(9), resultJson = if (isNull(10)) null else getString(10),
    error = if (isNull(11)) null else getString(11), createdAtMs = getLong(12), updatedAtMs = getLong(13),
)

private fun android.database.Cursor.toMemory() = MemoryItem(
    getString(0), getString(1), MemoryKind.valueOf(getString(2)), getString(3), MemoryStatus.valueOf(getString(4)), getDouble(5),
    if (isNull(6)) null else getString(6), if (isNull(7)) null else getString(7), getLong(8), getLong(9), if (isNull(10)) null else getLong(10),
    getString(11), MemoryScope.valueOf(getString(12)), getString(13),
)

private fun android.database.Cursor.toClarification() = PendingClarification(
    id = getString(0), sessionId = getString(1), sourceTurnId = getString(2), question = getString(3), reason = getString(4),
    ambiguity = ClarificationAmbiguity.valueOf(getString(5)), options = jsonStringList(getString(6)), blocking = getInt(7) != 0,
    status = ClarificationStatus.valueOf(getString(8)), contextObservationIds = jsonStringList(getString(9)),
    freshnessRequirement = nullableString(10), createdAtMs = getLong(11), updatedAtMs = getLong(12),
    expiresAtMs = if (isNull(13)) null else getLong(13), deferredCount = getInt(14), normalizedAnswer = nullableString(15),
    resolvedByTurnId = nullableString(16),
)

private fun jsonStringList(raw: String): List<String> = runCatching {
    val array = JSONArray(raw)
    buildList { for (index in 0 until array.length()) add(array.getString(index)) }
}.getOrDefault(emptyList())

private fun android.database.Cursor.nullableString(index: Int): String? = if (isNull(index)) null else getString(index)

private fun SessionPermissions.toJson() = JSONObject().apply {
    put("observe", observe); put("captureImage", captureImage.name); put("microphone", microphone.name)
    put("speakResponses", speakResponses); put("proactiveSpeech", proactiveSpeech)
    put("externalActionsRequireConfirmation", externalActionsRequireConfirmation)
}

private fun permissionsFromJson(raw: String?) = runCatching {
    val json = JSONObject(raw ?: "{}")
    SessionPermissions(
        observe = json.optBoolean("observe", true),
        captureImage = PermissionPolicy.valueOf(json.optString("captureImage", PermissionPolicy.ACTIVE_SESSION.name)),
        microphone = PermissionPolicy.valueOf(json.optString("microphone", PermissionPolicy.USER_REQUEST.name)),
        speakResponses = json.optBoolean("speakResponses", true), proactiveSpeech = json.optBoolean("proactiveSpeech", false),
        externalActionsRequireConfirmation = json.optBoolean("externalActionsRequireConfirmation", true),
    )
}.getOrElse { SessionPermissions() }

private inline fun <T> SQLiteDatabase.transaction(block: SQLiteDatabase.() -> T): T {
    beginTransaction()
    return try { val result = block(); setTransactionSuccessful(); result } finally { endTransaction() }
}
