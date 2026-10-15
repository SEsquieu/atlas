package com.grinningfrog.atlas.workspace.application

import androidx.test.core.app.ActivityScenario
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.core.view.doOnAttach
import androidx.core.view.doOnLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.grinningfrog.atlas.AtlasApplication
import com.grinningfrog.atlas.MainActivity
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

@RunWith(AndroidJUnit4::class)
class WorkspaceDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun previewLoadsAuthoredStylesWithoutClaimingVisualVerification() = runBlocking {
        val bundle = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("neon-vale-rendering.json").bufferedReader().use { it.readText() })
        val principal = WorkspacePrincipal("test", "neon", "neon", UUID.randomUUID().toString(), 1, WorkspaceInstanceMode.PREVIEW)
        surface(bundle, principal) { presentation ->
            val layout=presentation.inspectLayout()
            assertTrue(layout.toString(),layout.getJSONObject("body").getString("backgroundImage").startsWith("radial-gradient("))
            assertTrue(layout.getJSONArray("stylesheets").getJSONObject(0).getBoolean("loaded"))
            assertTrue(presentation.diagnostics().getBoolean("ready"))
            // Actual painted pixels are asserted in the foreground Compose-panel regression below.
        }
    }
    @Test fun neonValeBackgroundPaintsThroughComposePanel() = runBlocking {
        val bundle = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("neon-vale-rendering.json").bufferedReader().use { it.readText() })
        val db=(context.applicationContext as AtlasApplication).database
        val w=db.createComposableWorkspace("Neon rendering", "", bundle.toString())
        val revision=db.loadLiveWorkspaceRevision(w.id)!!
        WorkspaceApplicationData(db).setEnabled(w.id,revision.id,true)
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity -> activity.setContent { androidx.compose.material3.MaterialTheme { WorkspaceApplicationPanel(w,revision,db,Modifier.fillMaxSize()) } } }
            withTimeout(15_000) { while(com.grinningfrog.atlas.workspace.WorkspaceRenderSurface.diagnostics(w.id,revision.id)?.optJSONObject("runtime")?.optBoolean("ready") != true) delay(100) }
            delay(500)
            val layout=com.grinningfrog.atlas.workspace.WorkspaceRenderSurface.inspect(w.id,revision.id)
            val coordinates=IntArray(2)
            var webView: android.webkit.WebView? = null
            scenario.onActivity { activity ->
                fun find(view: android.view.View): android.webkit.WebView? {
                    if(view is android.webkit.WebView) return view
                    if(view is android.view.ViewGroup) for(i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
                    return null
                }
                webView=requireNotNull(find(activity.findViewById(android.R.id.content)))
                webView!!.getLocationOnScreen(coordinates)
            }
            val screenshot=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            try {
                val color=screenshot.getPixel(coordinates[0]+5,coordinates[1]+10)
                val captured=com.grinningfrog.atlas.workspace.WorkspaceRenderSurface.capture(w.id,revision.id).first
                try { assertEquals("Model viewport pixels differ from the displayed window",color,captured.getPixel(5,10)) } finally { captured.recycle() }
                val painted=android.graphics.Color.red(color)<80 && android.graphics.Color.green(color) in 9..79 && android.graphics.Color.blue(color)<80
                var alternatives=""
                if (!painted) {
                    withContext(Dispatchers.Main) { webView!!.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE,null); webView!!.invalidate() }
                    delay(300)
                    val software=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                    try { alternatives+=" software="+Integer.toHexString(software.getPixel(coordinates[0]+5,coordinates[1]+10)) } finally { software.recycle() }
                    withContext(Dispatchers.Main) { webView!!.evaluateJavascript("document.documentElement.style.background=getComputedStyle(document.body).background;",null) }
                    delay(300)
                    val rootPaint=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                    try { alternatives+=" explicitRoot="+Integer.toHexString(rootPaint.getPixel(coordinates[0]+5,coordinates[1]+10)) } finally { rootPaint.recycle() }
                }
                assertTrue("Compose background="+Integer.toHexString(color)+alternatives+" layout="+layout,painted)
            } finally { screenshot.recycle() }
        } finally { scenario.close();db.deleteComposableWorkspace(w.id) }
    }
    @Test fun nativeCanvasFallbackDoesNotOverrideAuthoredLightTheme() = runBlocking {
        val bundle=WorkspaceApplicationContract.example()
        bundle.getJSONObject("files").put("ui/style.css","body{margin:0;background:transparent}main{padding:32px}")
        val principal=WorkspacePrincipal("test","canvas","canvas",UUID.randomUUID().toString(),1,WorkspaceInstanceMode.PREVIEW)
        surface(bundle,principal) { presentation ->
            delay(300)
            val location=IntArray(2)
            withContext(Dispatchers.Main) { presentation.view.getLocationOnScreen(location) }
            fun pixel(): Int {
                val image=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                return try { image.getPixel(location[0]+5,location[1]+10) } finally { image.recycle() }
            }
            assertEquals(android.graphics.Color.rgb(1,3,2),pixel())
            evaluate(presentation,"document.documentElement.style.background='white';document.body.style.background='white';")
            delay(300)
            assertEquals(android.graphics.Color.WHITE,pixel())
        }
    }
    @Test fun isolatedWorkerExecutesAndTerminatesRunawayModule() = runBlocking {
        val worker = WorkspaceWorkerSupervisor(context)
        val bundle = WorkspaceApplicationContract.example()
        bundle.getJSONObject("files").put("assets/one.txt", "x".repeat(200_000)).put("assets/two.txt", "y".repeat(200_000))
        val large = worker.run(bundle, "health", JSONObject.NULL)
        assertTrue(large.toString(), large.optBoolean("ok"))
        assertTrue(large.getBoolean("value"))
        bundle.getJSONObject("files").put("tools/health.js", "export function health(){while(true){}}")
        assertFalse(worker.run(bundle, "health", JSONObject.NULL).getBoolean("ok"))
        assertTrue(worker.run(WorkspaceApplicationContract.example(), "health", JSONObject.NULL).getBoolean("value"))
    }
    @Test fun agentToolsBuildActivateEditAndPreserveLiveFormData() = runBlocking {
        val db = (context.applicationContext as AtlasApplication).database
        val session = db.createSession("Application flow", "Live host test")
        db.updateSessionStatus(session.id, com.grinningfrog.atlas.model.SessionStatus.ACTIVE)
        val turn = db.createTurn(session.id, "user")
        val harness = com.grinningfrog.atlas.runtime.ToolHarness(db, { _, _ -> error("No camera") }, { com.grinningfrog.atlas.model.DeviceHealth() },
            additionalTools = com.grinningfrog.atlas.workspace.WorkspaceTools.create(db, applicationContext = context))
        suspend fun tool(name: String, args: JSONObject): JSONObject {
            val current = db.loadSession(session.id)!!
            val proposal = com.grinningfrog.atlas.model.ToolCallProposal(UUID.randomUUID().toString(), name, args.toString())
            assertTrue(name, harness.evaluate(current, proposal).allowed)
            val call = com.grinningfrog.atlas.model.AtlasToolCall(proposal.id, session.id, turn.id, name, args.toString(), com.grinningfrog.atlas.model.ToolCallStatus.PROPOSED,
                harness.definition(name)!!.risk, false, proposal.id, createdAtMs = 0, updatedAtMs = 0)
            return JSONObject(harness.execute(call).resultJson)
        }
        val created = tool("workspace_package_create", JSONObject().put("name", "Live notes"))
        val id = created.getString("workspace_id"); val sequence = created.getLong("navigation_sequence")
        try {
            val bundle = WorkspaceApplicationContract.example()
            bundle.getJSONObject("files").put("ui/style.css", bundle.getJSONObject("files").getString("ui/style.css") + "/*" + "x".repeat(20_000) + "*/")
            val staged = tool("workspace_package_stage", JSONObject().put("workspace_id", id).put("base_revision_id", created.getString("base_revision_id")).put("navigation_sequence", sequence)
                .put("manifest", bundle.getJSONObject("manifest")).put("files", bundle.getJSONObject("files")))
            val checked = tool("workspace_package_test", JSONObject().put("workspace_id", id).put("candidate_id", staged.getString("candidate_id")))
            assertTrue(checked.toString(), checked.getBoolean("passed"))
            val activated = tool("workspace_package_activate", JSONObject().put("workspace_id", id).put("candidate_id", staged.getString("candidate_id")).put("check_id", checked.getString("check_id")).put("navigation_sequence", sequence))
            val w = db.loadComposableWorkspace(id)!!; val store = WorkspaceApplicationData(db)
            val old = WorkspacePrincipal(w.realmId, id, activated.getString("revision_id"), UUID.randomUUID().toString(), store.epoch(id), WorkspaceInstanceMode.FOREGROUND)
            surface(JSONObject(db.loadLiveWorkspaceRevision(id)!!.definitionJson), old, store) { view ->
                delay(300)
                evaluate(view, "document.querySelector('#text').value='From the form';document.querySelector('button').click();")
                withTimeout(5000) { while (db.listWorkspaceRecords(id, "notes").isEmpty()) delay(50) }
                assertEquals("From the form", JSONObject(db.listWorkspaceRecords(id, "notes").single().dataJson).getString("text"))
            }
            val updated = tool("workspace_package_stage", JSONObject().put("workspace_id", id).put("base_revision_id", activated.getString("revision_id")).put("navigation_sequence", sequence)
                .put("files", JSONObject().put("ui/style.css", "body{background:#101010;color:#a8ff00;font:18px sans-serif}")))
            val rechecked = tool("workspace_package_test", JSONObject().put("workspace_id", id).put("candidate_id", updated.getString("candidate_id")))
            tool("workspace_package_activate", JSONObject().put("workspace_id", id).put("candidate_id", updated.getString("candidate_id")).put("check_id", rechecked.getString("check_id")).put("navigation_sequence", sequence))
            assertEquals(1, db.listWorkspaceRecords(id, "notes").size)
            assertTrue(runCatching { store.query(old, JSONObject().put("collection", "notes")) }.isFailure)
        } finally { db.setComposableWorkspaceArchived(id, true); db.deleteComposableWorkspace(id) }
    }
    private suspend fun evaluate(p: WorkspaceWebPresentation, script: String): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation -> p.view.evaluateJavascript(script) { if (continuation.isActive) continuation.resume(it) } }
    }
    private suspend fun surface(bundle: JSONObject, principal: WorkspacePrincipal, store: WorkspaceApplicationData? = null, devices: WorkspaceDeviceBroker? = null,
        check: suspend (WorkspaceWebPresentation) -> Unit) {
        val ready = CompletableDeferred<Unit>()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var presentation: WorkspaceWebPresentation? = null
        try {
            scenario.onActivity { activity ->
                presentation = WorkspaceWebPresentation(activity, bundle, principal, { true }, { ready.complete(Unit) }, { ready.completeExceptionally(IllegalStateException(it)) }, store, devices)
                val container = android.widget.FrameLayout(activity).apply {
                    addView(presentation!!.view, android.widget.FrameLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT))
                }
                activity.setContentView(container)
                presentation!!.view.doOnAttach { view -> view.doOnLayout { presentation!!.loadAttached() } }
            }
            withTimeout(15_000) { ready.await() }
            check(presentation!!)
        } finally {
            withContext(Dispatchers.Main) { presentation?.dispose() }
            scenario.close()
        }
    }
    @Test fun browserNetworkDeviceStorageAndChildFrameBypassesAreDenied() = runBlocking {
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")); val hits = AtomicInteger()
        val udp = java.net.DatagramSocket(0, InetAddress.getByName("127.0.0.1")); val udpHits = AtomicInteger()
        val udpThread = Thread { while (!udp.isClosed) { try { val packet = java.net.DatagramPacket(ByteArray(2048), 2048); udp.receive(packet); udpHits.incrementAndGet() } catch (_: Exception) { break } } }.apply { isDaemon = true; start() }
        val thread = Thread { while (!server.isClosed) { try { server.accept().use { hits.incrementAndGet() } } catch (_: Exception) { break } } }.apply { isDaemon = true; start() }
        try {
            val bundle = WorkspaceApplicationContract.example()
            bundle.getJSONObject("files").put("assets/fixture.json", "{\"value\":7}")
            bundle.getJSONObject("files").put("ui/app.js", """
                import {host} from '/atlas/host.js';
                const issues=[];
                try{const local=await fetch('/assets/fixture.json').then(r=>r.json());if(local.value!==7)issues.push('packaged JSON');}catch(_){issues.push('packaged JSON blocked');}
                for(const name of ['RTCPeerConnection','WebSocket','WebTransport','Worker','SharedWorker','indexedDB','localStorage']) if(typeof globalThis[name]!=='undefined')issues.push(name);
                for(const name of ['bluetooth','usb','mediaDevices','geolocation','serviceWorker','credentials','clipboard','sendBeacon']) if(typeof navigator[name]!=='undefined')issues.push('navigator.'+name);
                const iframe=document.createElement('iframe');document.body.append(iframe);
                try{if(iframe.contentWindow && typeof iframe.contentWindow.RTCPeerConnection!=='undefined')issues.push('child.RTCPeerConnection');}catch(_){}
                iframe.srcdoc="<script>try{const p=new RTCPeerConnection({iceServers:[{urls:'stun:127.0.0.1:${udp.localPort}'}]});p.createDataChannel('probe');p.createOffer().then(o=>p.setLocalDescription(o));}catch(_){}<"+"/script>";
                if(globalThis.origin!=='null')issues.push('non-opaque document');
                const target='https://127.0.0.1:${server.localPort}/bypass';
                try{await fetch(target);issues.push('fetch');}catch(_){}
                const image=new Image();image.src=target;document.body.append(image);
                const script=document.createElement('script');script.src=target;document.head.append(script);
                const unsupported=await host.request('device.ble.connect',{device:'forged'});if(unsupported.status!=='failed')issues.push('device bridge');
                const bleDenied=await host.request('ble.scan',{});if(bleDenied.error?.code!=='EXECUTION_MODE_DENIED')issues.push('preview BLE');
                const storage=await host.request('data.query',{collection:'notes'});if(storage.status!=='failed')issues.push('preview storage');
                document.body.dataset.results=JSON.stringify(issues);await host.ready();
            """.trimIndent())
            val p = WorkspacePrincipal("test", "test", "test", UUID.randomUUID().toString(), 1, WorkspaceInstanceMode.PREVIEW)
            surface(bundle, p) { view ->
                val raw = evaluate(view, "document.body.dataset.results")
                assertEquals(raw, "[]", JSONArray("[$raw]").getString(0))
                evaluate(view, "setTimeout(()=>{throw Error('interaction-failure-probe')},0);Promise.reject(Error('promise-failure-probe'));")
                delay(300)
                val console = view.diagnostics().getJSONArray("console").toString()
                assertTrue(console, console.contains("interaction-failure-probe"))
                assertTrue(console, console.contains("promise-failure-probe"))
                delay(1500); assertEquals("A browser request escaped the host", 0, hits.get()); assertEquals("Child-frame WebRTC escaped the sandbox", 0, udpHits.get())
            }
        } finally { server.close(); udp.close(); thread.join(1000); udpThread.join(1000) }
    }
    @Test fun bleSdkUsesRealTransportAndOperationPollingWithInjectedRadio() = runBlocking {
        val calls = mutableListOf<String>()
        val radio = object : WorkspaceBleAdapter {
            override fun permitted() = true
            override fun status() = JSONObject().put("installed", true)
            override suspend fun scan(): JSONArray { delay(30); calls.add("scan"); return JSONArray().put(JSONObject().put("device_id", "seen")) }
            override fun deviceLabel(id: String) = "Bench fixture"
            override suspend fun connect(id: String): JSONObject { calls.add("connect"); return JSONObject().put("connected", true) }
            override fun services() = JSONArray().put(JSONObject().put("uuid", "test-service").put("characteristics", JSONArray().put(JSONObject().put("characteristic_id", "char"))))
            override suspend fun read(id: String): JSONObject { calls.add("read"); return JSONObject().put("hex", "1234") }
            override suspend fun write(id: String, hex: String): JSONObject { calls.add("write:"+hex); return JSONObject().put("acknowledged", true) }
            override suspend fun subscribe(id: String, enabled: Boolean): JSONObject { calls.add("subscribe"); return JSONObject().put("enabled", enabled) }
            override fun events() = JSONObject().put("events", JSONArray().put(JSONObject().put("hex", "5678"))).put("dropped", 0)
            override fun close() { calls.add("close") }
        }
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val principal=WorkspacePrincipal("test","ble","ble",UUID.randomUUID().toString(),1,WorkspaceInstanceMode.FOREGROUND)
        val broker=WorkspaceDeviceBroker(principal,WorkspaceBleContract.operations,{true},radio,{true},{true},scope)
        val bundle=WorkspaceApplicationContract.example()
        bundle.getJSONObject("files").put("ui/app.js", """
            import {host,ble} from '/atlas/host.js';
            const {devices}=await ble.scan(); await ble.connect(devices[0].device_id);
            const {services}=await ble.services();const id=services[0].characteristics[0].characteristic_id;
            const reading=await ble.read(id);await ble.write(id,'0102');await ble.subscribe(id,true);
            const events=await ble.events();
            document.body.dataset.results=JSON.stringify({reading,events});await host.ready();
        """.trimIndent())
        try {
            surface(bundle,principal,devices=broker) { presentation ->
                val raw=evaluate(presentation,"document.body.dataset.results")
                val result=JSONObject(JSONArray("[$raw]").getString(0))
                assertEquals("1234",result.getJSONObject("reading").getString("hex"))
                assertEquals("5678",result.getJSONObject("events").getJSONArray("events").getJSONObject(0).getString("hex"))
                assertEquals(listOf("scan","connect","read","write:0102","subscribe"),calls)
            }
        } finally { withContext(Dispatchers.Main) { broker.close() };scope.cancel() }
    }
    @Test fun nativeBleDenialBlocksPageRequestsUntilNativeReenabled() = runBlocking {
        val db=(context.applicationContext as AtlasApplication).database
        val bundle=WorkspaceApplicationContract.example()
        bundle.getJSONObject("manifest").getJSONArray("capabilities").put(WorkspaceBleContract.descriptor().getJSONObject("manifest_example"))
        bundle.getJSONObject("files").put("ui/app.js", """
            import {host,ble} from '/atlas/host.js';
            globalThis.tryBle=async()=>{try{await ble.scan();document.body.dataset.ble='unexpected-success'}catch(error){document.body.dataset.ble=error.code}};
            await host.ready();
        """.trimIndent())
        val workspace=db.createComposableWorkspace("BLE native denial","",bundle.toString())
        val revision=db.loadLiveWorkspaceRevision(workspace.id)!!
        WorkspaceApplicationData(db).setEnabled(workspace.id,revision.id,true)
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        var web: android.webkit.WebView?=null
        var stage="startup"
        var lastTree=""
        suspend fun evaluatePage(script:String):String=withContext(Dispatchers.Main) { suspendCancellableCoroutine { c -> web!!.evaluateJavascript(script) { if(c.isActive)c.resume(it) } } }
        try {
            scenario.onActivity { activity -> activity.setContent { MaterialThemeForTest { WorkspaceApplicationPanel(workspace,revision,db,Modifier.fillMaxSize()) } } }
            withTimeout(15_000) { while(com.grinningfrog.atlas.workspace.WorkspaceRenderSurface.diagnostics(workspace.id,revision.id)?.optJSONObject("runtime")?.optBoolean("ready")!=true)delay(100) }
            scenario.onActivity { activity ->
                fun find(view:android.view.View):android.webkit.WebView? { if(view is android.webkit.WebView)return view;if(view is android.view.ViewGroup)for(i in 0 until view.childCount)find(view.getChildAt(i))?.let{return it};return null }
                web=find(activity.findViewById(android.R.id.content))
            }
            stage="request scan"
            evaluatePage("tryBle();void 0")
            val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
            stage="find native Deny button"
            withTimeout(8_000) {
                var clicked=false
                while(!clicked) {
                    val root=automation.rootInActiveWindow
                    val tree=StringBuilder()
                    fun visit(node:android.view.accessibility.AccessibilityNodeInfo?,depth:Int=0) {
                        if(node==null || depth>24)return
                        tree.append(" ".repeat(depth)).append(node.className).append(": ").append(node.text).append(" / ").append(node.contentDescription).append(" clickable=").append(node.isClickable).append('\n')
                        if(!clicked && (node.text?.toString()=="Deny" || node.contentDescription?.toString()=="Deny")) {
                            var target:android.view.accessibility.AccessibilityNodeInfo?=node
                            repeat(4) {
                                if(!clicked) {
                                    if(target?.isClickable==true) clicked=target!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                                    else target=target?.parent
                                }
                            }
                        }
                        for(i in 0 until node.childCount)visit(node.getChild(i),depth+1)
                    }
                    visit(root);lastTree=tree.toString().take(16000)
                    if(!clicked)delay(100)
                }
            }
            stage="receive denied result"
            withTimeout(8_000) { while(evaluatePage("document.body.dataset.ble")!="\"WORKSPACE_GRANT_REQUIRED\"")delay(100) }
            stage="reject repeat request without another dialog"
            evaluatePage("document.body.dataset.ble='';tryBle();void 0")
            withTimeout(8_000) { while(evaluatePage("document.body.dataset.ble")!="\"WORKSPACE_GRANT_REQUIRED\"")delay(100) }
            assertFalse(com.grinningfrog.atlas.workspace.WorkspaceRenderSurface.diagnostics(workspace.id,revision.id)!!.getJSONObject("runtime").getJSONObject("devices").getBoolean("requests_enabled"))
        } catch(failure:Exception) {
            val state=runCatching { evaluatePage("document.body.dataset.ble") }.getOrDefault("unavailable")
            val diagnostics=com.grinningfrog.atlas.workspace.WorkspaceRenderSurface.diagnostics(workspace.id,revision.id)
            throw AssertionError("BLE denial stage=$stage page=$state diagnostics=$diagnostics tree=$lastTree",failure)
        } finally { scenario.close();db.deleteComposableWorkspace(workspace.id) }
    }
    @androidx.compose.runtime.Composable private fun MaterialThemeForTest(content:@androidx.compose.runtime.Composable ()->Unit) { androidx.compose.material3.MaterialTheme(content=content) }
    @Test fun realStorageBridgeRejectsForgedScopeAndPersistsExactlyOneRecord() = runBlocking {
        val db = (context.applicationContext as AtlasApplication).database
        val bundle = WorkspaceApplicationContract.example()
        bundle.getJSONObject("files").put("ui/app.js", """
            import {host,data} from '/atlas/host.js';
            const query=await data.query({collection:'notes'});
            const request={expected_data_version:query.data_version,request_key:'device-test',mutations:[{kind:'create',collection:'notes',data:{text:'Persisted'}}]};
            const first=await data.prepare(request), repeated=await data.prepare(request);if(first.operation_id!==repeated.operation_id)throw Error('prepare replay');
            await data.commit(first.operation_id);await data.commit(first.operation_id);
            const forged=await host.request('data.query',{collection:'notes',workspace_id:'other'});if(forged.status!=='failed')throw Error('forged scope');
            document.body.dataset.results=JSON.stringify(await data.query({collection:'notes'}));await host.ready();
        """.trimIndent())
        val w = db.createComposableWorkspace("Device test", "", bundle.toString())
        val store = WorkspaceApplicationData(db); store.setEnabled(w.id, w.liveRevisionId, true)
        val principal = WorkspacePrincipal(w.realmId, w.id, w.liveRevisionId, UUID.randomUUID().toString(), store.epoch(w.id), WorkspaceInstanceMode.FOREGROUND)
        try {
            surface(bundle, principal, store) { view ->
                val raw = evaluate(view, "document.body.dataset.results")
                val page = JSONObject(JSONArray("[$raw]").getString(0)); assertEquals(1, page.getJSONArray("records").length())
                assertEquals("Persisted", page.getJSONArray("records").getJSONObject(0).getJSONObject("data").getString("text"))
                withContext(Dispatchers.Main) {
                    val bitmap = android.graphics.Bitmap.createBitmap(view.view.width, view.view.height, android.graphics.Bitmap.Config.ARGB_8888)
                    view.view.draw(android.graphics.Canvas(bitmap))
                    val values = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "workspace-live-device.png")
                        put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/png")
                        put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download")
                    }
                    val uri = requireNotNull(context.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
                    requireNotNull(context.contentResolver.openOutputStream(uri)).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
            }
            assertEquals(1, db.listWorkspaceRecords(w.id, "notes").size)
        } finally { db.setComposableWorkspaceArchived(w.id, true); db.deleteComposableWorkspace(w.id) }
    }
}
