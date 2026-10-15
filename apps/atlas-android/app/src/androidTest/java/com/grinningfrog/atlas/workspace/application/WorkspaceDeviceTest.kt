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
        assertTrue(worker.run(bundle, "health", JSONObject.NULL).getBoolean("value"))
        bundle.getJSONObject("files").put("tools/health.js", "export function health(){while(true){}}")
        assertFalse(worker.run(bundle, "health", JSONObject.NULL).getBoolean("ok"))
        assertTrue(worker.run(WorkspaceApplicationContract.example(), "health", JSONObject.NULL).getBoolean("value"))
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
        val thread = Thread { while (!server.isClosed) { try { server.accept().use { hits.incrementAndGet() } } catch (_: Exception) { break } } }.apply { isDaemon = true; start() }
        try {
            val bundle = WorkspaceApplicationContract.example()
            bundle.getJSONObject("files").put("ui/app.js", """
                import {host} from '/atlas/host.js';
                const issues=[];
                for(const name of ['RTCPeerConnection','WebSocket','Worker','SharedWorker','indexedDB','localStorage']) if(typeof globalThis[name]!=='undefined')issues.push(name);
                for(const name of ['bluetooth','usb','mediaDevices','geolocation','serviceWorker','credentials','clipboard','sendBeacon']) if(typeof navigator[name]!=='undefined')issues.push('navigator.'+name);
                const iframe=document.createElement('iframe');document.body.append(iframe);
                try{if(iframe.contentWindow && typeof iframe.contentWindow.RTCPeerConnection!=='undefined')issues.push('child.RTCPeerConnection');}catch(_){}
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
                delay(500); assertEquals("A browser request escaped the host", 0, hits.get())
            }
        } finally { server.close(); thread.join(1000) }
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
            }
            assertEquals(1, db.listWorkspaceRecords(w.id, "notes").size)
        } finally { db.setComposableWorkspaceArchived(w.id, true); db.deleteComposableWorkspace(w.id) }
    }
}
