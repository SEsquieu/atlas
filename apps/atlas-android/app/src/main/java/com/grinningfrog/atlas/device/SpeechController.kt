package com.grinningfrog.atlas.device

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class SpeechRecognitionResult(val text: String, val confidence: Float? = null)

data class ListeningCallbacks(
    val onReady: () -> Unit = {},
    val onSpeechStarted: () -> Unit = {},
    val onSpeechEnded: () -> Unit = {},
    val onPartial: (String) -> Unit = {},
)

data class SpeechCallbacks(
    val onStarted: () -> Unit = {},
    val onCompleted: () -> Unit = {},
    val onInterrupted: () -> Unit = {},
    val onFailed: (Throwable) -> Unit = {},
)

/** Android speech I/O only. Atlas runtime owns turns, delivery truth, and interruption semantics. */
class SpeechController(private val context: Context) {
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private val speechCallbacks = ConcurrentHashMap<String, SpeechCallbacks>()
    private val mainHandler = Handler(Looper.getMainLooper())

    val isSpeaking: Boolean get() = speechCallbacks.isNotEmpty()

    suspend fun start() = withContext(Dispatchers.Main.immediate) {
        if (SpeechRecognizer.isRecognitionAvailable(context) && recognizer == null) recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        if (tts == null) tts = suspendCancellableCoroutine { continuation ->
            lateinit var engine: TextToSpeech
            engine = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    engine.language = Locale.getDefault()
                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) { utteranceId?.let { speechCallbacks[it]?.onStarted?.invoke() } }
                        override fun onDone(utteranceId: String?) { utteranceId?.let { speechCallbacks.remove(it)?.onCompleted?.invoke() } }
                        override fun onStop(utteranceId: String?, interrupted: Boolean) { utteranceId?.let { speechCallbacks.remove(it)?.onInterrupted?.invoke() } }
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) = fail(utteranceId, IllegalStateException("TTS failed"))
                        override fun onError(utteranceId: String?, errorCode: Int) = fail(utteranceId, IllegalStateException("TTS failed: $errorCode"))
                        private fun fail(id: String?, error: Throwable) { id?.let { speechCallbacks.remove(it)?.onFailed?.invoke(error) } }
                    })
                    if (continuation.isActive) continuation.resume(engine) else engine.shutdown()
                } else if (continuation.isActive) continuation.resumeWithException(IllegalStateException("TTS initialization failed: $status"))
            }
            continuation.invokeOnCancellation { engine.shutdown() }
        }
    }

    suspend fun listenOnce(
        language: String = Locale.getDefault().toLanguageTag(),
        callbacks: ListeningCallbacks = ListeningCallbacks(),
    ): SpeechRecognitionResult = withContext(Dispatchers.Main.immediate) {
        val engine = recognizer ?: throw IllegalStateException("Speech recognition is unavailable")
        suspendCancellableCoroutine { continuation ->
            engine.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle) {
                    val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
                    val confidence = results.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)?.firstOrNull()?.takeIf { it >= 0f }
                    if (!continuation.isActive) return
                    if (text.isNullOrBlank()) continuation.resumeWithException(IllegalStateException("No speech recognized"))
                    else continuation.resume(SpeechRecognitionResult(text, confidence))
                }
                override fun onError(error: Int) {
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Speech recognition failed: $error"))
                }
                override fun onReadyForSpeech(params: Bundle?) { readyCue(); callbacks.onReady() }
                override fun onBeginningOfSpeech() = callbacks.onSpeechStarted()
                override fun onEndOfSpeech() = callbacks.onSpeechEnded()
                override fun onPartialResults(results: Bundle?) {
                    results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()?.takeIf(String::isNotBlank)?.let(callbacks.onPartial)
                }
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            engine.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            })
            continuation.invokeOnCancellation { engine.cancel() }
        }
    }

    suspend fun enqueueSpeech(utteranceId: String, text: String, flushQueue: Boolean, callbacks: SpeechCallbacks) = withContext(Dispatchers.Main.immediate) {
        val engine = tts ?: throw IllegalStateException("TTS is unavailable")
        speechCallbacks[utteranceId] = callbacks
        val result = engine.speak(text, if (flushQueue) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, utteranceId)
        if (result == TextToSpeech.ERROR) {
            val error = IllegalStateException("TTS request failed")
            speechCallbacks.remove(utteranceId)
            callbacks.onFailed(error)
            throw error
        }
    }

    suspend fun speak(text: String) = suspendCancellableCoroutine { continuation ->
        val id = UUID.randomUUID().toString()
        mainHandler.post {
            val engine = tts
            if (engine == null) {
                if (continuation.isActive) continuation.resumeWithException(IllegalStateException("TTS is unavailable"))
                return@post
            }
            speechCallbacks[id] = SpeechCallbacks(
                onCompleted = { if (continuation.isActive) continuation.resume(Unit) },
                onInterrupted = { if (continuation.isActive) continuation.resume(Unit) },
                onFailed = { if (continuation.isActive) continuation.resumeWithException(it) },
            )
            val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
            if (result == TextToSpeech.ERROR) {
                speechCallbacks.remove(id)
                if (continuation.isActive) continuation.resumeWithException(IllegalStateException("TTS request failed"))
            }
        }
        continuation.invokeOnCancellation {
            speechCallbacks.remove(id)
            mainHandler.post { tts?.stop() }
        }
    }

    fun stopSpeaking() {
        val interrupted = speechCallbacks.entries.toList()
        speechCallbacks.clear()
        tts?.stop()
        interrupted.forEach { it.value.onInterrupted() }
    }

    fun close() {
        stopSpeaking()
        recognizer?.destroy(); recognizer = null
        tts?.shutdown(); tts = null
    }

    private fun readyCue() {
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java).defaultVibrator
            } else @Suppress("DEPRECATION") (context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) vibrator.vibrate(VibrationEffect.createOneShot(22, 75))
            else @Suppress("DEPRECATION") vibrator.vibrate(22)
        }
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 16)
            tone.startTone(ToneGenerator.TONE_PROP_ACK, 45)
            mainHandler.postDelayed({ tone.release() }, 120)
        }
    }
}
