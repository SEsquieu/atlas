package com.grinningfrog.atlas.device

import android.content.Context
import android.content.Intent
import android.os.Bundle
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

class SpeechController(private val context: Context) {
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private val speechContinuations = ConcurrentHashMap<String, kotlin.coroutines.Continuation<Unit>>()

    suspend fun start() = withContext(Dispatchers.Main.immediate) {
        if (SpeechRecognizer.isRecognitionAvailable(context) && recognizer == null) recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        if (tts == null) tts = suspendCancellableCoroutine { continuation ->
            lateinit var engine: TextToSpeech
            engine = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    engine.language = Locale.getDefault()
                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = Unit
                        override fun onDone(utteranceId: String?) { utteranceId?.let { speechContinuations.remove(it)?.resume(Unit) } }
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) { utteranceId?.let { speechContinuations.remove(it)?.resumeWithException(IllegalStateException("TTS failed")) } }
                        override fun onError(utteranceId: String?, errorCode: Int) { utteranceId?.let { speechContinuations.remove(it)?.resumeWithException(IllegalStateException("TTS failed: $errorCode")) } }
                    })
                    continuation.resume(engine)
                } else continuation.resumeWithException(IllegalStateException("TTS initialization failed: $status"))
            }
            continuation.invokeOnCancellation { engine.shutdown() }
        }
    }

    suspend fun listenOnce(language: String = Locale.getDefault().toLanguageTag()): String = withContext(Dispatchers.Main.immediate) {
        val engine = recognizer ?: throw IllegalStateException("Speech recognition is unavailable")
        suspendCancellableCoroutine { continuation ->
            engine.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle) {
                    val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
                    if (text.isNullOrBlank()) continuation.resumeWithException(IllegalStateException("No speech recognized")) else continuation.resume(text)
                }
                override fun onError(error: Int) = continuation.resumeWithException(IllegalStateException("Speech recognition failed: $error"))
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            engine.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            })
            continuation.invokeOnCancellation { engine.cancel() }
        }
    }

    suspend fun speak(text: String) = withContext(Dispatchers.Main.immediate) {
        val engine = tts ?: throw IllegalStateException("TTS is unavailable")
        val utteranceId = UUID.randomUUID().toString()
        suspendCancellableCoroutine { continuation ->
            speechContinuations[utteranceId] = continuation
            val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            if (result == TextToSpeech.ERROR) {
                speechContinuations.remove(utteranceId)
                continuation.resumeWithException(IllegalStateException("TTS request failed"))
            }
            continuation.invokeOnCancellation { engine.stop(); speechContinuations.remove(utteranceId) }
        }
    }

    fun stopSpeaking() { tts?.stop() }
    fun close() { recognizer?.destroy(); recognizer = null; tts?.shutdown(); tts = null }
}
