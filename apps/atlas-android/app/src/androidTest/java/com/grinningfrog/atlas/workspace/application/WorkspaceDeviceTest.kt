package com.grinningfrog.atlas.workspace.application

import androidx.test.core.app.ActivityScenario
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
    private suspend fun surface(bundle: JSONObject, principal: WorkspacePrincipal, store: WorkspaceApplicationData? = null,
        check: suspend (WorkspaceWebPresentation) -> Unit) {
        val ready = CompletableDeferred<Unit>()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var presentation: WorkspaceWebPresentation? = null
        try {
            withContext(Dispatchers.Main) {
                presentation = WorkspaceWebPresentation(context, bundle, principal, { true }, { ready.complete(Unit) }, { ready.completeExceptionally(IllegalStateException(it)) }, store)
            }
            scenario.onActivity { it.setContentView(presentation!!.view); presentation!!.load() }
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
