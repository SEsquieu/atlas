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
import org.json.JSONObject
import java.util.UUID

class AtlasDatabase(context: Context) : SQLiteOpenHelper(context, "atlas.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE sessions (
                session_id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                goal TEXT NOT NULL,
                status TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
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
                FOREIGN KEY(session_id) REFERENCES sessions(session_id)
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX observations_session_time ON observations(session_id, observed_at DESC)")
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
        "SELECT session_id,name,goal,status,created_at,updated_at FROM sessions ORDER BY updated_at DESC LIMIT 1", null
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else AtlasSession(
            id = cursor.getString(0), name = cursor.getString(1), goal = cursor.getString(2),
            status = SessionStatus.valueOf(cursor.getString(3)), createdAtMs = cursor.getLong(4), updatedAtMs = cursor.getLong(5),
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
        "SELECT sequence,event_id,session_id,event_type,at_ms,data_json FROM events WHERE session_id = ? ORDER BY sequence DESC LIMIT ?",
        arrayOf(sessionId, limit.toString())
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(AtlasEvent(cursor.getLong(0), cursor.getString(1), cursor.getString(2), cursor.getString(3), cursor.getLong(4), cursor.getString(5)))
        }.reversed()
    }
}

private inline fun <T> SQLiteDatabase.transaction(block: SQLiteDatabase.() -> T): T {
    beginTransaction()
    return try { val result = block(); setTransactionSuccessful(); result } finally { endTransaction() }
}
