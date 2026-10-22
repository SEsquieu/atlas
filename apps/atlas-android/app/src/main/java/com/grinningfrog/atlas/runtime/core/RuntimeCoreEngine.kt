package com.grinningfrog.atlas.runtime.core

import android.content.Context
import android.os.SystemClock
import androidx.javascriptengine.JavaScriptSandbox
import androidx.javascriptengine.IsolateStartupParameters
import androidx.javascriptengine.JavaScriptIsolate
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One warm sandbox per host. No token-level bridge, network, or disk operations in JavaScript. */
class RuntimeCoreEngine(private val context: Context) {
    private val gate = Mutex()
    private val services = java.util.concurrent.atomic.AtomicInteger(0)
    fun retain() { services.incrementAndGet() }
    suspend fun release() {
        check(services.decrementAndGet() >= 0) { "Unbalanced runtime service ownership" }
        gate.withLock { if (services.get() == 0) closeLocked() }
    }
    private var sandbox: JavaScriptSandbox? = null
    private var isolate: JavaScriptIsolate? = null
    private var loaded: String? = null
    suspend fun decide(bundle: RuntimeBundle, step: Int, elapsedMs: Long): CoreDecision = gate.withLock {
        withContext(Dispatchers.IO) {
            val start = SystemClock.elapsedRealtimeNanos()
            val cold = loaded != bundle.id
            try {
                withTimeout(if (cold) 5_000L else 1_000L) {
                    if (cold) {
                        isolate?.close(); isolate = null; loaded = null
                        if (sandbox == null) {
                            check(JavaScriptSandbox.isSupported()) { "JavaScript runtime unavailable on this device; select Native runtime" }
                            sandbox = JavaScriptSandbox.createConnectedInstanceAsync(context).awaitCore()
                        }
                        // Termination support is required so runaway code cannot hold the engine indefinitely.
                        check(sandbox!!.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_ISOLATE_TERMINATION)) { "Runtime termination unsupported" }
                        check(sandbox!!.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_ISOLATE_MAX_HEAP_SIZE)) { "Runtime heap limits unsupported" }
                        isolate = sandbox!!.createIsolate(IsolateStartupParameters().apply { maxHeapSizeBytes = 16L * 1024 * 1024 })
                        isolate!!.evaluateJavaScriptAsync(bundle.source + "\n; 'loaded';").awaitCore()
                        loaded = bundle.id
                    }
                    val requestId = UUID.randomUUID().toString()
                    val event = JSONObject().put("bridge", 1).put("request_id", requestId).put("type", "before_inference")
                        .put("snapshot", JSONObject().put("step", step).put("elapsed_ms", elapsedMs))
                    // JSONObject.quote ensures event contents are data, never interpolated executable fragments.
                    val expression = "JSON.stringify(globalThis.atlasRuntime.reduce(JSON.parse(" + JSONObject.quote(event.toString()) + ")))"
                    val raw = isolate!!.evaluateJavaScriptAsync(expression).awaitCore()
                    CoreDecision.parse(raw, requestId).copy(elapsedUs = (SystemClock.elapsedRealtimeNanos() - start) / 1000, cold = cold)
                }
            } catch (e: Exception) {
                isolate?.close(); isolate = null; loaded = null
                // Closing the sandbox also terminates any orphaned evaluation after cancellation or timeout.
                sandbox?.close(); sandbox = null
                if (e is TimeoutCancellationException) {
                    currentCoroutineContext().ensureActive()
                    throw IllegalStateException("Agent runtime exceeded its deadline. Cancel this task and select Native for a new task.", e)
                }
                throw e
            }
        }
    }
    /** Probes get a separate isolate; candidate code cannot alter the active task's JS globals. */
    suspend fun qualify(bundle: RuntimeBundle): JSONObject = gate.withLock {
        withContext(Dispatchers.IO) {
            var probe: JavaScriptIsolate? = null
            try {
                withTimeout(5_000L) {
                    if (sandbox == null) {
                        check(JavaScriptSandbox.isSupported()) { "JavaScript runtime unavailable on this device" }
                        sandbox = JavaScriptSandbox.createConnectedInstanceAsync(context).awaitCore()
                    }
                    check(sandbox!!.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_ISOLATE_TERMINATION) &&
                        sandbox!!.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_ISOLATE_MAX_HEAP_SIZE)) { "Runtime isolation limits unsupported" }
                    probe = sandbox!!.createIsolate(IsolateStartupParameters().apply { maxHeapSizeBytes = 16L * 1024 * 1024 })
                    probe!!.evaluateJavaScriptAsync(bundle.source + "\n;'loaded';").awaitCore()
                    val fixtures = listOf(0 to 0L, 7 to 0L, 8 to 0L, 0 to 180_000L)
                    val reports = org.json.JSONArray()
                    for ((step, elapsed) in fixtures) {
                        val id = UUID.randomUUID().toString()
                        val event = JSONObject().put("bridge", 1).put("request_id", id).put("type", "before_inference")
                            .put("snapshot", JSONObject().put("step", step).put("elapsed_ms", elapsed))
                        val expression = "JSON.stringify(globalThis.atlasRuntime.reduce(JSON.parse(" + JSONObject.quote(event.toString()) + ")))"
                        val decision = CoreDecision.parse(probe!!.evaluateJavaScriptAsync(expression).awaitCore(), id)
                        require((step != 0 || elapsed != 0L || decision.action == "infer") &&
                            ((step < 8 && elapsed < 180_000L) || decision.action == "yield")) { "Runtime violates empty-slice or host-ceiling contract" }
                        reports.put(JSONObject().put("step", step).put("elapsed_ms", elapsed).put("action", decision.action))
                    }
                    JSONObject().put("passed", true).put("runtime_id", bundle.id).put("verification_version", 1).put("probes", reports)
                        .put("evidence_scope", "Four isolated bridge/ceiling probes only; not general behavior, task correctness or semantic equivalence")
                }
            } catch (e: Exception) {
                // A failed proposal never changes selection or task pins; reset a possibly poisoned connection.
                probe?.close(); probe = null; closeLocked()
                if (e is TimeoutCancellationException) {
                    currentCoroutineContext().ensureActive()
                    throw IllegalStateException("Runtime proposal qualification timed out; selection unchanged", e)
                }
                throw e
            } finally { probe?.close() }
        }
    }
    suspend fun close() = gate.withLock { closeLocked() }
    private fun closeLocked() {
        isolate?.close(); isolate = null; sandbox?.close(); sandbox = null; loaded = null
    }
}

data class CoreDecision(val action: String, val guidance: String, val elapsedUs: Long = 0, val cold: Boolean = false) {
    companion object {
        fun parse(raw: String, requestId: String): CoreDecision {
            require(raw.toByteArray(Charsets.UTF_8).size <= 32_768) { "Oversized runtime response" }
            val result = boundedRuntimeObject(raw, 32_768)
            require(result.keys().asSequence().toSet() == setOf("bridge", "request_id", "action", "guidance")) { "Unknown runtime command fields" }
            require(result.get("bridge") == 1 && result.getString("request_id") == requestId) { "Stale or incompatible runtime response" }
            require(listOf("request_id", "action", "guidance").all { result.get(it) is String }) { "Runtime command fields must be strings" }
            val action = result.getString("action")
            require(action in setOf("infer", "yield")) { "Unauthorized runtime command" }
            val guidance = result.getString("guidance")
            require(guidance.length <= 16_384) { "Oversized runtime guidance" }
            return CoreDecision(action, guidance)
        }
    }
}

private suspend fun <T> ListenableFuture<T>.awaitCore(): T = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel(true) }
    addListener({
        if (continuation.isActive) {
            try { continuation.resume(get()) }
            catch (e: Exception) { continuation.resumeWithException(e.cause ?: e) }
        }
    }, Executor { it.run() })
}
