package com.grinningfrog.atlas.device

import android.content.Context
import android.graphics.BitmapFactory
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import com.grinningfrog.atlas.model.ContextStability
import com.grinningfrog.atlas.model.MotionState
import com.grinningfrog.atlas.model.ObservationTiming
import com.grinningfrog.atlas.model.VisualObservation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val motion: MotionMonitor,
) {
    private var provider: ProcessCameraProvider? = null
    private var capture: ImageCapture? = null

    suspend fun start() = withContext(Dispatchers.Main.immediate) {
        val cameraProvider = ProcessCameraProvider.getInstance(context).await()
        val resolution = ResolutionSelector.Builder().setResolutionStrategy(
            ResolutionStrategy(android.util.Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
        ).build()
        val imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setResolutionSelector(resolution)
            .build()
        cameraProvider.unbindAll()
        cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, imageCapture)
        provider = cameraProvider
        capture = imageCapture
    }

    fun stop() {
        provider?.unbindAll()
        provider = null
        capture = null
    }

    suspend fun capture(sessionId: String, reason: String): VisualObservation {
        val imageCapture = capture ?: throw IllegalStateException("Camera is not ready")
        val startedNanos = System.nanoTime()
        val observedAtMs = System.currentTimeMillis()
        val directory = File(context.filesDir, "captures/$sessionId").apply { mkdirs() }
        val output = File(directory, "${observedAtMs}-${reason.safeName()}.jpg")
        val captureStarted = System.nanoTime()
        imageCapture.takePicture(ImageCapture.OutputFileOptions.Builder(output).build()).await()
        val captureMs = elapsedMs(captureStarted)
        val processingStarted = System.nanoTime()
        val fingerprint = withContext(Dispatchers.Default) { fingerprint(output) }
        val processingMs = elapsedMs(processingStarted)
        val availableAtMs = System.currentTimeMillis()
        return VisualObservation(
            sessionId = sessionId,
            mediaPath = output.absolutePath,
            observedAtMs = observedAtMs,
            availableAtMs = availableAtMs,
            timing = ObservationTiming(elapsedMs(startedNanos), captureMs, processingMs),
            stability = motion.stability(),
            motionState = motion.state.value,
            sceneFingerprint = fingerprint,
        )
    }

    private fun fingerprint(file: File): String? {
        val source = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        val scaled = android.graphics.Bitmap.createScaledBitmap(source, 8, 8, true)
        val result = buildString(128) {
            for (y in 0 until 8) for (x in 0 until 8) {
                val pixel = scaled.getPixel(x, y)
                val luma = ((android.graphics.Color.red(pixel) * 299 + android.graphics.Color.green(pixel) * 587 + android.graphics.Color.blue(pixel) * 114) / 1000)
                append(luma.toString(16).padStart(2, '0'))
            }
        }
        if (scaled !== source) scaled.recycle()
        source.recycle()
        return result
    }

    private fun String.safeName() = lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(36).ifBlank { "capture" }
    private fun elapsedMs(startedNanos: Long) = (System.nanoTime() - startedNanos) / 1_000_000
}

private suspend fun <T> ListenableFuture<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addListener({
        try { continuation.resume(get()) } catch (error: Exception) { continuation.resumeWithException(error) }
    }, Executor { command -> command.run() })
}

private suspend fun ImageCapture.takePicture(options: ImageCapture.OutputFileOptions): ImageCapture.OutputFileResults =
    suspendCancellableCoroutine { continuation ->
        takePicture(options, androidx.core.os.ExecutorCompat.create(android.os.Handler(android.os.Looper.getMainLooper())), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) = continuation.resume(outputFileResults)
            override fun onError(exception: ImageCaptureException) = continuation.resumeWithException(exception)
        })
    }
