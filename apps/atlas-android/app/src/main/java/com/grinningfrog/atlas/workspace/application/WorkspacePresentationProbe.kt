package com.grinningfrog.atlas.workspace.application

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.coroutines.resume

/** Startup handshake only; not visual verification or proof of browser containment. */
internal object WorkspacePresentationProbe {
    suspend fun ready(context: Context, bundle: JSONObject, principal: WorkspacePrincipal): Boolean = withContext(Dispatchers.Main.immediate) {
        require(principal.mode == WorkspaceInstanceMode.PREVIEW) { "EXECUTION_MODE_DENIED" }
        var presentation: WorkspaceWebPresentation? = null
        val current = java.util.concurrent.atomic.AtomicBoolean(true)
        try {
            val result = withTimeoutOrNull(8_000L) {
                suspendCancellableCoroutine { continuation ->
                    presentation = WorkspaceWebPresentation(context, bundle, principal, { current.get() },
                        onReady = { if (continuation.isActive) continuation.resume(true) },
                        onFailure = { if (continuation.isActive) continuation.resume(false) })
                    presentation!!.load()
                }
            }
            if (result != true) error("STARTUP_FAILED: ${presentation?.diagnostics()?.toString()?.take(1500)}")
            true
        } finally {
            current.set(false)
            presentation?.dispose()
        }
    }
}
