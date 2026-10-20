package com.grinningfrog.atlas.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArchiveCodecTest {
    private fun session() = JSONObject("""{"format":"atlas.session.v1","session":[{"session_id":"source","name":"Recovered","goal":"test"}],"turns":[{"turn_id":"turn","session_id":"source","status":"WAITING_FOR_MODEL","trigger":"user","created_at":1}],"messages":[{"message_id":"message","session_id":"source","turn_id":"turn","sequence":12,"role":"USER","kind":"DIALOGUE","content":"Hello","created_at":1}],"memory":[],"toolCalls":[],"summaries":[]} """)
    @Test fun readsLegacyZipAndWarnsAboutPausedRecovery() {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { it.putNextEntry(ZipEntry("session.json")); it.write(session().toString().toByteArray()); it.closeEntry() }
        val preview = ArchiveCodec.read(output.toByteArray().inputStream())
        assertEquals("session", preview.kind); assertEquals(1, preview.itemCount)
        assertTrue(preview.warnings.any { it.contains("paused") })
    }
    @Test fun rejectsZipTraversalWithoutExtractingAnything() {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { it.putNextEntry(ZipEntry("../session.json")); it.write(session().toString().toByteArray()); it.closeEntry() }
        assertTrue(runCatching { ArchiveCodec.read(output.toByteArray().inputStream()) }.isFailure)
    }
    @Test fun rejectsCrossSessionRowsAndMissingTurns() {
        val root = session(); root.getJSONArray("messages").getJSONObject(0).put("session_id", "other")
        assertTrue(runCatching { ArchiveCodec.preview(root) }.isFailure)
        root.getJSONArray("messages").getJSONObject(0).put("session_id", "source").put("turn_id", "missing")
        assertTrue(runCatching { ArchiveCodec.preview(root) }.isFailure)
    }
    @Test fun validatesCheckpointBeforeImportPreview() {
        val root = session().put("summaries", JSONArray().put(JSONObject().put("session_id", "source").put("summary", "old checkpoint").put("through_message_sequence", 99)))
        assertTrue(ArchiveCodec.preview(root).warnings.any { it.contains("Checkpoint will be discarded") })
    }
    @Test fun identityIgnoresExportTimeAndJsonKeyOrder() {
        val first = session().put("exportedAtMs", 1)
        val reversed = JSONObject()
        first.keys().asSequence().toList().reversed().forEach { reversed.put(it, first.get(it)) }
        reversed.put("exportedAtMs", 2)
        assertEquals(ArchiveCodec.preview(first).digest, ArchiveCodec.preview(reversed).digest)
    }
    @Test fun embeddedExportTimestampsDoNotCreateDuplicateSnapshots() {
        val bundle = JSONObject().put("format", "atlas.workspace.bundle.v1").put("exported_at_ms", 1)
            .put("workspace", JSONObject().put("id", "workspace").put("name", "Portable").put("status", "ACTIVE"))
            .put("revision", JSONObject().put("definition", JSONObject(com.grinningfrog.atlas.workspace.WorkspaceDefinitionValidator.starter("Portable", ""))))
            .put("records", JSONArray())
        val first = session().put("workspaces", JSONArray().put(bundle))
        val digest = ArchiveCodec.preview(first).digest
        bundle.put("exported_at_ms", 2)
        assertEquals(digest, ArchiveCodec.preview(first).digest)
    }

    @Test fun rejectsMissingArraysRatherThanPretendingEmptyHistory() {
        val root = session(); root.remove("messages")
        assertTrue(runCatching { ArchiveCodec.preview(root) }.isFailure)
    }
}
