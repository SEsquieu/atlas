package com.grinningfrog.atlas.workspace.application

import android.app.Service
import android.content.Intent
import android.os.*
import android.os.Process
import org.json.JSONObject
import java.util.concurrent.Executors

/** Android assigns an isolated UID without the host's Android permissions or private files. */
class WorkspaceJavaScriptService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var messages: Messenger
    private lateinit var control: Handler
    private var busy = false
    override fun onCreate() {
        super.onCreate()
        control = Handler(Looper.getMainLooper()) { message ->
            if (message.sendingUid != applicationInfo.uid) return@Handler true
            when (message.what) {
                2 -> Process.killProcess(Process.myPid())
                1 -> {
                    val reply = message.replyTo ?: return@Handler true
                    if (busy) return@Handler true
                    busy = true
                    val args = message.data
                    val token = args.getString("token") ?: return@Handler true
                    // Independent wall-clock kill. It still works if native evaluation ignores its deadline.
                    control.postDelayed({ Process.killProcess(Process.myPid()) }, 4000)
                    worker.execute {
                        val result = runCatching {
                            val descriptor = args.getParcelable<ParcelFileDescriptor>("bundle_fd") ?: error("Missing package descriptor")
                            val bytes = ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
                                val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                                while (true) {
                                    val size = stream.read(buffer); if (size < 0) break
                                    require(output.size() + size <= WorkspaceApplicationContract.MAX_PACKAGE_BYTES) { "QUOTA_EXCEEDED" }
                                    output.write(buffer, 0, size)
                                }
                                output.toByteArray()
                            }
                            val bundle = WorkspaceApplicationContract.parseObject(bytes.toString(Charsets.UTF_8), WorkspaceApplicationContract.MAX_PACKAGE_BYTES)
                            WorkspaceNativeEngine.run(bundle, args.getString("export") ?: error("Missing export"), WorkspaceNativeEngine.input(args.getString("input") ?: "null"))
                        }.getOrElse { JSONObject().put("ok", false).put("code", "EXECUTION_FAILED").put("summary", (it.message ?: "Worker failed").take(1000)) }
                        val response = Message.obtain(null, 1).apply { data = Bundle().apply { putString("token", token); putString("result", result.toString()) } }
                        runCatching { reply.send(response) }
                    }
                }
            }
            true
        }
        messages = Messenger(control)
    }
    override fun onBind(intent: Intent): IBinder = messages.binder
    override fun onDestroy() { worker.shutdownNow(); super.onDestroy() }
}
