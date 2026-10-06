package com.grinningfrog.atlas.data

import android.app.Application
import com.grinningfrog.atlas.model.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ArchiveDatabaseTest {
    private lateinit var db: AtlasDatabase
    @Before fun setup() { RuntimeEnvironment.getApplication().deleteDatabase("atlas.db"); db = AtlasDatabase(RuntimeEnvironment.getApplication()); db.writableDatabase }
    @After fun cleanup() { db.close() }
    private fun source(): JSONObject = JSONObject("""{
        "format":"atlas.session.v1","session":[{"session_id":"old","name":"Recovery","goal":"test","active_composable_workspace_id":"workspace-old"}],
        "turns":[{"session_id":"old","turn_id":"turn-old","status":"WAITING_FOR_MODEL","trigger":"user","created_at":1,"updated_at":2}],
        "messages":[{"sequence":99,"session_id":"old","message_id":"msg-old","turn_id":"turn-old","role":"USER","kind":"DIALOGUE","content":"hello","created_at":1,"provider_context_json":"stale"}],
        "toolCalls":[{"session_id":"old","tool_call_id":"call-old","turn_id":"turn-old","name":"test","arguments_json":"{}","status":"RUNNING","risk":"SESSION_WRITE","created_at":1,"updated_at":2}],
        "memory":[{"session_id":"old","memory_id":"memory-old","kind":"DURABLE","status":"ACTIVE","content":"private fact","confidence":1,"created_at":1,"updated_at":2}],
        "summaries":[{"session_id":"old","summary":"checkpoint","through_message_sequence":99}],"events":[],"observations":[],"speechSegments":[],"clarifications":[]
    }""")
    private fun workspace(): JSONObject {
        val local = db.createComposableWorkspace("Portable", "test")
        return db.exportComposableWorkspace(local.id).apply { getJSONObject("workspace").put("id", "workspace-old") }
    }
    @Test fun sessionRestoresPausedWithRemappedCheckpointAndScopedMemory() {
        val imported = db.importArchive(ArchiveCodec.preview(source()))
        assertEquals(SessionStatus.PAUSED, db.loadSession(imported.id)!!.status)
        assertNull(db.loadActiveTurn(imported.id)); assertTrue(db.loadPendingToolCalls(imported.id).isEmpty())
        val messages = db.loadMessages(imported.id)
        assertNull(messages.single().providerContextJson)
        assertEquals(messages.single().sequence, db.loadSummary(imported.id)!!.throughMessageSequence)
        assertTrue(messages.single().sequence != 99L)
        val memory = db.loadActiveMemories(imported.id).single()
        assertEquals(MemoryScope.SESSION, memory.scope); assertEquals(MemoryKind.WORKING, memory.kind)
        assertTrue(db.loadRecentTurns(imported.id).all { it.status == TurnStatus.INTERRUPTED })
        assertEquals(imported.id, db.importArchive(ArchiveCodec.preview(source())).id)
        assertTrue(db.importArchive(ArchiveCodec.preview(source())).duplicate)
    }
    @Test fun legacyWorkspaceReconnectsInEitherOrderAndNewBundlesEmbedIt() {
        val bundle = workspace()
        val session = db.importArchive(ArchiveCodec.preview(source()))
        assertNull(db.loadSession(session.id)!!.activeComposableWorkspaceId)
        val restored = db.importArchive(ArchiveCodec.preview(bundle))
        assertEquals(restored.id, db.loadSession(session.id)!!.activeComposableWorkspaceId)
        assertEquals(1, db.exportSession(session.id).getJSONArray("workspaces").length())
        val newer = source().apply { getJSONArray("messages").getJSONObject(0).put("content", "second snapshot") }
        val other = db.importArchive(ArchiveCodec.preview(newer))
        assertTrue(other.id != session.id)
        assertEquals(restored.id, db.loadSession(other.id)!!.activeComposableWorkspaceId)
    }
    @Test fun failureAfterEmbeddedWorkspaceRollsBackEverything() {
        val before = db.listComposableWorkspaces(true).size
        val bundle = workspace()
        val root = source().put("workspaces", JSONArray().put(bundle))
        // Parser validates IDs and types; this missing timestamp fails only during transactional restoration.
        root.getJSONArray("memory").getJSONObject(0).remove("created_at")
        assertTrue(runCatching { db.importArchive(ArchiveCodec.preview(root)) }.isFailure)
        assertEquals(before + 1, db.listComposableWorkspaces(true).size)
        assertNull(db.loadLatestSession())
    }
    @Test fun exportsAllRecordsBeyondLegacyTwoHundredLimit() {
        val workspace = db.createComposableWorkspace("Many records", "")
        repeat(250) { db.addWorkspaceRecord(workspace.id, "legacy", "{}") }
        assertEquals(250, db.exportComposableWorkspace(workspace.id).getJSONArray("records").length())
    }
    @Test fun invalidCheckpointIsDiscardedAndOriginalAuditSurvivesAnotherCycle() {
        val root = source().apply { getJSONArray("summaries").getJSONObject(0).put("through_message_sequence", 1000) }
        val first = db.importArchive(ArchiveCodec.preview(root))
        assertNull(db.loadSummary(first.id))
        assertTrue(db.archiveContextNotice(first.id)!!.contains("Checkpoint discarded"))
        val second = db.importArchive(ArchiveCodec.preview(db.exportSession(first.id)))
        val exported = db.exportSession(second.id)
        val audit = JSONObject(exported.getJSONArray("importAudit").getJSONObject(0).getString("payload"))
        assertTrue(audit.has("historicalAudit"))
        assertTrue(db.loadPendingToolCalls(second.id).isEmpty())
    }
}
