package com.grinningfrog.atlas.workspace.visual

import android.app.KeyguardManager
import android.content.Context
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject
import java.lang.ref.WeakReference

/** Measures the actual Scaffold content, already outside Atlas controls and system/IME insets. */
internal object WorkspaceViewport {
    private var root = WeakReference<View>(null)
    private var bounds = intArrayOf(0, 0, 0, 0)
    private var workspaceChromeAvailable = false
    fun measured(view: View, x: Int, y: Int, width: Int, height: Int, workspaceChromeAvailable: Boolean = true) {
        root = WeakReference(view); bounds = intArrayOf(x, y, width, height); this.workspaceChromeAvailable = workspaceChromeAvailable
    }
    fun snapshot(): JSONObject {
        val view = root.get() ?: error("VIEWPORT_UNAVAILABLE: Atlas must be visible")
        check(workspaceChromeAvailable) {"VIEWPORT_UNAVAILABLE: use Session or Workspace; System has different native controls"}
        check(view.isAttachedToWindow && view.isShown && view.hasWindowFocus()) { "VIEWPORT_UNAVAILABLE: Atlas must have a foreground window" }
        check(!(view.context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked) { "DEVICE_LOCKED" }
        val dm = view.resources.displayMetrics
        val insets = ViewCompat.getRootWindowInsets(view)
        fun inset(type: Int) = (insets?.getInsets(type) ?: androidx.core.graphics.Insets.NONE).let {
            JSONObject().put("left", it.left).put("top", it.top).put("right", it.right).put("bottom", it.bottom)
        }
        val b = bounds.copyOf(); require(b[2] > 0 && b[3] > 0)
        return JSONObject().put("measured_at_ms", System.currentTimeMillis()).put("source", "actual_atlas_scaffold_content")
            .put("body_x_px", b[0]).put("body_y_px", b[1]).put("body_width_px", b[2]).put("body_height_px", b[3])
            .put("window_width_px", view.width).put("window_height_px", view.height)
            .put("physical_mode_width_px", view.display.mode.physicalWidth).put("physical_mode_height_px", view.display.mode.physicalHeight)
            .put("density_dpi", dm.densityDpi).put("density", dm.density.toDouble()).put("font_scale", view.resources.configuration.fontScale.toDouble())
            .put("orientation", view.resources.configuration.orientation).put("display_id", view.display.displayId)
            .put("system_insets_px", inset(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()))
            .put("ime_insets_px", inset(WindowInsetsCompat.Type.ime())).put("ime_visible", insets?.isVisible(WindowInsetsCompat.Type.ime()) == true)
            .put("touchscreen", view.context.packageManager.hasSystemFeature("android.hardware.touchscreen"))
            .put("multitouch", view.context.packageManager.hasSystemFeature("android.hardware.touchscreen.multitouch"))
    }
}

/** Host-owned budgets, independent of model/provider. No guessed phone viewport. */
internal object WorkspaceVisualLimits {
    const val MAX_PIXELS = 4_000_000L
    const val IDLE_MS = 300_000L
    const val LIFETIME_MS = 1_200_000L
    const val MAX_OPERATIONS = 128
    fun dimensions(width: Int, height: Int, dpi: Int) {
        require(width in 64..4096 && height in 64..4096 && width.toLong() * height <= MAX_PIXELS) { "VISUAL_VIEWPORT_BUDGET_EXCEEDED" }
        require(dpi in 72..960)
    }
    fun owns(owner: String, supplied: String) { require(owner == supplied) { "VISUAL_OWNER_CONFLICT" } }
}
