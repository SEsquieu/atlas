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
import com.grinningfrog.atlas.workspace.ComposableWorkspace
import com.grinningfrog.atlas.workspace.WorkspaceDefinitionValidator
import com.grinningfrog.atlas.workspace.WorkspaceRecord
import com.grinningfrog.atlas.workspace.WorkspaceRevision
import com.grinningfrog.atlas.workspace.WorkspaceStatus
import org.json.JSONObject
import org.json.JSONArray
import java.security.MessageDigest
import java.util.UUID

data class ChatGptUsageSummary(
    val requests: Int = 0,
    val interactiveRequests: Int = 0,
    val backgroundRequests: Int = 0,
    val visionRequests: Int = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val totalTokens: Long = 0,
    val failedAttempts: Int = 0,
    val usageLimitFailures: Int = 0,
    val lastFailureCode: String? = null,
    val hostedSearchUses: Int = 0,
    val lastHostedSearchAction: String? = null,
    val lastHostedSearchStatus: String? = null,
)

class AtlasDatabase(context: Context) : SQLiteOpenHelper(context, "atlas.db", null, 11) {
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
                provider_context_json TEXT,
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

    private fun createIntentRuntimeTables(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS intents (
            intent_id TEXT PRIMARY KEY, type TEXT NOT NULL, subject TEXT NOT NULL, description TEXT,
            origin TEXT NOT NULL, created_at INTEGER NOT NULL, last_updated_at INTEGER NOT NULL,
            last_evaluated_at INTEGER, last_worked_at INTEGER, state TEXT NOT NULL,
            importance REAL NOT NULL, user_relevance REAL NOT NULL, confidence REAL NOT NULL,
            environmental_affinity_json TEXT, required_capabilities_json TEXT NOT NULL DEFAULT '[]',
            required_authorities_json TEXT NOT NULL DEFAULT '[]', estimated_cost REAL, estimated_risk REAL,
            attempt_count INTEGER NOT NULL DEFAULT 0, identical_failure_count INTEGER NOT NULL DEFAULT 0,
            successful_step_count INTEGER NOT NULL DEFAULT 0, information_gain REAL, progress_rate REAL,
            blocked_reason TEXT, next_action TEXT, parent_intent_id TEXT, supersedes_intent_id TEXT,
            cooldown_until INTEGER, metadata_json TEXT NOT NULL DEFAULT '{}'
        )""".trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS intents_state_pressure_inputs ON intents(state, importance DESC, user_relevance DESC, last_updated_at DESC)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS intent_transitions (
            sequence INTEGER PRIMARY KEY AUTOINCREMENT, intent_id TEXT NOT NULL, from_state TEXT NOT NULL,
            to_state TEXT NOT NULL, reason TEXT, at_ms INTEGER NOT NULL,
            FOREIGN KEY(intent_id) REFERENCES intents(intent_id)
        )""".trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS intent_transitions_intent_time ON intent_transitions(intent_id, at_ms DESC)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS idle_evaluations (
            evaluation_id TEXT PRIMARY KEY, at_ms INTEGER NOT NULL, autonomy_mode TEXT NOT NULL,
            environment_json TEXT NOT NULL, candidate_count INTEGER NOT NULL, eligible_count INTEGER NOT NULL,
            ranked_json TEXT NOT NULL, decision TEXT NOT NULL, selected_intent_id TEXT, reason TEXT NOT NULL,
            next_evaluation_at INTEGER, budget_usage_json TEXT NOT NULL
        )""".trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idle_evaluations_time ON idle_evaluations(at_ms DESC)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS idle_work_attempts (
            attempt_id TEXT PRIMARY KEY, intent_id TEXT NOT NULL, started_at INTEGER NOT NULL,
            completed_at INTEGER NOT NULL, autonomy_mode TEXT NOT NULL, inference_location TEXT NOT NULL,
            contract_json TEXT NOT NULL, result_json TEXT NOT NULL,
            FOREIGN KEY(intent_id) REFERENCES intents(intent_id)
        )""".trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idle_work_attempts_intent_time ON idle_work_attempts(intent_id, started_at DESC)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS intent_quarantine (
            sequence INTEGER PRIMARY KEY AUTOINCREMENT, intent_id TEXT, raw_json TEXT NOT NULL,
            error TEXT NOT NULL, quarantined_at INTEGER NOT NULL
        )""".trimIndent())
        db.execSQL("""CREATE TABLE IF NOT EXISTS idle_runtime_state (
            state_key TEXT PRIMARY KEY, value_json TEXT NOT NULL, updated_at INTEGER NOT NULL
        )""".trimIndent())
    }

    private fun createComposableWorkspaceTables(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS composable_workspaces (
            composable_workspace_id TEXT PRIMARY KEY, realm_id TEXT NOT NULL, name TEXT NOT NULL,
            description TEXT NOT NULL DEFAULT '', status TEXT NOT NULL, live_revision_id TEXT NOT NULL,
            created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
            FOREIGN KEY(realm_id) REFERENCES workspaces(workspace_id)
        )""".trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS composable_workspaces_realm_status ON composable_workspaces(realm_id,status,updated_at DESC)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS workspace_revisions (
            revision_id TEXT PRIMARY KEY, composable_workspace_id TEXT NOT NULL, parent_revision_id TEXT,
            definition_json TEXT NOT NULL, content_hash TEXT NOT NULL, author_type TEXT NOT NULL,
            source_session_id TEXT, source_turn_id TEXT, purpose TEXT NOT NULL, created_at INTEGER NOT NULL,
            FOREIGN KEY(composable_workspace_id) REFERENCES composable_workspaces(composable_workspace_id) ON DELETE CASCADE
        )""".trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS workspace_revisions_workspace_time ON workspace_revisions(composable_workspace_id,created_at DESC)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS workspace_records (
            record_id TEXT PRIMARY KEY, composable_workspace_id TEXT NOT NULL, collection_name TEXT NOT NULL,
            data_json TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
            FOREIGN KEY(composable_workspace_id) REFERENCES composable_workspaces(composable_workspace_id) ON DELETE CASCADE
        )""".trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS workspace_records_collection ON workspace_records(composable_workspace_id,collection_name,updated_at DESC)")
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
                active_composable_workspace_id TEXT,
                workspace_navigation_sequence INTEGER NOT NULL DEFAULT 0,
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
        createIntentRuntimeTables(db)
        createComposableWorkspaceTables(db)
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
        if (oldVersion < 9) createIntentRuntimeTables(db)
        if (oldVersion < 10) addColumnIfMissing(db, "messages", "provider_context_json", "TEXT")
        if (oldVersion < 11) {
            createComposableWorkspaceTables(db)
            addColumnIfMissing(db, "sessions", "active_composable_workspace_id", "TEXT")
            addColumnIfMissing(db, "sessions", "workspace_navigation_sequence", "INTEGER NOT NULL DEFAULT 0")
        }
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
                put("active_composable_workspace_id", session.activeComposableWorkspaceId)
                put("workspace_navigation_sequence", session.workspaceNavigationSequence)
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
            workspace_id,actor_id,site_id,station_id,task_run_id,policy_id,policy_revision,
            active_composable_workspace_id,workspace_navigation_sequence
            FROM sessions ORDER BY updated_at DESC LIMIT 1""".trimIndent(), null
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else AtlasSession(
            id = cursor.getString(0), name = cursor.getString(1), goal = cursor.getString(2),
            status = SessionStatus.valueOf(cursor.getString(3)), createdAtMs = cursor.getLong(4), updatedAtMs = cursor.getLong(5),
            contextMode = ContextMode.valueOf(cursor.getString(6)), permissions = permissionsFromJson(cursor.getString(7)),
            workspaceId = cursor.getString(8), actorId = cursor.nullableString(9), siteId = cursor.nullableString(10),
            stationId = cursor.nullableString(11), taskRunId = cursor.nullableString(12), policyId = cursor.nullableString(13),
            policyRevision = if (cursor.isNull(14)) null else cursor.getInt(14),
            activeComposableWorkspaceId = cursor.nullableString(15), workspaceNavigationSequence = cursor.getLong(16),
        )
    }

    fun loadSession(sessionId: String): AtlasSession? = readableDatabase.rawQuery(
        """SELECT session_id,name,goal,status,created_at,updated_at,context_mode,permissions_json,
            workspace_id,actor_id,site_id,station_id,task_run_id,policy_id,policy_revision,
            active_composable_workspace_id,workspace_navigation_sequence
            FROM sessions WHERE session_id = ?""".trimIndent(), arrayOf(sessionId)
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else AtlasSession(
            id = cursor.getString(0), name = cursor.getString(1), goal = cursor.getString(2),
            status = SessionStatus.valueOf(cursor.getString(3)), createdAtMs = cursor.getLong(4), updatedAtMs = cursor.getLong(5),
            contextMode = ContextMode.valueOf(cursor.getString(6)), permissions = permissionsFromJson(cursor.getString(7)),
            workspaceId = cursor.getString(8), actorId = cursor.nullableString(9), siteId = cursor.nullableString(10),
            stationId = cursor.nullableString(11), taskRunId = cursor.nullableString(12), policyId = cursor.nullableString(13),
            policyRevision = if (cursor.isNull(14)) null else cursor.getInt(14), activeComposableWorkspaceId = cursor.nullableString(15),
            workspaceNavigationSequence = cursor.getLong(16),
        )
    }

    @Synchronized
    fun activateComposableWorkspace(sessionId: String, workspaceId: String?, nowMs: Long = System.currentTimeMillis()): Long = writableDatabase.transaction {
        if (workspaceId != null) {
            check(rawQuery("SELECT 1 FROM composable_workspaces c JOIN sessions s ON s.session_id = ? WHERE c.composable_workspace_id = ? AND c.realm_id = s.workspace_id AND c.status = 'ACTIVE'", arrayOf(sessionId, workspaceId)).use { it.moveToFirst() }) {
                "Workspace is unavailable to this session"
            }
        }
        val prior = rawQuery("SELECT active_composable_workspace_id,workspace_navigation_sequence FROM sessions WHERE session_id = ?", arrayOf(sessionId)).use { cursor ->
            check(cursor.moveToFirst()) { "Unknown session $sessionId" }
            cursor.nullableString(0) to cursor.getLong(1)
        }
        val sequence = prior.second + 1
        update("sessions", ContentValues().apply {
            put("active_composable_workspace_id", workspaceId); put("workspace_navigation_sequence", sequence); put("updated_at", nowMs)
        }, "session_id = ?", arrayOf(sessionId))
        appendEventLocked(this, sessionId, "workspace.activated", nowMs, JSONObject().apply {
            put("priorWorkspaceId", prior.first); put("workspaceId", workspaceId); put("navigationSequence", sequence)
        }.toString())
        sequence
    }

    @Synchronized
    fun createComposableWorkspace(
        name: String,
        description: String,
        definitionJson: String = WorkspaceDefinitionValidator.starter(name, description),
        realmId: String = DEFAULT_PERSONAL_WORKSPACE_ID,
        sourceSessionId: String? = null,
        sourceTurnId: String? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): ComposableWorkspace {
        val validation = WorkspaceDefinitionValidator.validate(definitionJson)
        require(validation.valid) { validation.errors.joinToString("; ") }
        val workspaceId = UUID.randomUUID().toString()
        val revisionId = UUID.randomUUID().toString()
        val workspace = ComposableWorkspace(workspaceId, realmId, name.trim().take(120), description.trim().take(1_000), WorkspaceStatus.ACTIVE, revisionId, nowMs, nowMs)
        writableDatabase.transaction {
            insertOrThrow("composable_workspaces", null, ContentValues().apply {
                put("composable_workspace_id", workspaceId); put("realm_id", realmId); put("name", workspace.name)
                put("description", workspace.description); put("status", workspace.status.name); put("live_revision_id", revisionId)
                put("created_at", nowMs); put("updated_at", nowMs)
            })
            insertOrThrow("workspace_revisions", null, revisionValues(revisionId, workspaceId, null, definitionJson, "USER", sourceSessionId, sourceTurnId, "Workspace created", nowMs))
        }
        return workspace
    }

    fun listComposableWorkspaces(includeArchived: Boolean = false): List<ComposableWorkspace> = readableDatabase.rawQuery(
        "SELECT composable_workspace_id,realm_id,name,description,status,live_revision_id,created_at,updated_at FROM composable_workspaces ${if (includeArchived) "" else "WHERE status = 'ACTIVE'"} ORDER BY updated_at DESC", null
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toComposableWorkspace()) } }

    fun loadComposableWorkspace(id: String): ComposableWorkspace? = readableDatabase.rawQuery(
        "SELECT composable_workspace_id,realm_id,name,description,status,live_revision_id,created_at,updated_at FROM composable_workspaces WHERE composable_workspace_id = ?", arrayOf(id)
    ).use { if (it.moveToFirst()) it.toComposableWorkspace() else null }

    fun loadWorkspaceRevision(revisionId: String): WorkspaceRevision? = readableDatabase.rawQuery(
        "SELECT revision_id,composable_workspace_id,parent_revision_id,definition_json,content_hash,author_type,source_session_id,source_turn_id,purpose,created_at FROM workspace_revisions WHERE revision_id = ?", arrayOf(revisionId)
    ).use { if (it.moveToFirst()) it.toWorkspaceRevision() else null }

    fun loadLiveWorkspaceRevision(workspaceId: String): WorkspaceRevision? = readableDatabase.rawQuery(
        "SELECT r.revision_id,r.composable_workspace_id,r.parent_revision_id,r.definition_json,r.content_hash,r.author_type,r.source_session_id,r.source_turn_id,r.purpose,r.created_at FROM workspace_revisions r JOIN composable_workspaces w ON w.live_revision_id = r.revision_id WHERE w.composable_workspace_id = ?", arrayOf(workspaceId)
    ).use { if (it.moveToFirst()) it.toWorkspaceRevision() else null }

    @Synchronized
    fun replaceWorkspaceDefinition(workspaceId: String, baseRevisionId: String, definitionJson: String, authorType: String, sourceSessionId: String?, sourceTurnId: String?, purpose: String, nowMs: Long = System.currentTimeMillis()): WorkspaceRevision {
        val validation = WorkspaceDefinitionValidator.validate(definitionJson)
        require(validation.valid) { validation.errors.joinToString("; ") }
        val revisionId = UUID.randomUUID().toString()
        val revision = WorkspaceRevision(revisionId, workspaceId, baseRevisionId, definitionJson, sha256(definitionJson), authorType, sourceSessionId, sourceTurnId, purpose.take(500), nowMs)
        writableDatabase.transaction {
            val changed = update("composable_workspaces", ContentValues().apply { put("live_revision_id", revisionId); put("updated_at", nowMs) },
                "composable_workspace_id = ? AND live_revision_id = ? AND status = 'ACTIVE'", arrayOf(workspaceId, baseRevisionId))
            check(changed == 1) { "Workspace revision changed; inspect it again before applying this update" }
            insertOrThrow("workspace_revisions", null, revisionValues(revisionId, workspaceId, baseRevisionId, definitionJson, authorType, sourceSessionId, sourceTurnId, purpose, nowMs))
        }
        return revision
    }

    @Synchronized
    fun setComposableWorkspaceArchived(workspaceId: String, archived: Boolean, nowMs: Long = System.currentTimeMillis()) {
        check(writableDatabase.update("composable_workspaces", ContentValues().apply {
            put("status", if (archived) WorkspaceStatus.ARCHIVED.name else WorkspaceStatus.ACTIVE.name); put("updated_at", nowMs)
        }, "composable_workspace_id = ?", arrayOf(workspaceId)) == 1) { "Unknown workspace" }
        if (archived) writableDatabase.execSQL("UPDATE sessions SET active_composable_workspace_id = NULL, workspace_navigation_sequence = workspace_navigation_sequence + 1 WHERE active_composable_workspace_id = ?", arrayOf(workspaceId))
    }

    @Synchronized
    fun addWorkspaceRecord(workspaceId: String, collection: String, dataJson: String, nowMs: Long = System.currentTimeMillis()): WorkspaceRecord {
        require(collection.matches(Regex("[A-Za-z][A-Za-z0-9_-]{0,63}"))) { "Invalid collection name" }
        require(dataJson.toByteArray().size <= 32 * 1024) { "Record exceeds 32 KiB" }
        JSONObject(dataJson)
        check(loadComposableWorkspace(workspaceId)?.status == WorkspaceStatus.ACTIVE) { "Workspace is not active" }
        val record = WorkspaceRecord(UUID.randomUUID().toString(), workspaceId, collection, dataJson, nowMs, nowMs)
        writableDatabase.insertOrThrow("workspace_records", null, ContentValues().apply {
            put("record_id", record.id); put("composable_workspace_id", workspaceId); put("collection_name", collection)
            put("data_json", dataJson); put("created_at", nowMs); put("updated_at", nowMs)
        })
        return record
    }

    fun listWorkspaceRecords(workspaceId: String, collection: String? = null, limit: Int = 100): List<WorkspaceRecord> {
        val where = if (collection == null) "composable_workspace_id = ?" else "composable_workspace_id = ? AND collection_name = ?"
        val args = if (collection == null) arrayOf(workspaceId) else arrayOf(workspaceId, collection)
        return readableDatabase.query("workspace_records", arrayOf("record_id","composable_workspace_id","collection_name","data_json","created_at","updated_at"), where, args, null, null, "updated_at DESC", limit.coerceIn(1, 200).toString()).use { cursor ->
            buildList { while (cursor.moveToNext()) add(WorkspaceRecord(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3), cursor.getLong(4), cursor.getLong(5))) }
        }
    }

    fun exportComposableWorkspace(workspaceId: String): JSONObject {
        val workspace = loadComposableWorkspace(workspaceId) ?: error("Unknown workspace")
        val revision = loadLiveWorkspaceRevision(workspaceId) ?: error("Workspace revision is unavailable")
        return JSONObject().apply {
            put("format", "atlas.workspace.bundle.v1"); put("exported_at_ms", System.currentTimeMillis())
            put("workspace", JSONObject().apply {
                put("id", workspace.id); put("name", workspace.name); put("description", workspace.description)
                put("status", workspace.status.name); put("created_at_ms", workspace.createdAtMs); put("updated_at_ms", workspace.updatedAtMs)
            })
            put("revision", JSONObject().apply {
                put("id", revision.id); put("content_hash", revision.contentHash); put("definition", JSONObject(revision.definitionJson))
            })
            put("records", JSONArray().apply { listWorkspaceRecords(workspaceId, limit = 200).forEach { record ->
                put(JSONObject().apply { put("id", record.id); put("collection", record.collection); put("data", JSONObject(record.dataJson)); put("updated_at_ms", record.updatedAtMs) })
            } })
            put("notice", "Capability grants and credentials are intentionally excluded.")
        }
    }

    @Synchronized
    fun deleteComposableWorkspace(workspaceId: String) {
        val workspace = loadComposableWorkspace(workspaceId) ?: error("Unknown workspace")
        require(workspace.status == WorkspaceStatus.ARCHIVED) { "Archive the workspace before deleting it" }
        writableDatabase.transaction {
            update("sessions", ContentValues().apply { putNull("active_composable_workspace_id"); put("workspace_navigation_sequence", 0) },
                "active_composable_workspace_id = ?", arrayOf(workspaceId))
            delete("workspace_records", "composable_workspace_id = ?", arrayOf(workspaceId))
            delete("workspace_revisions", "composable_workspace_id = ?", arrayOf(workspaceId))
            check(delete("composable_workspaces", "composable_workspace_id = ?", arrayOf(workspaceId)) == 1) { "Workspace deletion failed" }
        }
    }

    fun workspaceContextForSession(sessionId: String): String? = readableDatabase.rawQuery(
        "SELECT active_composable_workspace_id,workspace_navigation_sequence FROM sessions WHERE session_id = ?", arrayOf(sessionId)
    ).use { cursor ->
        if (!cursor.moveToFirst() || cursor.isNull(0)) return@use null
        val id = cursor.getString(0)
        val sequence = cursor.getLong(1)
        val workspace = loadComposableWorkspace(id) ?: return@use null
        val revision = loadWorkspaceRevision(workspace.liveRevisionId) ?: return@use null
        JSONObject().apply {
            put("id", workspace.id); put("name", workspace.name); put("description", workspace.description)
            put("revisionId", revision.id); put("navigationSequence", sequence)
            put("definition", JSONObject(revision.definitionJson)); put("recordCount", listWorkspaceRecords(id, limit = 200).size)
        }.toString()
    }

    private fun revisionValues(id: String, workspaceId: String, parent: String?, definition: String, authorType: String, sourceSessionId: String?, sourceTurnId: String?, purpose: String, nowMs: Long) = ContentValues().apply {
        put("revision_id", id); put("composable_workspace_id", workspaceId); put("parent_revision_id", parent)
        put("definition_json", definition); put("content_hash", sha256(definition)); put("author_type", authorType)
        put("source_session_id", sourceSessionId); put("source_turn_id", sourceTurnId); put("purpose", purpose.take(500)); put("created_at", nowMs)
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

    fun loadChatGptUsageSummary(sinceMs: Long = 0): ChatGptUsageSummary {
        var requests = 0
        var interactive = 0
        var background = 0
        var vision = 0
        var input = 0L
        var output = 0L
        var total = 0L
        var failures = 0
        var limits = 0
        var lastFailureCode: String? = null
        var hostedSearchUses = 0
        var lastHostedSearchAction: String? = null
        var lastHostedSearchStatus: String? = null
        val visionByRequest = mutableMapOf<String, Boolean>()
        readableDatabase.rawQuery(
            "SELECT event_type,data_json FROM events WHERE at_ms >= ? AND event_type IN ('provider.requested','provider.responded','provider.route_attempted') ORDER BY sequence",
            arrayOf(sinceMs.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val type = cursor.getString(0)
                val data = runCatching { JSONObject(cursor.getString(1)) }.getOrNull() ?: continue
                if (type == "provider.requested") {
                    data.optString("requestId").takeIf(String::isNotBlank)?.let { requestId ->
                        visionByRequest[requestId] = data.optBoolean("visionAttached", false) || data.optString("source") == "heartbeat"
                    }
                    continue
                }
                if (data.optString("endpointId") != com.grinningfrog.atlas.provider.ChatGptAuthManager.ENDPOINT_ID) continue
                if (type == "provider.responded") {
                    requests++
                    if (data.optString("source") in setOf("heartbeat", "memory_compaction")) background++ else interactive++
                    if (visionByRequest[data.optString("requestId")] == true) vision++
                    input += data.optLong("promptTokens", 0)
                    output += data.optLong("completionTokens", 0)
                    total += data.optLong("totalTokens", 0)
                    data.optJSONArray("providerToolUses")?.let { uses ->
                        for (index in 0 until uses.length()) {
                            val use = uses.optJSONObject(index) ?: continue
                            if (use.optString("type") != "web_search") continue
                            hostedSearchUses++
                            lastHostedSearchAction = use.optString("action").takeIf(String::isNotBlank)
                            lastHostedSearchStatus = use.optString("status").takeIf(String::isNotBlank)
                        }
                    }
                } else if (!data.optBoolean("success", false)) {
                    failures++
                    data.optString("errorCode").takeIf(String::isNotBlank)?.let { code ->
                        lastFailureCode = code
                        if (code == "subscription_sharing_usage_limit_exceeded") limits++
                    }
                }
            }
        }
        return ChatGptUsageSummary(requests, interactive, background, vision, input, output, total, failures, limits,
            lastFailureCode, hostedSearchUses, lastHostedSearchAction, lastHostedSearchStatus)
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
        "SELECT turn_id,session_id,status,trigger,created_at,updated_at,step_count,error FROM turns WHERE session_id = ? AND status NOT IN ('COMPLETED','COMPLETED_LATE','FAILED','CANCELLED','HARD_CANCELLED','INTERRUPTED') ORDER BY created_at DESC LIMIT 1",
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
                put("provider_context_json", message.providerContextJson)
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
    fun updateAssistantMessage(messageId: String, content: String, toolCallsJson: String? = null, providerContextJson: String? = null) {
        writableDatabase.update("messages", ContentValues().apply {
            put("content", content)
            put("tool_calls_json", toolCallsJson)
            if (providerContextJson != null) put("provider_context_json", providerContextJson)
        }, "message_id = ?", arrayOf(messageId))
    }

    @Synchronized
    fun promoteAssistantMessage(messageId: String) {
        writableDatabase.update("messages", ContentValues().apply { put("kind", MessageKind.DIALOGUE.name) }, "message_id = ?", arrayOf(messageId))
    }

    fun loadMessages(sessionId: String, afterSequence: Long = 0, limit: Int = 80): List<AtlasMessage> = readableDatabase.rawQuery(
        """SELECT sequence,message_id,session_id,turn_id,role,content,created_at,kind,tool_call_id,tool_calls_json,delivery_status,delivered_content,interrupted_sentence,provider_context_json
            FROM messages WHERE session_id = ? AND sequence > ? ORDER BY sequence DESC LIMIT ?""".trimIndent(),
        arrayOf(sessionId, afterSequence.toString(), limit.toString()),
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toMessage()) }.reversed() }

    /** Oldest-first page used by checkpointing so a failed backlog can never be skipped. */
    fun loadMessagesForCompaction(sessionId: String, afterSequence: Long, limit: Int): List<AtlasMessage> = readableDatabase.rawQuery(
        """SELECT sequence,message_id,session_id,turn_id,role,content,created_at,kind,tool_call_id,tool_calls_json,delivery_status,delivered_content,interrupted_sentence,provider_context_json
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
            rawQuery("SELECT turn_id FROM turns WHERE session_id = ? AND status IN ('CREATED','ASSEMBLING_CONTEXT','WAITING_FOR_MODEL','EXECUTING_TOOL','SOFT_TIMED_OUT')", arrayOf(sessionId)).use { cursor ->
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
    if (isNull(13)) null else getString(13),
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

private fun android.database.Cursor.toComposableWorkspace() = ComposableWorkspace(
    id = getString(0), realmId = getString(1), name = getString(2), description = getString(3),
    status = WorkspaceStatus.valueOf(getString(4)), liveRevisionId = getString(5), createdAtMs = getLong(6), updatedAtMs = getLong(7),
)

private fun android.database.Cursor.toWorkspaceRevision() = WorkspaceRevision(
    id = getString(0), workspaceId = getString(1), parentRevisionId = nullableString(2), definitionJson = getString(3),
    contentHash = getString(4), authorType = getString(5), sourceSessionId = nullableString(6), sourceTurnId = nullableString(7),
    purpose = getString(8), createdAtMs = getLong(9),
)

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

private fun jsonStringList(raw: String): List<String> = runCatching {
    val array = JSONArray(raw)
    buildList { for (index in 0 until array.length()) add(array.getString(index)) }
}.getOrDefault(emptyList())

private fun android.database.Cursor.nullableString(index: Int): String? = if (isNull(index)) null else getString(index)

private fun SessionPermissions.toJson() = JSONObject().apply {
    put("observe", observe); put("captureImage", captureImage.name); put("microphone", microphone.name); put("location", location.name)
    put("speakResponses", speakResponses); put("proactiveSpeech", proactiveSpeech)
    put("externalActionsRequireConfirmation", externalActionsRequireConfirmation)
}

private fun permissionsFromJson(raw: String?) = runCatching {
    val json = JSONObject(raw ?: "{}")
    SessionPermissions(
        observe = json.optBoolean("observe", true),
        captureImage = PermissionPolicy.valueOf(json.optString("captureImage", PermissionPolicy.ACTIVE_SESSION.name)),
        microphone = PermissionPolicy.valueOf(json.optString("microphone", PermissionPolicy.USER_REQUEST.name)),
        location = PermissionPolicy.valueOf(json.optString("location", PermissionPolicy.USER_REQUEST.name)),
        speakResponses = json.optBoolean("speakResponses", true), proactiveSpeech = json.optBoolean("proactiveSpeech", false),
        externalActionsRequireConfirmation = json.optBoolean("externalActionsRequireConfirmation", true),
    )
}.getOrElse { SessionPermissions() }

private inline fun <T> SQLiteDatabase.transaction(block: SQLiteDatabase.() -> T): T {
    beginTransaction()
    return try { val result = block(); setTransactionSuccessful(); result } finally { endTransaction() }
}
