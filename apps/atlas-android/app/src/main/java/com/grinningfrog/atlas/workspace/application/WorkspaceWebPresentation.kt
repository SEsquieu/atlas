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

/** Substrate spike. No storage/device/network methods are installed and live activation is gated. */
class WorkspaceWebPresentation(
    context: Context,
    bundle: JSONObject,
    private val principal: WorkspacePrincipal,
    private val current: () -> Boolean,
    private val onReady: () -> Unit,
    private val onFailure: (String) -> Unit,
    private val data: WorkspaceApplicationData? = null,
) {
    private val files = JSONObject(bundle.getJSONObject("files").toString())
    private val policy = WorkspacePresentationPolicy(principal, bundle.getJSONObject("manifest").getString("entry"))
    @Volatile private var disposed = false
    private var remainingMessages = 64
    private var messageWindow = android.os.SystemClock.elapsedRealtime()
    private val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val console = java.util.Collections.synchronizedList(mutableListOf<JSONObject>())
    @Volatile private var ready = false
    fun diagnostics(): JSONObject = JSONObject().put("ready", ready).put("disposed", disposed).put("console", org.json.JSONArray(synchronized(console) { console.map { JSONObject(it.toString()) } }))
    val view: WebView

    init {
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
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) WebViewCompat.addDocumentStartJavaScript(view, """
            (() => {
              for (const name of ['RTCPeerConnection','webkitRTCPeerConnection','mozRTCPeerConnection','AudioContext','webkitAudioContext','OfflineAudioContext','WebSocket','EventSource','Worker','SharedWorker','showOpenFilePicker','showSaveFilePicker','showDirectoryPicker','indexedDB','caches','openDatabase','localStorage','sessionStorage']) {
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
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean { dispose(); onFailure("Workspace renderer terminated"); return true }
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
            val response = runCatching {
                val request = WorkspaceHostProtocol.request(raw); requestId = request.getString("request_id")
                val method = request.getString("method")
                val result = withContext(Dispatchers.IO) {
                    require(!disposed && current()) { "REVISION_CONFLICT" }
                    WorkspaceHostRegistry.dispatch(principal, method, request.getJSONObject("params"), data)
                }
                if (method == "host.ready" && !disposed && current()) { ready = true; onReady() }
                val response = WorkspaceHostProtocol.response(requestId, "succeeded", operation, result)
                require(response.toString().toByteArray(Charsets.UTF_8).size <= WorkspaceApplicationContract.MAX_MESSAGE_BYTES) { "QUOTA_EXCEEDED" }
                response
            }.getOrElse {
                if (it is CancellationException) throw it
                val known = setOf("REVISION_CONFLICT", "DATA_CONFLICT", "TARGET_CONFLICT", "OPERATION_CONFLICT", "OPERATION_UNAVAILABLE", "QUOTA_EXCEEDED", "RECEIPT_QUOTA_EXCEEDED", "UNSUPPORTED_CAPABILITY", "EXECUTION_MODE_DENIED", "FORMAT_TOOL_MISMATCH")
                val code = it.message?.takeIf { message -> message in known } ?: "VALIDATION_FAILED"
                WorkspaceHostProtocol.failure(requestId, operation, code, code, if (code == "DATA_CONFLICT") "inspect_and_retry" else "never")
            }
            if (!disposed && current()) reply.postMessage(response.toString())
            }
        }
    }
    fun load() { check(!disposed); view.loadUrl(policy.entryUrl) }
    fun dispose() {
        if (disposed) return
        disposed = true
        bridgeScope.cancel()
        view.stopLoading()
        (view.parent as? android.view.ViewGroup)?.removeView(view)
        WebStorage.getInstance().deleteOrigin(policy.origin)
        view.destroy()
    }
    private fun denied() = WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", mapOf("Content-Security-Policy" to policy.csp), ByteArrayInputStream(ByteArray(0)))
    private fun serve(url: String, method: String): WebResourceResponse {
        if (disposed || !current()) return denied()
        val path = policy.resource(url, method) ?: return denied()
        val content = if (path == "atlas/host.js") WorkspaceHostRegistry.sdk() else if (files.has(path)) files.getString(path) else return denied()
        val mime = when (path.substringAfterLast('.')) { "html" -> "text/html"; "js" -> "text/javascript"; "css" -> "text/css"; "svg" -> "image/svg+xml"; "json" -> "application/json"; else -> "text/plain" }
        return WebResourceResponse(mime, "UTF-8", 200, "OK", mapOf("Content-Security-Policy" to policy.csp, "Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff", "Referrer-Policy" to "no-referrer", "X-DNS-Prefetch-Control" to "off", "Access-Control-Allow-Origin" to "*"), ByteArrayInputStream(content.toByteArray(Charsets.UTF_8)))
    }
}
