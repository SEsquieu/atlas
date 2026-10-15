package com.grinningfrog.atlas.workspace.application

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceDeviceBrokerTest {
    private val principal = WorkspacePrincipal("realm", "workspace", "revision", "instance", 4, WorkspaceInstanceMode.FOREGROUND)
    private class Radio : WorkspaceBleAdapter {
        var permission = true
        var scans = 0; var connections = 0; var writes = 0; var closes = 0
        var delayRead = false
        override fun permitted() = permission
        override fun status() = JSONObject().put("connected", connections > closes)
        override suspend fun scan(): JSONArray { scans++; return JSONArray().put(JSONObject().put("device_id", "seen")) }
        override fun deviceLabel(id: String): String { require(id == "seen") { "DEVICE_UNAVAILABLE" }; return "Bench" }
        override suspend fun connect(id: String): JSONObject { connections++; return JSONObject().put("connected", true) }
        override fun services() = JSONArray()
        override suspend fun read(id: String): JSONObject { if (delayRead) delay(20_000); return JSONObject().put("hex", "0102") }
        override suspend fun write(id: String, hex: String): JSONObject { writes++; return JSONObject().put("acknowledged", true) }
        override suspend fun subscribe(id: String, enabled: Boolean) = JSONObject().put("enabled", enabled)
        override fun events() = JSONObject().put("events", JSONArray())
        override fun close() { closes++ }
    }
    private fun result(broker: WorkspaceDeviceBroker, id: String) = broker.dispatch("ble.operation", JSONObject().put("operation_id", id))
    private fun submit(broker: WorkspaceDeviceBroker, method: String, params: JSONObject = JSONObject()) = broker.dispatch(method, params).getString("operation_id")
    private suspend fun TestScope.connected(broker: WorkspaceDeviceBroker) {
        val scan = submit(broker, "ble.scan"); runCurrent(); assertEquals("succeeded", result(broker, scan).getString("status"))
        val connect = submit(broker, "ble.connect", JSONObject().put("device_id", "seen")); runCurrent(); assertEquals("succeeded", result(broker, connect).getString("status"))
    }
    @Test fun nativeDenialNeverTouchesRadioAndBlocksPromptSpam() = runTest {
        val radio = Radio(); var prompts = 0; var osPrompts = 0
        val broker = WorkspaceDeviceBroker(principal, WorkspaceBleContract.operations, { true }, radio, { prompts++; false }, { osPrompts++; true }, this)
        val id = submit(broker, "ble.scan"); runCurrent()
        assertEquals("WORKSPACE_GRANT_REQUIRED", result(broker, id).getJSONObject("error").getString("code"))
        assertEquals(0, radio.scans); assertEquals(0, osPrompts)
        assertTrue(runCatching { submit(broker, "ble.scan") }.isFailure); assertEquals(1, prompts)
        broker.enableRequests(); submit(broker, "ble.scan"); runCurrent(); assertEquals(2, prompts)
    }
    @Test fun androidPermissionIsNotWorkspaceConsentAndMissingPermissionNeverScans() = runTest {
        val radio = Radio(); radio.permission = false
        val broker = WorkspaceDeviceBroker(principal, WorkspaceBleContract.operations, { true }, radio, { true }, { false }, this)
        val id = submit(broker, "ble.scan"); runCurrent()
        assertEquals("OS_PERMISSION_REQUIRED", result(broker, id).getJSONObject("error").getString("code")); assertEquals(0, radio.scans)
    }
    @Test fun selectedDeviceRequiresSecondConsentAndForgedHandlesCannotConnect() = runTest {
        val radio = Radio(); var prompts = 0
        val broker = WorkspaceDeviceBroker(principal, WorkspaceBleContract.operations, { true }, radio, { ++prompts == 1 }, { true }, this)
        submit(broker, "ble.scan"); runCurrent()
        val forged = submit(broker, "ble.connect", JSONObject().put("device_id", "other")); runCurrent()
        assertEquals("DEVICE_UNAVAILABLE", result(broker, forged).getJSONObject("error").getString("code"))
        val connect = submit(broker, "ble.connect", JSONObject().put("device_id", "seen")); runCurrent()
        assertEquals("WORKSPACE_GRANT_REQUIRED", result(broker, connect).getJSONObject("error").getString("code")); assertEquals(0, radio.connections)
    }
    @Test fun operationReceiptsDoNotRepeatWritesAndRevokeInvalidatesReceipts() = runTest {
        val radio = Radio(); val broker = WorkspaceDeviceBroker(principal, WorkspaceBleContract.operations, { true }, radio, { true }, { true }, this)
        connected(broker)
        val id = submit(broker, "ble.write", JSONObject().put("characteristic_id", "char").put("hex", "0102")); runCurrent()
        repeat(3) { assertTrue(result(broker, id).getJSONObject("result").getBoolean("acknowledged")) }
        assertEquals(1, radio.writes)
        broker.revoke(blockRequests = true)
        assertEquals("GRANT_REVOKED", result(broker, id).getJSONObject("error").getString("code"))
        assertTrue(runCatching { broker.dispatch("ble.services", JSONObject()) }.isFailure)
        val other = WorkspaceDeviceBroker(principal.copy(workspaceId = "other"), WorkspaceBleContract.operations, { true }, Radio(), { true }, { true }, this)
        assertTrue(runCatching { result(other, id) }.isFailure)
    }
    @Test fun revisionChangeAndPermissionLossCloseRadioAndSuppressReadDelivery() = runTest {
        for (permissionLoss in listOf(false, true)) {
            val radio = Radio(); var current = true
            val broker = WorkspaceDeviceBroker(principal, WorkspaceBleContract.operations, { current }, radio, { true }, { true }, this)
            connected(broker); radio.delayRead = true
            val id = submit(broker, "ble.read", JSONObject().put("characteristic_id", "char")); runCurrent()
            if (permissionLoss) radio.permission = false else current = false
            broker.refreshAuthority(); runCurrent(); advanceUntilIdle()
            assertTrue(radio.closes > 0)
            if (permissionLoss) assertEquals("GRANT_REVOKED", result(broker, id).getJSONObject("error").getString("code"))
            else assertTrue(runCatching { result(broker, id) }.isFailure)
        }
    }
    @Test fun previewsTestsAndUndeclaredOperationsNeverReachRadio() = runTest {
        for (mode in listOf(WorkspaceInstanceMode.PREVIEW, WorkspaceInstanceMode.TEST, WorkspaceInstanceMode.PERSISTENT)) {
            val radio = Radio(); val broker = WorkspaceDeviceBroker(principal.copy(mode = mode), WorkspaceBleContract.operations, { true }, radio, { true }, { true }, this)
            assertTrue(runCatching { submit(broker, "ble.scan") }.isFailure); assertEquals(0, radio.scans)
        }
        val radio = Radio(); val broker = WorkspaceDeviceBroker(principal, setOf("scan"), { true }, radio, { true }, { true }, this)
        assertTrue(runCatching { submit(broker, "ble.write", JSONObject().put("characteristic_id", "char").put("hex", "01")) }.isFailure)
        assertEquals(0, radio.writes)
    }
    @Test fun malformedDeviceRequestsAndUnsupportedDeclarationsFailClosed() {
        for (params in listOf(JSONObject().put("characteristic_id", "x").put("hex", "f"), JSONObject().put("characteristic_id", "x").put("hex", "00".repeat(21)), JSONObject().put("characteristic_id", "x").put("hex", "00").put("workspace_id", "other"))) {
            assertTrue(runCatching { WorkspaceBleContract.validate("ble.write", params) }.isFailure)
        }
        val bundle = WorkspaceApplicationContract.example()
        bundle.getJSONObject("manifest").put("capabilities", JSONArray().put(WorkspaceBleContract.descriptor().getJSONObject("manifest_example")))
        assertEquals(WorkspaceBleContract.operations, WorkspaceBleContract.requested(bundle.getJSONObject("manifest")))
        bundle.getJSONObject("manifest").getJSONArray("capabilities").getJSONObject(0).put("resources", JSONArray().put("all_devices"))
        assertTrue(runCatching { WorkspaceBleContract.requested(bundle.getJSONObject("manifest")) }.isFailure)
    }
}
