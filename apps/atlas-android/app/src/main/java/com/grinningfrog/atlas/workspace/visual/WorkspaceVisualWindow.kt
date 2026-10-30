package com.grinningfrog.atlas.workspace.visual

import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.*
import androidx.savedstate.*
import com.grinningfrog.atlas.AtlasTheme
import com.grinningfrog.atlas.workspace.application.*
import kotlinx.coroutines.*
import org.json.JSONObject
import kotlin.math.abs

/** A visible, hardware-composited app window on a PRIVATE display, never the user's display. */
internal class WorkspaceVisualWindow(private val context: Context, val profile: JSONObject, private val title: String, private val revision: String, private val bundle: JSONObject) : java.io.Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var window: TestPresentation? = null
    private var compose: ComposeView? = null
    private var storage: WorkspaceTestStorage? = null
    private var current by mutableStateOf<WorkspaceWebPresentation?>(null)
    private var ready = CompletableDeferred<Unit>()
    private var closed = false
    private var paused = false
    private var failure: String? = null
    private val width = profile.getInt("render_width_px")
    private val height = profile.getInt("render_height_px")
    private val dpi = profile.getInt("density_dpi")
    val surface get() = current ?: error("VISUAL_INSTANCE_CLOSED")
    val displayId get() = display?.display?.displayId ?: -1

    suspend fun start() {
        check(Looper.myLooper() == Looper.getMainLooper())
        WorkspaceVisualLimits.dimensions(width, height, dpi)
        try {
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3).also {
                // Consumer must drain continuously; otherwise the compositor eventually stalls.
                it.setOnImageAvailableListener({ source -> runCatching { source.acquireLatestImage()?.close() } }, Handler(Looper.getMainLooper()))
            }
            val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
            display = (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
                .createVirtualDisplay("Atlas isolated workspace test", width, height, dpi, reader!!.surface, flags)
            check(display != null && displayId != android.view.Display.DEFAULT_DISPLAY) { "PRIVATE_DISPLAY_UNAVAILABLE" }
            window = TestPresentation(context, display!!.display).also { presentation ->
                presentation.window!!.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
                compose = ComposeView(presentation.context).also { view ->
                    view.setViewTreeLifecycleOwner(presentation)
                    view.setViewTreeViewModelStoreOwner(presentation)
                    view.setViewTreeSavedStateRegistryOwner(presentation)
                    view.setContent {
                        AtlasTheme {
                            Surface {
                                Column(Modifier.fillMaxSize()) {
                                    WorkspaceToolbar(title, "rev ${revision.take(8)}") {
                                        for (icon in listOf(Icons.Default.Delete, Icons.Default.Archive, Icons.Default.IosShare)) IconButton(onClick = {}) { Icon(icon, null) }
                                    }
                                    HorizontalDivider()
                                    WorkspaceRevisionLabel(revision)
                                    if(WorkspaceCapabilityRegistry.requested(bundle.getJSONObject("manifest")).isNotEmpty()) WorkspaceCapabilityBar("BLE · simulated bench device", true) {}
                                    current?.let { app ->
                                        val cssHeight = profile.optInt("alternative_height_css", 0)
                                        val modifier = if(cssHeight > 0) Modifier.fillMaxWidth().height(cssHeight.dp) else Modifier.fillMaxWidth().weight(1f)
                                        key(app) { AndroidView(factory = { app.view }, modifier = modifier) }
                                    }
                                }
                            }
                        }
                    }
                    presentation.setContentView(view)
                }
                presentation.show()
                presentation.window!!.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                presentation.resume()
            }
            val calibration = WorkspaceApplicationContract.example()
            calibration.getJSONObject("files").put("ui/style.css", "html,body{margin:0;width:100%;height:100%;background:#12ab34}main{display:none}")
            load(calibration)
            surface.awaitCompositedFrame()
            val image = surface.captureVisiblePixels(window!!.window)
            try {
                val expected = Color.rgb(18,171,52)
                for (point in listOf(.25f,.5f,.75f)) {
                    val pixel = image.getPixel((image.width * point).toInt(), (image.height * point).toInt())
                    check(abs(Color.red(pixel)-Color.red(expected))<=8 && abs(Color.green(pixel)-Color.green(expected))<=8 && abs(Color.blue(pixel)-Color.blue(expected))<=8) { "COMPOSITOR_CALIBRATION_FAILED: captured pixels do not match the private display" }
                }
            } finally { image.recycle() }
            disposeDocument()
            load(bundle)
            surface.awaitCompositedFrame()
        } catch (error: Throwable) { close(); throw error }
    }
    private suspend fun load(packageJson: JSONObject) {
        storage = withContext(Dispatchers.IO) { WorkspaceTestStorage(context, packageJson) }
        ready = CompletableDeferred(); failure = null
        val principal = storage!!.principal()
        val requested = WorkspaceCapabilityRegistry.requested(packageJson.getJSONObject("manifest"))[WorkspaceBleContract.CAPABILITY].orEmpty()
        val broker = if(requested.isEmpty()) null else WorkspaceDeviceBroker(principal,
            listOf(WorkspaceBleCapability(requested, WorkspaceUiSimulation.Radio(), simulated = true)),
            { !closed }, { true }, { true }, scope, isSimulation = true)
        current = WorkspaceWebPresentation(window!!.context, packageJson, principal, { !closed }, { ready.complete(Unit) }, {
            failure = it; ready.completeExceptionally(IllegalStateException(it))
        }, data = storage!!.data, devices = broker)
        withTimeout(5_000) { while(!surface.view.isAttachedToWindow || surface.view.width <= 0 || surface.view.height <= 0) delay(16) }
        surface.loadAttached()
        withTimeout(8_000) { ready.await() }
    }
    private fun disposeDocument() { current?.dispose(); current = null; storage?.close(); storage = null }
    suspend fun resumeRendering() {
        check(!closed); failure?.let { error("VISUAL_RENDERER_FAILED: $it") }
        if(paused) { display!!.surface = reader!!.surface; surface.view.resumeTimers(); surface.view.onResume(); paused = false }
    }
    fun pauseRendering() {
        if(!closed && !paused) { surface.view.onPause(); surface.view.pauseTimers(); display?.surface = null; paused = true }
    }
    suspend fun capture(): Bitmap { resumeRendering(); surface.awaitCompositedFrame(); return surface.captureVisiblePixels(window!!.window) }
    suspend fun inspect(): JSONObject { resumeRendering(); return surface.inspectLayout().put("runtime", surface.diagnostics()).put("native_viewport_width_px", surface.view.width).put("native_viewport_height_px", surface.view.height).put("display_id", displayId) }
    suspend fun terminate() { current?.terminateRenderer() }
    override fun close() {
        if(closed)return
        closed = true
        runCatching { disposeDocument() }
        runCatching { compose?.disposeComposition() }; compose = null
        runCatching { window?.closeLifecycle(); window?.dismiss() }; window = null
        runCatching { display?.release() }; display = null
        runCatching { reader?.setOnImageAvailableListener(null, null); reader?.close() }; reader = null
        scope.cancel()
    }
    private class TestPresentation(context: Context, display: android.view.Display) : Presentation(context, display, android.R.style.Theme_Material_NoActionBar), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val state = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val viewModelStore = ViewModelStore()
        override val savedStateRegistry: SavedStateRegistry get() = state.savedStateRegistry
        init { state.performAttach(); state.performRestore(null); registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE) }
        fun resume() { registry.handleLifecycleEvent(Lifecycle.Event.ON_START); registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME) }
        fun closeLifecycle() { registry.currentState = Lifecycle.State.DESTROYED; viewModelStore.clear() }
    }
}
