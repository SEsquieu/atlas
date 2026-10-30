package com.grinningfrog.atlas.workspace.application

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.webkit.*
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.WebViewRenderProcess
import androidx.webkit.WebViewRenderProcessClient
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.UUID
import kotlinx.coroutines.*

/** Contained package presentation with optional Core-bound storage and foreground device brokers. */
class WorkspaceWebPresentation(
    context: Context,
    bundle: JSONObject,
    private val principal: WorkspacePrincipal,
    private val current: () -> Boolean,
    private val onReady: () -> Unit,
    private val onFailure: (String) -> Unit,
    private val data: WorkspaceApplicationData? = null,
    private val devices: WorkspaceDeviceBroker? = null,
    private val testRandomValues: List<Double>? = null,
) {
    private val files = JSONObject(bundle.getJSONObject("files").toString())
    private val policy = WorkspacePresentationPolicy(principal, bundle.getJSONObject("manifest").getString("entry"))
    @Volatile private var disposed = false
    private val rendererGone = CompletableDeferred<Unit>()
    private var remainingMessages = 64
    private var messageWindow = android.os.SystemClock.elapsedRealtime()
    private val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val runtimeErrorCount = java.util.concurrent.atomic.AtomicInteger()
    private val resourceErrorCount = java.util.concurrent.atomic.AtomicInteger()
    private val console = java.util.Collections.synchronizedList(mutableListOf<JSONObject>())
    @Volatile private var hostFailureCount=0
    private val hostFailures=mutableListOf<JSONObject>()
    private val hostCalls = java.util.Collections.synchronizedList(mutableListOf<JSONObject>())
    private fun hostAudit(method: String, status: String, code: String? = null, message: String? = null) { synchronized(hostCalls) { if(hostCalls.size>=64)hostCalls.removeAt(0);val event=JSONObject().put("method",method).put("status",status).put("error_code",code).put("message",message?.take(500));hostCalls.add(event);if(status=="failed") {hostFailureCount++;if(hostFailures.size>=16)hostFailures.removeAt(0);hostFailures.add(event)} } }
    private val resources = java.util.Collections.synchronizedList(mutableListOf<JSONObject>())
    private val provider = WebViewCompat.getCurrentWebViewPackage(context)?.let { "${it.packageName}/${it.versionName}" } ?: "unknown"
    @Volatile private var ready = false
    fun diagnostics(): JSONObject = JSONObject().put("instance_mode",principal.mode.name.lowercase()).put("simulation",devices?.isSimulation==true).put("webview_provider", provider).put("android_api", android.os.Build.VERSION.SDK_INT).put("runtime_error_count", runtimeErrorCount.get()).put("resource_error_count", resourceErrorCount.get()).put("native_canvas_fallback", "#010302").put("devices", devices?.status() ?: JSONObject.NULL).put("ready", ready).put("disposed", disposed).put("resources", org.json.JSONArray(synchronized(resources) { resources.map { JSONObject(it.toString()) } })).put("console", org.json.JSONArray(synchronized(console) { console.map { JSONObject(it.toString()) } })).put("host_failure_count",hostFailureCount).put("host_failures",org.json.JSONArray(synchronized(hostCalls) {hostFailures.map {JSONObject(it.toString())}})).put("host_calls",org.json.JSONArray(synchronized(hostCalls) {hostCalls.map {JSONObject(it.toString())}}))
    val view: WebView

    init {
        require(testRandomValues==null || principal.mode==WorkspaceInstanceMode.TEST) { "Random overrides are test-only" }
        require(WorkspaceApplicationContract.validate(bundle).isEmpty()) { "Invalid package" }
        // Unsupported providers fail closed. Single-process rendering cannot run authored scripts.
        require(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) { "Browser capability guards are unavailable" }
        require(WebViewFeature.isFeatureSupported(WebViewFeature.MUTE_AUDIO)) { "Browser audio containment is unavailable" }
        require(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) { "Message-origin checks are unavailable" }
        require(WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROCESS) && WebViewCompat.isMultiProcessEnabled()) { "Isolated WebView rendering is unavailable" }
        require(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_VIEW_RENDERER_CLIENT_BASIC_USAGE)) { "Renderer monitoring is unavailable" }
        require(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_VIEW_RENDERER_TERMINATE)) { "Renderer termination is unavailable" }
        view = WebView(context)
        try { configure() } catch (failure: Throwable) { bridgeScope.cancel(); view.destroy(); throw failure }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure() {
        // Match the native Atlas surface while the document canvas is transparent or loading.
        // Authored opaque backgrounds still paint over this fallback.
        view.setBackgroundColor(android.graphics.Color.rgb(1, 3, 2))
        view.settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            blockNetworkLoads = true
            domStorageEnabled = false
            databaseEnabled = false
            cacheMode = WebSettings.LOAD_NO_CACHE
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            setGeolocationEnabled(false)
            mediaPlaybackRequiresUserGesture = true
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MUTE_AUDIO)) WebViewCompat.setAudioMuted(view, true)
        testRandomValues?.let { values ->
            require(values.isNotEmpty() && values.size<=32 && values.all {it.isFinite() && it>=0 && it<1})
            WebViewCompat.addDocumentStartJavaScript(view,"(()=>{const values=${org.json.JSONArray(values)};let i=0;Math.random=()=>values[(i++)%values.length];})();",setOf("*"))
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) WebViewCompat.addDocumentStartJavaScript(view, """
            (() => {
              addEventListener('error', event => console.error('Uncaught application error: '+event.message));
              addEventListener('unhandledrejection', event => console.error('Unhandled application rejection: '+String(event.reason)));
              for (const name of ['RTCPeerConnection','webkitRTCPeerConnection','mozRTCPeerConnection','AudioContext','webkitAudioContext','OfflineAudioContext','WebSocket','WebTransport','EventSource','Worker','SharedWorker','showOpenFilePicker','showSaveFilePicker','showDirectoryPicker','indexedDB','caches','openDatabase','localStorage','sessionStorage']) {
                Object.defineProperty(globalThis,name,{value:undefined,writable:false,configurable:false});
              }
              for (const name of ['clipboard','share','canShare','credentials','bluetooth','usb','serial','hid','contacts','wakeLock','serviceWorker','sendBeacon','mediaDevices','geolocation']) {
                Object.defineProperty(Navigator.prototype,name,{value:undefined,writable:false,configurable:false});
                Object.defineProperty(navigator,name,{value:undefined,writable:false,configurable:false});
              }
            })();
        """.trimIndent(), setOf("*"))
        view.importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        view.setOnDragListener { _, _ -> true }
        CookieManager.getInstance().setAcceptCookie(false)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
        ServiceWorkerController.getInstance().apply {
            serviceWorkerWebSettings.apply { blockNetworkLoads = true; allowFileAccess = false; allowContentAccess = false }
            setServiceWorkerClient(object : ServiceWorkerClient() { override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse = denied() })
        }
        view.setDownloadListener { _, _, _, _, _ -> onFailure("Direct downloads are unavailable") }
        view.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                if(message.messageLevel()==ConsoleMessage.MessageLevel.ERROR)runtimeErrorCount.incrementAndGet()
                synchronized(console) {
                    if (console.size >= 24) console.removeAt(0)
                    console.add(JSONObject().put("level", message.messageLevel().name).put("message", message.message().orEmpty().take(1000)).put("source", message.sourceId().orEmpty().take(240)).put("line", message.lineNumber()))
                }
                return true
            }
            override fun onPermissionRequest(request: PermissionRequest) = request.deny()
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) = callback.invoke(origin, false, false)
            override fun onShowFileChooser(webView: WebView, callback: android.webkit.ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean { callback.onReceiveValue(null); return true }
            override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean { result.cancel(); return true }
            override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean { result.cancel(); return true }
            override fun onJsPrompt(view: WebView, url: String, message: String, defaultValue: String, result: JsPromptResult): Boolean { result.cancel(); return true }
        }
        view.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                if (!policy.navigation(url)) { dispose(); onFailure("Workspace navigation escaped its package") }
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = !policy.navigation(request.url.toString())
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = !policy.navigation(url)
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse = serve(request.url.toString(), request.method)
            override fun shouldInterceptRequest(view: WebView, url: String): WebResourceResponse = serve(url, "GET")
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean { rendererGone.complete(Unit); dispose(); onFailure("Workspace renderer terminated"); return true }
        }
        WebViewCompat.setWebViewRenderProcessClient(view, object : WebViewRenderProcessClient() {
            override fun onRenderProcessUnresponsive(view: WebView, renderer: WebViewRenderProcess?) {
                if (renderer != null && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_VIEW_RENDERER_TERMINATE)) renderer.terminate()
                onFailure("Workspace renderer became unresponsive")
            }
            override fun onRenderProcessResponsive(view: WebView, renderer: WebViewRenderProcess?) = Unit
        })
        // The CSP sandbox gives the document an opaque origin. Android's main-frame flag,
        // this WebView's packaged-only navigation and the bound epoch supply the authority.
        // Subframes receive no dispatch authority even though their opaque origin is also "null".
        WebViewCompat.addWebMessageListener(view, "atlasTransport", setOf("*")) { _, message, source, mainFrame, reply ->
            if (!policy.acceptsMessage(source.toString(), mainFrame, !disposed && current())) return@addWebMessageListener
            val raw = runCatching { message.data }.getOrNull() ?: return@addWebMessageListener
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - messageWindow >= 1000) { messageWindow = now; remainingMessages = 64 }
            if (--remainingMessages < 0) { onFailure("Workspace bridge quota exceeded"); return@addWebMessageListener }
            bridgeScope.launch {
            val operation = UUID.randomUUID().toString()
            var requestId = "invalid"
            var requestedMethod="invalid"
            val response = runCatching {
                val request = WorkspaceHostProtocol.request(raw); requestId = request.getString("request_id")
                val method = request.getString("method");requestedMethod=method
                val params = request.getJSONObject("params")
                val result = if (devices?.handles(method) == true || method.startsWith("ble.")) {
                    require(!disposed && current()) { "REVISION_CONFLICT" }
                    require(principal.mode == WorkspaceInstanceMode.FOREGROUND || principal.mode == WorkspaceInstanceMode.TEST && devices?.isSimulation == true) { "EXECUTION_MODE_DENIED" }
                    requireNotNull(devices) { "UNSUPPORTED_CAPABILITY" }.dispatch(method, params)
                } else withContext(Dispatchers.IO) {
                    require(!disposed && current()) { "REVISION_CONFLICT" }
                    WorkspaceHostRegistry.dispatch(principal, method, params, data, capabilityBindings = devices?.capabilityNames.orEmpty()).also { if(devices?.isSimulation==true && method in setOf("host.describe","host.ready")) it.put("ble_available",true).put("simulation",true).apply { val methods=WorkspaceCapabilityRegistry.methods(devices.capabilityNames);for(name in methods.keys())getJSONObject("methods").put(name,methods.getJSONObject(name)) } }
                }
                if (method == "host.ready" && !disposed && current()) { ready = true; onReady() }
                hostAudit(method,"succeeded")
                val response = WorkspaceHostProtocol.response(requestId, "succeeded", operation, result)
                require(response.toString().toByteArray(Charsets.UTF_8).size <= WorkspaceApplicationContract.MAX_MESSAGE_BYTES) { "QUOTA_EXCEEDED" }
                response
            }.getOrElse {
                if (it is CancellationException) throw it
                val known = setOf("REVISION_CONFLICT", "DATA_CONFLICT", "TARGET_CONFLICT", "OPERATION_CONFLICT", "OPERATION_UNAVAILABLE", "QUOTA_EXCEEDED", "RECEIPT_QUOTA_EXCEEDED", "UNSUPPORTED_CAPABILITY", "EXECUTION_MODE_DENIED", "FORMAT_TOOL_MISMATCH")
                val code = it.message?.takeIf { message -> message in known || message in WorkspaceBleContract.errors || message in devices?.errorCodes.orEmpty() } ?: "VALIDATION_FAILED"
                val detail=it.message?.take(500) ?: code
                hostAudit(requestedMethod,"failed",code,detail)
                WorkspaceHostProtocol.failure(requestId, operation, code, detail, if (code == "DATA_CONFLICT") "inspect_and_retry" else "never")
            }
            if (!disposed && current()) reply.postMessage(response.toString())
            }
        }
    }
    /** Fixed host probe: read layout facts only, never execute agent-supplied diagnostic code. */
    suspend fun inspectLayout(): JSONObject = withTimeout(2_500) {
        withContext(Dispatchers.Main.immediate) {
            check(!disposed)
            val script = """
                (()=>{const style=e=>{if(!e)return null;const s=getComputedStyle(e),r=e.getBoundingClientRect();let clip={left:0,top:0,right:innerWidth,bottom:innerHeight};for(let p=e.parentElement;p;p=p.parentElement){const ps=getComputedStyle(p),pr=p.getBoundingClientRect();if(/hidden|clip|scroll|auto/.test(ps.overflowX)){clip.left=Math.max(clip.left,pr.left);clip.right=Math.min(clip.right,pr.right)}if(/hidden|clip|scroll|auto/.test(ps.overflowY)){clip.top=Math.max(clip.top,pr.top);clip.bottom=Math.min(clip.bottom,pr.bottom)}}const cx=Math.max(0,Math.min(innerWidth-1,r.x+r.width/2)),cy=Math.max(0,Math.min(innerHeight-1,r.y+r.height/2)),hit=document.elementFromPoint(cx,cy);return {tag:e.tagName,id:e.id.slice(0,128),text:(e.textContent||'').slice(0,120),backgroundColor:s.backgroundColor.slice(0,100),backgroundImage:s.backgroundImage.slice(0,1000),color:s.color.slice(0,100),display:s.display,width:r.width,height:r.height,x:r.x,y:r.y,disabled:!!e.disabled,visible:r.width>0&&r.height>0&&s.visibility!=='hidden'&&s.display!=='none'&&Number(s.opacity)>0,clipped:r.left<clip.left-1||r.right>clip.right+1||r.top<clip.top-1||r.bottom>clip.bottom+1,occludedAtCenter:!!hit&&hit!==e&&!e.contains(hit),scrollWidth:e.scrollWidth,scrollHeight:e.scrollHeight,clientWidth:e.clientWidth,clientHeight:e.clientHeight}};
                return JSON.stringify({url:location.pathname,readyState:document.readyState,viewport:{width:innerWidth,height:innerHeight,devicePixelRatio,scrollX,scrollY,visualViewport:visualViewport?{width:visualViewport.width,height:visualViewport.height,scale:visualViewport.scale}:null},html:style(document.documentElement),body:style(document.body),
                stylesheets:Array.from(document.querySelectorAll('link[rel=stylesheet]')).slice(0,16).map(e=>({path:new URL(e.href).pathname,loaded:!!e.sheet})),elementLimit:64,elements:Array.from(document.querySelectorAll('[id],main,button,input,select,a,canvas,[role=button]')).slice(0,64).map(style),notice:'Clipping/occlusion are geometric observations, not design failures; scrollable content can be intentional.'});})()
            """.trimIndent()
            suspendCancellableCoroutine { continuation ->
                view.evaluateJavascript(script) { raw ->
                    if (continuation.isActive) {
                        val result = runCatching {
                            require(raw.length <= 64 * 1024)
                            JSONObject(org.json.JSONArray("[$raw]").getString(0)).put("sampled_at_ms", System.currentTimeMillis())
                        }.getOrElse { JSONObject().put("error", "Layout probe returned invalid evidence") }
                        continuation.resumeWith(Result.success(result))
                    }
                }
            }
        }
    }

    /** Chromium visual state plus hardware frame commit: PixelCopy must see a queued frame. */
    suspend fun awaitCompositedFrame() = withTimeout(5_000) {
        withContext(Dispatchers.Main.immediate) {
            check(!disposed && view.isAttachedToWindow && view.isShown && view.isHardwareAccelerated)
            if(android.os.Build.VERSION.SDK_INT>=29) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    var observer: android.view.ViewTreeObserver? = null
                    var committed: Runnable? = null
                    continuation.invokeOnCancellation {
                        view.post { if(android.os.Build.VERSION.SDK_INT>=29)committed?.let {callback -> observer?.takeIf {it.isAlive}?.unregisterFrameCommitCallback(callback)} }
                    }
                    view.postVisualStateCallback(android.os.SystemClock.uptimeMillis(), object : WebView.VisualStateCallback() {
                        override fun onComplete(requestId: Long) {
                            if(!continuation.isActive)return
                            committed=Runnable { view.post { if(continuation.isActive)continuation.resumeWith(Result.success(Unit)) } }
                            observer=view.viewTreeObserver
                            if(android.os.Build.VERSION.SDK_INT>=29)observer!!.registerFrameCommitCallback(committed!!)
                            view.invalidate()
                        }
                    })
                    view.invalidate()
                }
            } else error("Hardware frame commit requires Android 10 or newer")
        }
    }

    /** Copy only this viewport from its owning app window; software redraws can differ from compositor pixels. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    suspend fun captureVisiblePixels(owningWindow: android.view.Window? = null): android.graphics.Bitmap = withTimeout(3_000) {
        withContext(Dispatchers.Main.immediate) {
            check(!disposed && view.isAttachedToWindow && view.isShown) { "Workspace viewport is not visible" }
            require(view.width > 0 && view.height > 0 && view.width.toLong()*view.height <= 8_000_000L)
            var owner: android.content.Context = view.context
            var activity: android.app.Activity? = null
            repeat(8) {
                if (owner is android.app.Activity) activity=owner as android.app.Activity
                else (owner as? android.content.ContextWrapper)?.baseContext?.let { owner=it }
            }
            val window=owningWindow ?: requireNotNull(activity).window
            val location=IntArray(2);view.getLocationInWindow(location)
            val rect=android.graphics.Rect(location[0],location[1],location[0]+view.width,location[1]+view.height)
            require(rect.left>=0 && rect.top>=0 && rect.right<=window.decorView.width && rect.bottom<=window.decorView.height) { "Workspace viewport is clipped" }
            val bitmap=android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888)
            suspendCancellableCoroutine { continuation ->
                try {
                    android.view.PixelCopy.request(window,rect,bitmap,{ status ->
                        if (status==android.view.PixelCopy.SUCCESS && continuation.isActive) {
                            val after=IntArray(2);view.getLocationInWindow(after)
                            if (disposed || !view.isAttachedToWindow || !view.isShown || view.width!=bitmap.width || view.height!=bitmap.height || !after.contentEquals(location)) {
                                bitmap.recycle()
                                continuation.resumeWith(Result.failure(IllegalStateException("Workspace viewport moved during capture; retry")))
                            } else continuation.resume(bitmap) { _, value, _ -> value.recycle() }
                        } else {
                            bitmap.recycle()
                            if (continuation.isActive) continuation.resumeWith(Result.failure(IllegalStateException("Workspace pixel copy failed: $status")))
                        }
                    },android.os.Handler(android.os.Looper.getMainLooper()))
                } catch (failure: Exception) {
                    bitmap.recycle()
                    if (continuation.isActive) continuation.resumeWith(Result.failure(failure))
                }
            }
        }
    }

    fun loadAttached() { if (!disposed && view.isAttachedToWindow && view.width > 0 && view.height > 0) load() }
    fun load() { check(!disposed); view.loadUrl(policy.entryUrl) }
    /** A coroutine timeout does not interrupt JavaScript. Terminate before destroying a stuck view.
     * Android can share renderers; every affected live surface receives onRenderProcessGone.
     */
    suspend fun terminateRenderer() {
        if (!disposed && android.os.Build.VERSION.SDK_INT >= 29 && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_VIEW_RENDERER_TERMINATE)) {
            if(view.webViewRenderProcess?.terminate()==true) {
                // Let Android retire the renderer before a following test opens a fresh surface.
                withTimeoutOrNull(2_000) { rendererGone.await() }
            }
        }
    }
    fun dispose(preserveDeviceConsent: Boolean = false) {
        if (disposed) return
        disposed = true
        bridgeScope.cancel()
        devices?.close(preserveConsent = preserveDeviceConsent)
        view.stopLoading()
        (view.parent as? android.view.ViewGroup)?.removeView(view)
        WebStorage.getInstance().deleteOrigin(policy.origin)
        view.destroy()
    }
    private fun denied() = WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", mapOf("Content-Security-Policy" to policy.csp), ByteArrayInputStream(ByteArray(0)))
    private fun serve(url: String, method: String): WebResourceResponse {
        if (disposed || !current()) return denied()
        // Chromium asks for this optional root icon even when the package has no icon declaration.
        // Supply an explicit empty host response rather than report a fictitious application failure.
        if(policy.optionalBrowserIcon(url,method))return WebResourceResponse("image/x-icon","UTF-8",204,"No Content",mapOf("Cache-Control" to "no-store"),ByteArrayInputStream(ByteArray(0)))
        val path = policy.resource(url, method) ?: run {
            resourceErrorCount.incrementAndGet()
            synchronized(resources) {
                if(resources.size>=32)resources.removeAt(0)
                resources.add(JSONObject().put("path",Uri.parse(url).path.orEmpty().take(240)).put("status",403).put("reason","package_policy_denied").put("at_ms",System.currentTimeMillis()))
            }
            return denied()
        }
        synchronized(resources) {
            if (resources.size >= 32) resources.removeAt(0)
            resources.add(JSONObject().put("path", path).put("status", if (path == "atlas/host.js" || files.has(path)) 200 else 403).put("at_ms", System.currentTimeMillis()))
        }
        if(path!="atlas/host.js" && !files.has(path))resourceErrorCount.incrementAndGet()
        val content = if (path == "atlas/host.js") WorkspaceHostRegistry.sdk() else if (files.has(path)) files.getString(path) else return denied()
        val mime = when (path.substringAfterLast('.')) { "html" -> "text/html"; "js" -> "text/javascript"; "css" -> "text/css"; "svg" -> "image/svg+xml"; "json" -> "application/json"; else -> "text/plain" }
        return WebResourceResponse(mime, "UTF-8", 200, "OK", mapOf("Content-Security-Policy" to policy.csp, "Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff", "Referrer-Policy" to "no-referrer", "X-DNS-Prefetch-Control" to "off", "Access-Control-Allow-Origin" to "*"), ByteArrayInputStream(content.toByteArray(Charsets.UTF_8)))
    }
}
