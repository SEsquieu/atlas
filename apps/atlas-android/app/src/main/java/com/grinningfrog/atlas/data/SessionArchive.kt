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
    fun share(sessionId: String) {
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
        require(snapshot.size.toLong() + transcript.size <= ArchiveCodec.MAX_BYTES - 64 * 1024) { "Session archive plus readable transcript exceeds 32 MiB; no partial export was created" }
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("transcript.txt"))
            zip.write(transcript)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("session.json"))
            zip.write(snapshot)
            zip.closeEntry()
        }
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
