package com.grinningfrog.atlas.workspace.application

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.UUID
import kotlin.coroutines.resume

/** Fresh isolated instance per invocation; no DB transactions or session inference ownership here. */
class WorkspaceWorkerSupervisor(context: Context) {
    private val context = context.applicationContext
    suspend fun run(bundle: JSONObject, exportName: String, input: Any?): JSONObject = withContext(Dispatchers.Main.immediate) {
        if (Build.VERSION.SDK_INT < 29) error("UNSUPPORTED_CAPABILITY: isolated JavaScript workers require Android 10")
        val errors = WorkspaceApplicationContract.validate(bundle)
        require(errors.isEmpty()) { "VALIDATION_FAILED: ${errors.joinToString("; ")}" }
        val serialized = bundle.toString()
        val inputJson = WorkspaceApplicationContract.canonical(input)
        require(serialized.toByteArray(Charsets.UTF_8).size + inputJson.toByteArray(Charsets.UTF_8).size <= 256 * 1024) { "QUOTA_EXCEEDED: isolated-worker payload" }
        val export = bundle.getJSONObject("manifest").getJSONObject("exports").getJSONObject(exportName)
        WorkspaceJsonSchema.validate(export.getJSONObject("input_schema"), input)
        val token = UUID.randomUUID().toString()
        var remote: Messenger? = null
        var connection: ServiceConnection? = null
        var bound = false
        try {
            withTimeout(5000) {
                suspendCancellableCoroutine { continuation ->
                    val responses = Messenger(Handler(Looper.getMainLooper()) { message ->
                        if (message.what == 1 && message.data.getString("token") == token && continuation.isActive) {
                            val raw = message.data.getString("result").orEmpty()
                            val result = runCatching {
                                val response = WorkspaceApplicationContract.parseObject(raw)
                                if (response.optBoolean("ok")) WorkspaceJsonSchema.validate(export.getJSONObject("output_schema"), response.get("value"))
                                response
                            }.getOrElse { JSONObject().put("ok", false).put("code", "OUTPUT_INVALID").put("summary", "Worker output failed host validation") }
                            continuation.resume(result)
                        }
                        true
                    })
                    val callback = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                            if (!continuation.isActive) return
                            val messenger = Messenger(binder); remote = messenger
                            val request = Message.obtain(null, 1).apply {
                                replyTo = responses
                                data = Bundle().apply { putString("token", token); putString("bundle", serialized); putString("export", exportName); putString("input", inputJson) }
                            }
                            runCatching { messenger.send(request) }.onFailure {
                                if (continuation.isActive) continuation.resume(JSONObject().put("ok", false).put("code", "WORKER_UNAVAILABLE").put("summary", "Worker request failed"))
                            }
                        }
                        override fun onServiceDisconnected(name: ComponentName) {
                            if (continuation.isActive) continuation.resume(JSONObject().put("ok", false).put("code", "WORKER_TERMINATED").put("summary", "Worker died before returning a result"))
                        }
                        override fun onNullBinding(name: ComponentName) = onServiceDisconnected(name)
                        override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)
                    }
                    connection = callback
                    bound = context.bindIsolatedService(Intent(context, WorkspaceJavaScriptService::class.java), Context.BIND_AUTO_CREATE,
                        "workspace-${token}", context.mainExecutor, callback)
                    if (!bound && continuation.isActive) continuation.resume(JSONObject().put("ok", false).put("code", "WORKER_UNAVAILABLE").put("summary", "Worker binding was denied"))
                }
            }
        } finally {
            // External control channel cannot be reached from generated JavaScript.
            runCatching { remote?.send(Message.obtain(null, 2)) }
            if (bound) connection?.let { runCatching { context.unbindService(it) } }
        }
    }
}
