package com.grinningfrog.atlas.workspace

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/** Only the currently composed Atlas workspace panel can register a capture source. No system screen access. */
object WorkspaceRenderSurface {
    private data class Host(val token: Any, val workspaceId: String, val revisionId: String, val capture: suspend () -> Bitmap, var drawn: JSONObject? = null)
    private var host: Host? = null

    @Synchronized
    fun register(token: Any, workspaceId: String, revisionId: String, capture: suspend () -> Bitmap) {
        host = Host(token, workspaceId, revisionId, capture)
    }
    @Synchronized
    fun unregister(token: Any) { if (host?.token === token) host = null }

    @Synchronized
    fun drawn(token: Any, data: JSONObject): Boolean {
        val current = host?.takeIf { it.token === token } ?: return false
        val changed = current.drawn?.optJSONObject("projection")?.toString() != data.optJSONObject("projection")?.toString() ||
            current.drawn?.optString("error") != data.optString("error")
        current.drawn = JSONObject(data.toString())
        return changed
    }

    @Synchronized
    fun diagnostics(workspaceId: String, revisionId: String): JSONObject? = host
        ?.takeIf { it.workspaceId == workspaceId && it.revisionId == revisionId }
        ?.drawn?.let { JSONObject(it.toString()) }

    private fun frameKey(data: JSONObject): String = JSONObject(data.toString()).apply { remove("drawn_at_ms") }.toString()

    suspend fun capture(workspaceId: String, revisionId: String): Pair<Bitmap, JSONObject> = withTimeout(5_000) {
        withContext(Dispatchers.Main.immediate) {
            val current = synchronized(this@WorkspaceRenderSurface) {
                host?.takeIf { it.workspaceId == workspaceId && it.revisionId == revisionId && it.drawn != null }
            } ?: error("Workspace panel is not visible or has not drawn this revision. Open it before capturing.")
            val evidence = JSONObject(current.drawn!!.toString())
            val bitmap = current.capture()
            val same = synchronized(this@WorkspaceRenderSurface) { host === current && current.drawn?.let { frameKey(it) } == frameKey(evidence) }
            if (!same) { bitmap.recycle(); error("Workspace changed during capture; retry") }
            bitmap to evidence
        }
    }
}
