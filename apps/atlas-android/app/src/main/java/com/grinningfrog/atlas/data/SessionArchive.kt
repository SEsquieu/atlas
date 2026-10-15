package com.grinningfrog.atlas.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.grinningfrog.atlas.media.MediaRepository
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SessionArchive(
    private val context: Context,
    val database: AtlasDatabase,
    private val mediaRepository: MediaRepository,
) {
    internal fun writeExport(sessionId: String): File {
        val directory = File(context.cacheDir, "exports").apply { mkdirs() }
        directory.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > DAY_MS) it.delete() }
        val file = File(directory, "atlas-session-${sessionId.take(8)}.zip")
        val bundle = database.exportSession(sessionId)
        val snapshot = bundle.toString(2).toByteArray()
        val transcript = buildString {
            val session = bundle.getJSONArray("session").getJSONObject(0)
            appendLine(session.getString("name")); appendLine("Goal: ${session.optString("goal")}"); appendLine()
            val messages = bundle.getJSONArray("messages")
            for (index in 0 until messages.length()) {
                val message = messages.getJSONObject(index)
                if (message.getString("kind") == "DIALOGUE") appendLine("${if (message.getString("role") == "USER") "You" else "Atlas"}: ${message.getString("content")}")
            }
        }.toByteArray()
        require(snapshot.size.toLong() + transcript.size <= ArchiveCodec.MAX_BYTES - 128 * 1024) { "Session archive plus readable transcript exceeds 32 MiB; no partial export was created" }
        val diagnostics = com.grinningfrog.atlas.diagnostics.RuntimeDiagnostics.snapshot(context).toString(2).toByteArray()
        require(diagnostics.size <= 128 * 1024) { "Runtime diagnostics exceed export limit" }
        val images = mutableListOf<Pair<String, ByteArray>>()
        val imageIndex = org.json.JSONArray()
        var available = (ArchiveCodec.MAX_BYTES - snapshot.size - transcript.size - diagnostics.size - 64 * 1024).coerceAtMost(8 * 1024 * 1024)
        val observations = bundle.getJSONArray("observations")
        var totalCaptures = 0
        for (i in observations.length() - 1 downTo 0) {
            val row = observations.getJSONObject(i)
            if (row.optString("media_purpose") != "WORKSPACE_UI") continue
            totalCaptures++
            if (imageIndex.length() >= 64) continue
            val entry = org.json.JSONObject().put("observation_id", row.getString("observation_id")).put("media_id", row.getString("media_id"))
                .put("observed_at_ms", row.getLong("observed_at")).put("summary", row.optString("summary").take(300))
            runCatching {
                require(images.size < 8) { "Export image-count limit" }
                val ref = com.grinningfrog.atlas.model.MediaRef(row.getString("media_id"), row.getString("media_path"), row.getString("media_mime"),
                    row.getInt("media_width"), row.getInt("media_height"), row.getLong("media_bytes"), row.getString("media_sha256"),
                    com.grinningfrog.atlas.model.MediaPurpose.WORKSPACE_UI, row.optLong("media_raw_bytes"), row.optLong("media_processing_ms"))
                val source = mediaRepository.resolve(ref)
                require(source.length() in 1..minOf(available.toLong(), 2 * 1024 * 1024L)) { "Export image-byte limit" }
                val bytes = source.readBytes()
                require(bytes.size.toLong() == ref.byteSize) { "Image size mismatch" }
                val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                require(digest == ref.sha256) { "Image integrity mismatch" }
                val path = "workspace-images/${images.size + 1}.jpg"
                images += path to bytes; available -= bytes.size
                entry.put("path", path).put("sha256", digest).put("included", true)
            }.onFailure { entry.put("included", false).put("reason", (it.message ?: "Image unavailable").take(300)) }
            imageIndex.put(entry)
        }
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("workspace-images/index.json")); zip.write(org.json.JSONObject().put("total_captures", totalCaptures).put("entries", imageIndex).put("scope", "Newest 64 capture metadata entries; up to eight available workspace-only images. Physical camera images excluded.").toString(2).toByteArray()); zip.closeEntry()
            for ((path, bytes) in images) { zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry() }
            zip.putNextEntry(ZipEntry("runtime-diagnostics.json"))
            zip.write(diagnostics)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("transcript.txt"))
            zip.write(transcript)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("session.json"))
            zip.write(snapshot)
            zip.closeEntry()
        }
        return file
    }

    fun share(sessionId: String) {
        val file = writeExport(sessionId)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.exports", file)
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }, "Save or share Atlas session").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun delete(sessionId: String) {
        mediaRepository.deleteStorageKeys(database.deleteSession(sessionId))
    }

    fun enforceMediaRetention(days: Int): Int {
        val keys = database.pruneExpiredObservations(System.currentTimeMillis() - days.coerceIn(1, 30) * DAY_MS)
        return mediaRepository.deleteStorageKeys(keys)
    }

    private companion object { const val DAY_MS = 86_400_000L }
}
