package com.grinningfrog.atlas.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.grinningfrog.atlas.model.AtlasEvent
import com.grinningfrog.atlas.model.AgentTurn
import com.grinningfrog.atlas.model.AtlasMessage
import com.grinningfrog.atlas.model.AtlasSession
import com.grinningfrog.atlas.model.AtlasToolCall
import com.grinningfrog.atlas.model.ContextStability
import com.grinningfrog.atlas.model.ContextMode
import com.grinningfrog.atlas.model.DeliveryStatus
import com.grinningfrog.atlas.model.MotionState
import com.grinningfrog.atlas.model.MemoryItem
import com.grinningfrog.atlas.model.MemoryKind
import com.grinningfrog.atlas.model.MemoryStatus
import com.grinningfrog.atlas.model.MessageKind
import com.grinningfrog.atlas.model.MessageRole
import com.grinningfrog.atlas.model.MediaPurpose
import com.grinningfrog.atlas.model.MediaRef
import com.grinningfrog.atlas.model.ObservationTiming
import com.grinningfrog.atlas.model.PermissionPolicy
import com.grinningfrog.atlas.model.SessionPermissions
import com.grinningfrog.atlas.model.SessionStatus
import com.grinningfrog.atlas.model.SessionSummary
import com.grinningfrog.atlas.model.SpeechSegment
import com.grinningfrog.atlas.model.SpeechSegmentStatus
import com.grinningfrog.atlas.model.ToolCallStatus
import com.grinningfrog.atlas.model.ToolRisk
import com.grinningfrog.atlas.model.TurnStatus
import com.grinningfrog.atlas.model.VisualObservation
import org.json.JSONObject
import java.util.UUID

class AtlasDatabase(context: Context) : SQLiteOpenHelper(context, "atlas.db", null, 6) {
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
                kind TEXT NOT NULL,
                content TEXT NOT NULL,
                status TEXT NOT NULL,
                confidence REAL NOT NULL,
                source_turn_id TEXT,
                evidence_observation_id TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                expires_at INTEGER,
                FOREIGN KEY(session_id) REFERENCES sessions(session_id)
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS memory_session_status ON memory_items(session_id, status, kind)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS session_summaries (
                session_id TEXT PRIMARY KEY,
                summary TEXT NOT NULL,
                through_message_sequence INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                FOREIGN KEY(session_id) REFERENCES sessions(session_id)
            )""".trimIndent()
        )
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE sessions (
                session_id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                goal TEXT NOT NULL,
                status TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                context_mode TEXT NOT NULL DEFAULT 'MANUAL',
                permissions_json TEXT NOT NULL DEFAULT '{}'
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE events (
                sequence INTEGER PRIMARY KEY AUTOINCREMENT,
                event_id TEXT NOT NULL UNIQUE,
                session_id TEXT NOT NULL,
                event_type TEXT NOT NULL,
                at_ms INTEGER NOT NULL,
                data_json TEXT NOT NULL,
                FOREIGN KEY(session_id) REFERENCES sessions(session_id)
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX events_session_sequence ON events(session_id, sequence)")
        db.execSQL(
            """CREATE TABLE observations (
                observation_id TEXT PRIMARY KEY,
                session_id TEXT NOT NULL,
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
                FOREIGN KEY(session_id) REFERENCES sessions(session_id)
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
    }

    @Synchronized
    fun createSession(name: String, goal: String, nowMs: Long = System.currentTimeMillis()): AtlasSession {
        val session = AtlasSession(
            id = UUID.randomUUID().toString(), name = name, goal = goal,
            status = SessionStatus.IDLE, createdAtMs = nowMs, updatedAtMs = nowMs,
        )
        writableDatabase.transaction {
            insertOrThrow("sessions", null, ContentValues().apply {
                put("session_id", session.id); put("name", name); put("goal", goal)
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
        "SELECT session_id,name,goal,status,created_at,updated_at,context_mode,permissions_json FROM sessions ORDER BY updated_at DESC LIMIT 1", null
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else AtlasSession(
            id = cursor.getString(0), name = cursor.getString(1), goal = cursor.getString(2),
            status = SessionStatus.valueOf(cursor.getString(3)), createdAtMs = cursor.getLong(4), updatedAtMs = cursor.getLong(5),
            contextMode = ContextMode.valueOf(cursor.getString(6)), permissions = permissionsFromJson(cursor.getString(7)),
        )
    }

    @Synchronized
    fun appendEvent(sessionId: String, type: String, data: JSONObject = JSONObject(), atMs: Long = System.currentTimeMillis()): AtlasEvent =
        appendEventLocked(writableDatabase, sessionId, type, atMs, data.toString())

    private fun appendEventLocked(db: SQLiteDatabase, sessionId: String, type: String, atMs: Long, json: String): AtlasEvent {
        val eventId = UUID.randomUUID().toString()
        val sequence = db.insertOrThrow("events", null, ContentValues().apply {
            put("event_id", eventId); put("session_id", sessionId); put("event_type", type); put("at_ms", atMs); put("data_json", json)
        })
        return AtlasEvent(sequence, eventId, sessionId, type, atMs, json)
    }

    @Synchronized
    fun saveObservation(observation: VisualObservation) {
        writableDatabase.transaction {
            insertOrThrow("observations", null, ContentValues().apply {
                put("observation_id", observation.id); put("session_id", observation.sessionId); put("media_path", observation.media.storageKey)
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
        "SELECT sequence,event_id,session_id,event_type,at_ms,data_json FROM events WHERE session_id = ? ORDER BY sequence DESC LIMIT ?",
        arrayOf(sessionId, limit.toString())
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(AtlasEvent(cursor.getLong(0), cursor.getString(1), cursor.getString(2), cursor.getString(3), cursor.getLong(4), cursor.getString(5)))
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
            insertWithOnConflict("memory_items", null, ContentValues().apply {
                put("memory_id", item.id); put("session_id", item.sessionId); put("kind", item.kind.name); put("content", item.content)
                put("status", item.status.name); put("confidence", item.confidence); put("source_turn_id", item.sourceTurnId)
                put("evidence_observation_id", item.evidenceObservationId)
                put("created_at", item.createdAtMs); put("updated_at", item.updatedAtMs); put("expires_at", item.expiresAtMs)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            appendEventLocked(this, item.sessionId, "memory.${item.status.name.lowercase()}", item.updatedAtMs, JSONObject().apply {
                put("memoryId", item.id); put("kind", item.kind.name.lowercase()); put("content", item.content)
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
        "SELECT 1 FROM memory_items WHERE memory_id = ? AND (session_id = ? OR kind = 'DURABLE') LIMIT 1",
        arrayOf(memoryId, sessionId),
    ).use { it.moveToFirst() }

    fun accessibleMemoryKind(memoryId: String, sessionId: String): MemoryKind? = readableDatabase.rawQuery(
        "SELECT kind FROM memory_items WHERE memory_id = ? AND (session_id = ? OR kind = 'DURABLE') LIMIT 1",
        arrayOf(memoryId, sessionId),
    ).use { cursor -> if (cursor.moveToFirst()) MemoryKind.valueOf(cursor.getString(0)) else null }

    fun observationBelongsToSession(observationId: String, sessionId: String): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM observations WHERE observation_id = ? AND session_id = ? LIMIT 1",
        arrayOf(observationId, sessionId),
    ).use { it.moveToFirst() }

    fun loadActiveMemories(sessionId: String, nowMs: Long = System.currentTimeMillis()): List<MemoryItem> = readableDatabase.rawQuery(
        """SELECT memory_id,session_id,kind,content,status,confidence,source_turn_id,evidence_observation_id,created_at,updated_at,expires_at
            FROM memory_items WHERE (session_id = ? OR kind = 'DURABLE') AND status = 'ACTIVE' AND (expires_at IS NULL OR expires_at > ?)
            ORDER BY kind,updated_at DESC""".trimIndent(),
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
)

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
