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
    private val database: AtlasDatabase,
    private val mediaRepository: MediaRepository,
) {
    fun share(sessionId: String) {
        val directory = File(context.cacheDir, "exports").apply { mkdirs() }
        directory.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > DAY_MS) it.delete() }
        val file = File(directory, "atlas-session-${sessionId.take(8)}.zip")
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("transcript.txt"))
            zip.write(database.exportTranscript(sessionId).toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("session.json"))
            zip.write(database.exportSession(sessionId).toString(2).toByteArray())
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
