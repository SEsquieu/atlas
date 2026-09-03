package com.grinningfrog.atlas.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.grinningfrog.atlas.model.AtlasEvent
import com.grinningfrog.atlas.model.AtlasSession
import com.grinningfrog.atlas.model.ContextStability
import com.grinningfrog.atlas.model.MotionState
import com.grinningfrog.atlas.model.ObservationTiming
import com.grinningfrog.atlas.model.SessionStatus
import com.grinningfrog.atlas.model.VisualObservation
import org.json.JSONObject
import java.util.UUID

class AtlasDatabase(context: Context) : SQLiteOpenHelper(context, "atlas.db", null, 1) {
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

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

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
                put("observation_id", observation.id); put("session_id", observation.sessionId); put("media_path", observation.mediaPath)
                put("observed_at", observation.observedAtMs); put("available_at", observation.availableAtMs)
                put("total_ms", observation.timing.totalMs); put("capture_ms", observation.timing.captureMs); put("processing_ms", observation.timing.processingMs)
                observation.confidence?.let { put("confidence", it) }
                put("stability", observation.stability.name); put("motion_state", observation.motionState.name)
                put("fingerprint", observation.sceneFingerprint); put("summary", observation.summary)
            })
            appendEventLocked(this, observation.sessionId, "observation.captured", observation.availableAtMs, JSONObject().apply {
                put("observationId", observation.id); put("observedAtMs", observation.observedAtMs); put("availableAtMs", observation.availableAtMs)
                put("mediaPath", observation.mediaPath); put("totalMs", observation.timing.totalMs); put("captureMs", observation.timing.captureMs)
                put("processingMs", observation.timing.processingMs); put("motionState", observation.motionState.name)
            }.toString())
            update("sessions", ContentValues().apply { put("updated_at", observation.availableAtMs) }, "session_id = ?", arrayOf(observation.sessionId))
        }
    }

    fun loadLatestObservation(sessionId: String): VisualObservation? = readableDatabase.rawQuery(
        """SELECT observation_id,media_path,observed_at,available_at,total_ms,capture_ms,processing_ms,
            confidence,stability,motion_state,fingerprint,summary FROM observations WHERE session_id = ? ORDER BY observed_at DESC LIMIT 1""".trimIndent(),
        arrayOf(sessionId)
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else VisualObservation(
            id = cursor.getString(0), sessionId = sessionId, mediaPath = cursor.getString(1), observedAtMs = cursor.getLong(2), availableAtMs = cursor.getLong(3),
            timing = ObservationTiming(cursor.getLong(4), cursor.getLong(5), cursor.getLong(6)),
            confidence = if (cursor.isNull(7)) null else cursor.getDouble(7),
            stability = ContextStability.valueOf(cursor.getString(8)), motionState = MotionState.valueOf(cursor.getString(9)),
            sceneFingerprint = if (cursor.isNull(10)) null else cursor.getString(10), summary = if (cursor.isNull(11)) null else cursor.getString(11),
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
