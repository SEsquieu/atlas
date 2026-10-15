package com.grinningfrog.atlas.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Bounded evidence independent of SQLite, so a database lock cannot prevent stall capture. */
object RuntimeDiagnostics {
    private var installed = false
    @Synchronized fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val prior = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
            runCatching { record(app, JSONObject().put("kind", "uncaught_exception").put("thread", thread.name)
                .put("stack", failure.stackTraceToString().take(12_000))) }
            if (prior != null) prior.uncaughtException(thread, failure)
            else { android.os.Process.killProcess(android.os.Process.myPid()); kotlin.system.exitProcess(10) }
        }
        val handler = Handler(Looper.getMainLooper())
        val pending = AtomicLong(0)
        val executor = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "atlas-stall-recorder").apply { isDaemon = true } }
        var recorded = false
        executor.scheduleWithFixedDelay({
            runCatching {
                val now = SystemClock.uptimeMillis()
                val sent = pending.get()
                if (sent == 0L) {
                    pending.set(now)
                    handler.post { pending.set(0) }
                    recorded = false
                } else if (!recorded && now - sent >= 5_000) {
                    recorded = true
                    record(app, JSONObject().put("kind", "main_thread_stall").put("unresponsive_ms", now - sent)
                        .put("main_stack", Looper.getMainLooper().thread.stackTrace.take(64).joinToString("\n")))
                }
            }
        }, 2, 2, TimeUnit.SECONDS)
    }

    @Synchronized internal fun record(context: Context, event: JSONObject) {
        val file = File(context.filesDir, "runtime-diagnostics.json")
        val prior = runCatching { JSONArray(file.readText()) }.getOrDefault(JSONArray())
        val next = JSONArray()
        for (i in maxOf(0, prior.length() - 7) until prior.length()) next.put(prior.getJSONObject(i))
        next.put(event.put("at_ms", System.currentTimeMillis()))
        while (next.toString().toByteArray(Charsets.UTF_8).size > 96 * 1024 && next.length() > 0) next.remove(0)
        val atomic = android.util.AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(next.toString().toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
        catch (failure: Throwable) { atomic.failWrite(stream); throw failure }
    }

    fun snapshot(context: Context): JSONObject = JSONObject().apply {
        put("scope", "Process-wide recent evidence; timestamps may predate this session. Stalls do not by themselves establish a crash cause.")
        put("events", runCatching { JSONArray(File(context.filesDir, "runtime-diagnostics.json").readText()) }.getOrDefault(JSONArray()))
        val exits = JSONArray()
        if (Build.VERSION.SDK_INT >= 30) runCatching {
            context.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(context.packageName, 0, 5).forEach { exit ->
                exits.put(JSONObject().put("at_ms", exit.timestamp).put("process", exit.processName).put("reason", exit.reason)
                    .put("status", exit.status).put("description", exit.description?.take(500)).put("pss_kb", exit.pss).put("rss_kb", exit.rss))
            }
        }.onFailure { put("exit_history_error", it.javaClass.simpleName) }
        put("android_process_exits", exits)
    }
}
