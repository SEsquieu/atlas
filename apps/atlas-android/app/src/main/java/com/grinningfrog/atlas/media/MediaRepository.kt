package com.grinningfrog.atlas.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import com.grinningfrog.atlas.model.InferenceImage
import com.grinningfrog.atlas.model.MediaPurpose
import com.grinningfrog.atlas.model.MediaRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.max

data class ImageBudget(val longestEdge: Int, val initialQuality: Int, val maxBytes: Int) {
    companion object {
        fun forPurpose(purpose: MediaPurpose) = when (purpose) {
            MediaPurpose.HEARTBEAT -> ImageBudget(640, 70, 150_000)
            MediaPurpose.STANDARD_VISION -> ImageBudget(1024, 78, 350_000)
            MediaPurpose.DETAIL_VISION -> ImageBudget(1600, 82, 750_000)
        }
    }
}

private data class EncodedImage(val bytes: ByteArray, val width: Int, val height: Int)

/** Owns private media storage and conversion. Providers never receive filesystem paths. */
class MediaRepository(private val context: Context) {
    private val root = File(context.filesDir, "media/v1").apply { mkdirs() }

    suspend fun ingestCameraJpeg(rawFile: File, sessionId: String, purpose: MediaPurpose): MediaRef = withContext(Dispatchers.Default) {
        val started = System.nanoTime()
        val rawBytes = rawFile.length()
        try {
            val budget = ImageBudget.forPurpose(purpose)
            val decoded = decodeSampled(rawFile, budget.longestEdge)
            val oriented = orient(decoded, ExifInterface(rawFile.absolutePath))
            if (oriented !== decoded) decoded.recycle()
            val scaled = scale(oriented, budget.longestEdge)
            if (scaled !== oriented) oriented.recycle()
            val encoded = try { encodeWithinBudget(scaled, budget) } finally { scaled.recycle() }

            val mediaId = UUID.randomUUID().toString()
            val key = "$sessionId/$mediaId.jpg"
            val output = resolveStorageKey(key)
            output.parentFile?.mkdirs()
            val temporary = File(output.parentFile, ".${output.name}.tmp")
            temporary.writeBytes(encoded.bytes)
            check(temporary.renameTo(output)) { "Could not commit processed image" }
            MediaRef(
                id = mediaId,
                storageKey = key,
                mimeType = "image/jpeg",
                width = encoded.width,
                height = encoded.height,
                byteSize = encoded.bytes.size.toLong(),
                sha256 = encoded.bytes.sha256(),
                purpose = purpose,
                rawByteSize = rawBytes,
                processingMs = elapsedMs(started),
            )
        } finally {
            rawFile.delete()
        }
    }

    suspend fun inferenceImage(media: MediaRef): InferenceImage = withContext(Dispatchers.IO) {
        val bytes = resolve(media).readBytes()
        if (media.byteSize > 0) check(bytes.size.toLong() == media.byteSize) { "Media size changed for ${media.id}" }
        val digest = bytes.sha256()
        if (media.sha256.isNotBlank()) check(digest == media.sha256) { "Media integrity check failed for ${media.id}" }
        InferenceImage(media.id, media.mimeType, media.width, media.height, digest, bytes)
    }

    fun resolve(media: MediaRef): File = resolveStorageKey(media.storageKey).also {
        check(it.exists()) { "Media is no longer available: ${media.id}" }
    }

    fun fingerprint(media: MediaRef): String? {
        val source = BitmapFactory.decodeFile(resolve(media).absolutePath) ?: return null
        val scaled = Bitmap.createScaledBitmap(source, 8, 8, true)
        val result = buildString(128) {
            for (y in 0 until 8) for (x in 0 until 8) {
                val pixel = scaled.getPixel(x, y)
                val luma = (android.graphics.Color.red(pixel) * 299 + android.graphics.Color.green(pixel) * 587 + android.graphics.Color.blue(pixel) * 114) / 1000
                append(luma.toString(16).padStart(2, '0'))
            }
        }
        if (scaled !== source) scaled.recycle()
        source.recycle()
        return result
    }

    private fun resolveStorageKey(key: String): File {
        if (File(key).isAbsolute) return File(key) // v1 database compatibility only
        val candidate = File(root, key).canonicalFile
        check(candidate.path.startsWith(root.canonicalPath + File.separator)) { "Invalid media storage key" }
        return candidate
    }

    private fun decodeSampled(file: File, longestEdge: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        check(bounds.outWidth > 0 && bounds.outHeight > 0) { "Camera returned an unreadable image" }
        var sample = 1
        while (max(bounds.outWidth / sample, bounds.outHeight / sample) > longestEdge * 2) sample *= 2
        return checkNotNull(BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }))
    }

    private fun orient(bitmap: Bitmap, exif: ExifInterface): Bitmap {
        val rotation = when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        return if (rotation == 0f) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(rotation) }, true)
    }

    private fun scale(bitmap: Bitmap, longestEdge: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= longestEdge) return bitmap
        val ratio = longestEdge.toDouble() / longest
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt(), (bitmap.height * ratio).toInt(), true)
    }

    private fun encodeWithinBudget(source: Bitmap, budget: ImageBudget): EncodedImage {
        var bitmap = source
        var ownsBitmap = false
        try {
            repeat(4) {
                var quality = budget.initialQuality
                while (quality >= 48) {
                    val output = ByteArrayOutputStream()
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) { "JPEG encoding failed" }
                    val bytes = output.toByteArray()
                    if (bytes.size <= budget.maxBytes) return EncodedImage(bytes, bitmap.width, bitmap.height)
                    quality -= 8
                }
                val smaller = Bitmap.createScaledBitmap(bitmap, (bitmap.width * .82).toInt(), (bitmap.height * .82).toInt(), true)
                if (ownsBitmap) bitmap.recycle()
                bitmap = smaller
                ownsBitmap = true
            }
            error("Could not satisfy image byte budget")
        } finally {
            if (ownsBitmap) bitmap.recycle()
        }
    }

    private fun ByteArray.sha256() = MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }
    private fun elapsedMs(started: Long) = (System.nanoTime() - started) / 1_000_000
}
