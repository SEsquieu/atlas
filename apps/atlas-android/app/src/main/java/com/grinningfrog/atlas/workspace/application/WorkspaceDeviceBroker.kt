package com.grinningfrog.atlas.workspace.application

import kotlinx.coroutines.*
import org.json.JSONObject

/** Per-presentation, ephemeral grants. No package can choose another workspace or an Android address. */
class WorkspaceDeviceBroker internal constructor(
    private val principal: WorkspacePrincipal,
    private val requested: Set<String>,
    private val current: () -> Boolean,
    private val adapter: WorkspaceBleAdapter,
    private val consent: suspend (String) -> Boolean,
    private val permission: suspend () -> Boolean,
    private val scope: CoroutineScope,
    private val changed: () -> Unit = {},
) {
    private var generation = 0L
    private var scanApproved = false
    private var deviceApproved = false
    private var active = true
    private var busy = false
    private var requestsEnabled = true
    private var job: Job? = null
    private val receipts = linkedMapOf<String, JSONObject>()
    private fun fence() {
        require(active && current()) { "REVISION_CONFLICT" }
        require(principal.mode == WorkspaceInstanceMode.FOREGROUND) { "EXECUTION_MODE_DENIED" }
        require(requested.isNotEmpty()) { "UNSUPPORTED_CAPABILITY" }
    }
    private fun granted() {
        fence()
        require(adapter.permitted()) { "OS_PERMISSION_REQUIRED" }
        require(deviceApproved) { "WORKSPACE_GRANT_REQUIRED" }
    }
    fun status(): JSONObject = adapter.status().put("scan_approved", scanApproved).put("device_approved", deviceApproved)
        .put("requests_enabled", requestsEnabled).put("busy", busy).put("generation", generation).put("foreground", active && current())
    fun dispatch(method: String, params: JSONObject): JSONObject {
        refreshAuthority()
        WorkspaceBleContract.validate(method, params)
        fence()
        when (method) {
            "ble.status" -> return status()
            "ble.revoke" -> { revoke(); return status() }
            "ble.operation" -> return JSONObject((receipts[params.getString("operation_id")] ?: error("OPERATION_UNAVAILABLE")).toString())
            "ble.disconnect" -> { revoke(); return status() }
            "ble.events" -> { granted(); require("subscribe" in requested) { "WORKSPACE_GRANT_REQUIRED" }; return adapter.events() }
            "ble.services" -> { granted(); require("services" in requested) { "WORKSPACE_GRANT_REQUIRED" }; return JSONObject().put("services", adapter.services()) }
        }
        val operation = method.removePrefix("ble.")
        require(requestsEnabled) { "WORKSPACE_GRANT_REQUIRED" }
        require(operation in requested) { "WORKSPACE_GRANT_REQUIRED" }
        require(!busy) { "BLE_BUSY" }
        if (receipts.size >= 32) receipts.remove(receipts.keys.first())
        val id = WorkspaceBleContract.id()
        val receipt = JSONObject().put("operation_id", id).put("status", "pending").put("method", method).put("started_at_ms", System.currentTimeMillis())
        receipts[id] = receipt
        val token = generation
        busy = true
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = withTimeout(45_000) {
                    when (operation) {
                        "scan" -> {
                            if (!scanApproved) {
                                receipt.put("status", "waiting_user")
                                require(approve("Allow this workspace to scan nearby Bluetooth LE devices? Device names and advertised services will be visible to this app.")) { "WORKSPACE_GRANT_REQUIRED" }
                                require(permission()) { "OS_PERMISSION_REQUIRED" }
                                fence(); require(token == generation) { "GRANT_REVOKED" }
                                scanApproved = true
                            }
                            require(adapter.permitted()) { "OS_PERMISSION_REQUIRED" }
                            receipt.put("status", "pending")
                            JSONObject().put("devices", adapter.scan())
                        }
                        "connect" -> {
                            require(scanApproved) { "WORKSPACE_GRANT_REQUIRED" }
                            require(adapter.permitted()) { "OS_PERMISSION_REQUIRED" }
                            val device = params.getString("device_id")
                            val label = adapter.deviceLabel(device)
                            receipt.put("status", "waiting_user")
                            require(approve("Connect to $label? This workspace requests: ${requested.sorted().joinToString()}. Access lasts only while this workspace is open. Writes can change the peripheral's state.")) { "WORKSPACE_GRANT_REQUIRED" }
                            fence(); require(token == generation) { "GRANT_REVOKED" }
                            require(adapter.permitted()) { "OS_PERMISSION_REQUIRED" }
                            receipt.put("status", "pending")
                            adapter.connect(device).also { deviceApproved = true }
                        }
                        "read" -> { granted(); adapter.read(params.getString("characteristic_id")) }
                        "write" -> { granted(); adapter.write(params.getString("characteristic_id"), params.getString("hex")) }
                        "subscribe" -> { granted(); adapter.subscribe(params.getString("characteristic_id"), params.getBoolean("enabled")) }
                        else -> error("UNSUPPORTED_CAPABILITY")
                    }
                }
                fence(); require(token == generation) { "GRANT_REVOKED" }
                require(adapter.permitted()) { "OS_PERMISSION_REQUIRED" }
                receipt.put("status", "succeeded").put("result", result)
            } catch (failure: Exception) {
                val code = when (failure) {
                    is TimeoutCancellationException -> "BLE_TIMEOUT"
                    is CancellationException -> "GRANT_REVOKED"
                    is SecurityException -> "OS_PERMISSION_REQUIRED"
                    else -> failure.message?.takeIf { it in WorkspaceBleContract.errors } ?: "BLE_FAILED"
                }
                receipt.remove("result")
                receipt.put("status", "failed").put("error", JSONObject().put("code", code).put("summary", code).put("retry", if (operation == "write") "inspect_device_before_retry" else "explicit_user_action"))
                // A timeout may have reached the peripheral. Close rather than matching a late callback to a new request.
                if (code in setOf("BLE_TIMEOUT", "OS_PERMISSION_REQUIRED", "BLE_FAILED")) { adapter.close(); deviceApproved = false; scanApproved = false }
            } finally {
                receipt.put("finished_at_ms", System.currentTimeMillis())
                if (generation == token) busy = false
                changed()
            }
        }
        job!!.start()
        changed()
        return JSONObject().put("operation_id", id).put("status", receipt.getString("status"))
    }
    fun refreshAuthority() {
        if ((scanApproved || deviceApproved || busy) && (!current() || !adapter.permitted() && (scanApproved || deviceApproved))) revoke()
    }
    private suspend fun approve(message: String): Boolean = consent(message).also { if (!it) requestsEnabled = false }
    fun enableRequests() { requestsEnabled = true; changed() }
    fun revoke(blockRequests: Boolean = false) {
        if (blockRequests) requestsEnabled = false
        generation++; job?.cancel(); job = null
        adapter.close(); deviceApproved = false; scanApproved = false; busy = false
        receipts.values.filter { it.optString("status") in setOf("pending", "waiting_user", "succeeded") }.forEach {
            it.remove("result"); it.put("status", "failed").put("error", JSONObject().put("code", "GRANT_REVOKED").put("summary", "Access revoked"))
        }
        changed()
    }
    fun close() { active = false; revoke() }
}
