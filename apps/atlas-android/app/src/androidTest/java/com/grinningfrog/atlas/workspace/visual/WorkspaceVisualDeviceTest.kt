package com.grinningfrog.atlas.workspace.visual

import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.ParcelFileDescriptor
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.grinningfrog.atlas.AtlasApplication
import com.grinningfrog.atlas.MainActivity
import com.grinningfrog.atlas.workspace.application.WorkspaceApplicationContract
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Runs the actual cross-process display/compositor path; no WebView.draw or screenshot mocks. */
@RunWith(AndroidJUnit4::class)
class WorkspaceVisualDeviceTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun fixture()=WorkspaceApplicationContract.example().apply {
        getJSONObject("files")
            .put("ui/index.html","""<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><link rel="stylesheet" href="./style.css"></head><body><button id="change">Change</button><p id="status">Ready</p><canvas id="board" width="120" height="120"></canvas><div id="clip"><div id="wide">overflow</div></div><script type="module" src="./app.js"></script></body></html>""")
            .put("ui/style.css","html,body{margin:0;background:#123456;color:white}button{position:absolute;left:20px;top:20px;width:120px;height:60px}#status{position:absolute;top:100px}#board{position:absolute;left:180px;top:20px;width:120px;height:120px}#clip{position:absolute;top:160px;width:100px;height:30px;overflow:hidden}#wide{width:400px;height:60px}")
            .put("ui/app.js","""import {host} from '/atlas/host.js';const ctx=document.querySelector('#board').getContext('2d');ctx.fillStyle='#ffcc00';ctx.fillRect(0,0,60,120);ctx.fillStyle='#0033ff';ctx.fillRect(60,0,60,120);document.querySelector('#change').onclick=()=>{document.querySelector('#status').textContent='Changed';document.body.style.background='#b23456';document.documentElement.style.background='#b23456'};await host.ready();""")
    }
    private suspend fun open(client:WorkspaceVisualClient,owner:String,bundle:JSONObject):JSONObject {
        val dm=context.resources.displayMetrics
        val width=(360*dm.density).toInt();val height=(600*dm.density).toInt()
        val profile=JSONObject().put("render_width_px",width).put("render_height_px",height).put("density_dpi",dm.densityDpi).put("viewport_kind","instrumentation_alternative")
        val source=bundle.toString();val file=File.createTempFile("visual-device-",".json",context.cacheDir).also {it.writeText(source)}
        return try { client.request(JSONObject().put("action","open").put("owner",owner).put("profile",profile).put("title","Test candidate").put("base_revision_id","fixture-revision").put("package_sha256",WorkspaceApplicationContract.hash(source)),packageFile=file).json } finally {file.delete()}
    }
    private fun request(action:String,owner:String,id:String)=JSONObject().put("action",action).put("owner",owner).put("instance_id",id)
    private suspend fun pixel(client:WorkspaceVisualClient,owner:String,id:String,cssX:Double?=null,cssY:Double?=null,save:Boolean=false):Int {
        val response=client.request(request("capture",owner,id))
        val bitmap=ParcelFileDescriptor.AutoCloseInputStream(requireNotNull(response.image)).use {BitmapFactory.decodeStream(it)}!!
        return try {
            if(save && android.os.Build.VERSION.SDK_INT>=29) {
                val values=android.content.ContentValues().apply {put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME,"workspace-isolated-device.png");put(android.provider.MediaStore.MediaColumns.MIME_TYPE,"image/png");put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,"Download/")}
                val uri=requireNotNull(context.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,values))
                context.contentResolver.openOutputStream(uri)!!.use {check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))}
            }
            val scale=bitmap.width/360.0
            bitmap.getPixel(cssX?.let {(it*scale).toInt()} ?: (bitmap.width-8),cssY?.let {(it*scale).toInt()} ?: (bitmap.height-8))
        } finally {bitmap.recycle();response.image.close()}
    }
    @Test fun privateWindowCapturesAndInteractsWhileSessionRemainsVisible()=runBlocking {
        val app=context.applicationContext as AtlasApplication
        val client=app.workspaceVisuals
        val owner="instrumentation:${UUID.randomUUID()}"
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        var id:String?=null
        try {
            scenario.onActivity {it.setContent {Surface(modifier=Modifier.fillMaxSize(),color=ComposeColor(0xff241357)) {Text("Session stays visible")}}}
            // Fence the physical activity's own startup frame too; its launch window may still be white.
            withTimeout(8_000) {
                while(true) {
                    val screen=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                    val stable=try {screen.getPixel(screen.width/2,screen.height/2)==0xff241357.toInt()} finally {screen.recycle()}
                    if(stable)break
                    delay(50)
                }
            }
            val before=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            val opened=open(client,owner,fixture());id=opened.getString("instance_id")
            assertNotEquals(android.os.Process.myPid(),opened.getInt("service_pid"))
            assertNotEquals(android.view.Display.DEFAULT_DISPLAY,opened.getInt("display_id"))
            assertEquals("passed_actual_window_pixelcopy",opened.getString("calibration"))
            val layout=opened.getJSONObject("layout");val viewport=layout.getJSONObject("viewport")
            assertEquals(360.0,viewport.getDouble("width"),1.0)
            assertEquals(layout.getInt("native_viewport_width_px").toDouble(),viewport.getDouble("width")*viewport.getDouble("devicePixelRatio"),1.5)
            assertTrue(viewport.getDouble("height") in 300.0..590.0)
            val elements=layout.getJSONArray("elements")
            assertTrue((0 until elements.length()).any {elements.getJSONObject(it).optString("id")=="wide" && elements.getJSONObject(it).getBoolean("clipped")})
            assertEquals(Color.rgb(255,204,0),pixel(client,owner,id,200.0,40.0))
            val first=pixel(client,owner,id)
            assertEquals(0x12,Color.red(first));assertEquals(0x34,Color.green(first));assertEquals(0x56,Color.blue(first))
            // Owner rejection cannot tear down another task's renderer.
            assertTrue(runCatching {client.request(request("inspect","foreign-task",id))}.isFailure)
            client.request(request("inspect",owner,id))
            val steps=JSONArray().put(JSONObject("""{"action":"tap","value":{"x":60,"y":45}}"""))
                .put(JSONObject("""{"action":"assert_text_exact","selector":"#status","value":"Changed","criterion":"button changes state"}"""))
            val callId=UUID.randomUUID().toString()
            val interacted=client.request(request("interact",owner,id).put("steps",steps),callId).json
            assertTrue(interacted.toString(),interacted.getBoolean("passed"))
            // Repeated delivery of a tool call must not inject another gesture.
            assertEquals(interacted.getJSONObject("instance").getInt("operations"),client.request(request("interact",owner,id).put("steps",steps),callId).json.getJSONObject("instance").getInt("operations"))
            assertEquals(0xb2,Color.red(pixel(client,owner,id,save=true)))
            val finished=client.request(request("finish",owner,id)).json
            assertTrue(finished.toString(),finished.getBoolean("passed"))
            assertEquals("button changes state",finished.getJSONArray("verified_criteria").getString(0))
            val after=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            try {assertEquals(before.width,after.width);assertEquals(before.getPixel(before.width/2,before.height/2),after.getPixel(after.width/2,after.height/2))} finally {before.recycle();after.recycle()}
            client.request(request("close",owner,id))
            assertEquals(0,client.request(JSONObject().put("action","status").put("owner",owner)).json.getInt("active_instances"))
            assertTrue(runCatching {client.request(request("inspect",owner,id))}.isFailure)
            id=null
        } finally {
            id?.let {runCatching {client.request(request("close",owner,it))}}
            scenario.close()
        }
    }
    @Test fun storageIsDisposableAndBlockedBrowserCapabilitiesRemainBlocked()=runBlocking {
        val app=context.applicationContext as AtlasApplication;val client=app.workspaceVisuals
        val live=app.database.createComposableWorkspace("Unaffected live workspace","",fixture().toString())
        val owner="instrumentation:${UUID.randomUUID()}"
        val scanPermission=context.checkSelfPermission("android.permission.BLUETOOTH_SCAN")
        val bundle=fixture().apply {
            getJSONObject("manifest").put("capabilities",JSONArray().put(JSONObject("""{"name":"bluetooth.le","operations":["scan"],"resources":["user_selected_device"]}""")))
            getJSONObject("files").put("ui/app.js","""
            import {host,data,ble} from '/atlas/host.js';
            const {devices}=await ble.scan();const unsafe=devices[0]?.name!=='Simulated bench device'||!!(navigator.bluetooth||navigator.mediaDevices||navigator.clipboard||globalThis.localStorage||globalThis.indexedDB);
            async function count(){const page=await data.query({collection:'notes',limit:100});document.querySelector('#status').textContent='rows:'+page.records.length+':'+(unsafe?'UNSAFE':'guarded');return page.data_version}
            await count();document.querySelector('#change').onclick=async()=>{const version=await count();const prepared=await data.prepare({expected_data_version:version,request_key:'one',mutations:[{kind:'create',collection:'notes',data:{text:'test only'}}]});await data.commit(prepared.operation_id);await count()};await host.ready();
        """.trimIndent())}
        var id:String?=null
        try {
            id=open(client,owner,bundle).getString("instance_id")
            val result=client.request(request("interact",owner,id).put("steps",JSONArray()
                .put(JSONObject("""{"action":"assert_text_exact","selector":"#status","value":"rows:0:guarded"}"""))
                .put(JSONObject("""{"action":"click","selector":"#change"}"""))
                .put(JSONObject("""{"action":"wait_text","selector":"#status","value":"rows:1:guarded"}""")))).json
            assertTrue(result.toString(),result.getBoolean("passed"))
            assertEquals(scanPermission,context.checkSelfPermission("android.permission.BLUETOOTH_SCAN"))
            assertEquals(0,app.database.listWorkspaceRecords(live.id,"notes").size)
            assertEquals(live.liveRevisionId,app.database.loadComposableWorkspace(live.id)!!.liveRevisionId)
            client.request(request("close",owner,id))
            id=open(client,owner,bundle).getString("instance_id")
            assertTrue(client.request(request("interact",owner,id).put("steps",JSONArray().put(JSONObject("""{"action":"assert_text_exact","selector":"#status","value":"rows:0:guarded"}""")))).json.getBoolean("passed"))
        } finally {id?.let {runCatching {client.request(request("close",owner,it))}};app.database.setComposableWorkspaceArchived(live.id,true);app.database.deleteComposableWorkspace(live.id)}
    }
    @Test fun cancelledStartupReleasesTheDisplayAndAllowsFreshOpen()=runBlocking {
        val client=(context.applicationContext as AtlasApplication).workspaceVisuals
        val owner="instrumentation:${UUID.randomUUID()}"
        val hung=fixture().apply {getJSONObject("files").put("ui/app.js","while(true){}")}
        val pending=launch {open(client,owner,hung)}
        delay(1_000);pending.cancelAndJoin()
        withTimeout(12_000) {while(client.request(JSONObject().put("action","status").put("owner",owner)).json.getInt("active_instances")!=0)delay(100)}
        var id:String?=null
        try {id=open(client,owner,fixture()).getString("instance_id");assertEquals(0x12,Color.red(pixel(client,owner,id)))}
        finally {id?.let {runCatching {client.request(request("close",owner,it))}}}
    }
    @Test fun remoteProcessDeathExpiresStateWithoutCrashingTheForegroundProcess()=runBlocking {
        val client=(context.applicationContext as AtlasApplication).workspaceVisuals
        val owner="instrumentation:${UUID.randomUUID()}"
        val opened=open(client,owner,fixture());val id=opened.getString("instance_id")
        val pid=opened.getInt("service_pid");assertNotEquals(android.os.Process.myPid(),pid)
        android.os.Process.killProcess(pid)
        delay(1_000)
        assertTrue(runCatching {client.request(request("inspect",owner,id))}.isFailure)
        var fresh:String?=null
        try {
            val result=open(client,owner,fixture());fresh=result.getString("instance_id")
            assertNotEquals(id,fresh);assertNotEquals(opened.getString("process_nonce"),result.getString("process_nonce"))
            assertEquals(0x12,Color.red(pixel(client,owner,fresh)))
        } finally {fresh?.let {runCatching {client.request(request("close",owner,it))}}}
    }
    @Test fun failedAssertionsCannotProducePassingVisualReceipt()=runBlocking {
        val client=(context.applicationContext as AtlasApplication).workspaceVisuals
        val owner="instrumentation:${UUID.randomUUID()}";var id:String?=null
        try {
            id=open(client,owner,fixture()).getString("instance_id")
            client.request(request("interact",owner,id).put("steps",JSONArray().put(JSONObject("""{"action":"assert_text_exact","selector":"#status","value":"Wrong"}"""))))
            pixel(client,owner,id)
            assertFalse(client.request(request("finish",owner,id)).json.getBoolean("passed"))
        } finally {id?.let {runCatching {client.request(request("close",owner,it))}}}
    }
}
