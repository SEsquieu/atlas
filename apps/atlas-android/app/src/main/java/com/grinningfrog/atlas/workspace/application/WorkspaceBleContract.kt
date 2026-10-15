package com.grinningfrog.atlas.workspace.application

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Device access is a declared request, never an activation-time grant. */
object WorkspaceBleContract {
    const val CAPABILITY = "bluetooth.le"
    const val RESOURCE = "user_selected_device"
    val operations = setOf("scan", "connect", "services", "read", "write", "subscribe")
    val errors = setOf("WORKSPACE_GRANT_REQUIRED", "GRANT_REVOKED", "OS_PERMISSION_REQUIRED", "PLATFORM_DISABLED", "DEVICE_UNAVAILABLE", "BLE_BUSY", "BLE_FAILED", "BLE_TIMEOUT", "OPERATION_UNAVAILABLE", "EXECUTION_MODE_DENIED", "REVISION_CONFLICT", "UNSUPPORTED_CAPABILITY", "QUOTA_EXCEEDED")
    fun requested(manifest: JSONObject): Set<String> {
        val caps = manifest.getJSONArray("capabilities")
        val requested = mutableSetOf<String>()
        for (i in 0 until caps.length()) {
            val cap = caps.getJSONObject(i)
            require(cap.getString("name") == CAPABILITY) { "UNSUPPORTED_CAPABILITY" }
            val resources = cap.getJSONArray("resources")
            require(resources.length() == 1 && resources.getString(0) == RESOURCE) { "UNSUPPORTED_CAPABILITY" }
            val ops = cap.getJSONArray("operations")
            for (j in 0 until ops.length()) {
                val op = ops.getString(j)
                require(op in operations && requested.add(op)) { "UNSUPPORTED_CAPABILITY" }
            }
        }
        if (requested.any { it != "scan" }) require(requested.containsAll(setOf("scan", "connect"))) { "BLE connection operations require scan and connect declarations" }
        if (requested.any { it in setOf("read", "write", "subscribe") }) require("services" in requested) { "BLE characteristic operations require services declaration" }
        return requested
    }
    fun validate(method: String, params: JSONObject) {
        val required = when (method) {
            "ble.status", "ble.scan", "ble.services", "ble.events", "ble.disconnect", "ble.revoke" -> emptySet()
            "ble.operation" -> setOf("operation_id")
            "ble.connect" -> setOf("device_id")
            "ble.read" -> setOf("characteristic_id")
            "ble.write" -> setOf("characteristic_id", "hex")
            "ble.subscribe" -> setOf("characteristic_id", "enabled")
            else -> error("UNSUPPORTED_CAPABILITY")
        }
        WorkspaceApplicationContract.checkJson(params)
        WorkspaceApplicationContract.exactKeys(params, required, required)
        for (key in required - setOf("hex", "enabled")) require(params.get(key) is String && params.getString(key).length in 1..128)
        if (method == "ble.write") require(params.get("hex") is String && params.getString("hex").matches(Regex("(?:[0-9a-fA-F]{2}){0,20}"))) { "Write requires at most 20 bytes of hexadecimal data" }
        if (method == "ble.subscribe") require(params.get("enabled") is Boolean)
    }
    fun methodSchemas(): JSONObject = JSONObject().apply {
        for (method in listOf("status", "scan", "services", "events", "disconnect", "revoke", "operation", "connect", "read", "write", "subscribe")) {
            val keys = when (method) {
                "operation" -> listOf("operation_id")
                "connect" -> listOf("device_id")
                "read" -> listOf("characteristic_id")
                "write" -> listOf("characteristic_id", "hex")
                "subscribe" -> listOf("characteristic_id", "enabled")
                else -> emptyList()
            }
            val properties = JSONObject()
            for (key in keys) properties.put(key, if (key == "enabled") JSONObject().put("type", "boolean") else JSONObject().put("type", "string").put("maxLength", if (key == "hex") 40 else 128))
            put("ble.$method", JSONObject().put("type", "object").put("properties", properties).put("required", JSONArray(keys)).put("additionalProperties", false))
        }
    }
    fun descriptor(): JSONObject = JSONObject()
        .put("name", CAPABILITY).put("operations", JSONArray(operations.sorted())).put("resources", JSONArray(listOf(RESOURCE)))
        .put("manifest_example", JSONObject().put("name", CAPABILITY).put("operations", JSONArray(operations.sorted())).put("resources", JSONArray(listOf(RESOURCE))))
        .put("contract", "Import ble from /atlas/host.js. Declare only needed operations; connect requires scan, and characteristic access also requires connect and services. Foreground only; native scan approval, Android permission, and native selected-device approval are separate gates. Activation/preview never grants access. One connection, 5-second scans, at most 64 devices and 128 characteristic handles. services() returns service UUIDs and opaque characteristic_id handles; use those handles in read/write/subscribe. Writes use hex (0..20 bytes), acknowledged GATT writes only, no automatic retry. Notifications use events() (drains at most 32, dropped count explicit). Foreground scan consent is reused across scans, ordinary BLE failures and disconnect. Disconnect/failure invalidate device handles and selected-device approval. Leaving the workspace, backgrounding, revision change, permission loss or explicit revoke end foreground consent. Permission prompts may take 45 seconds. Expose errors and reconnect controls. Never use navigator.bluetooth or simulate hardware success.")
        .put("sdk", JSONObject().put("scan()", "{devices:[{device_id,name,rssi,service_uuids}]}")
            .put("connect(device_id)", "{connected,name}; native approval required")
            .put("services()", "{services:[{uuid,characteristics:[{characteristic_id,uuid,properties,readable,writable,notifiable}]}]}")
            .put("read(characteristic_id)", "{hex,truncated,gatt_status}").put("write(characteristic_id,hex)", "{acknowledged,gatt_status}; hex is 0..20 bytes")
            .put("subscribe(characteristic_id,enabled=true)", "{enabled,gatt_status}").put("events()", "{events:[{characteristic_id,hex,truncated,received_at_ms}],dropped}")
            .put("disconnect()/revoke()", "Closes hardware and invalidates all handles; returns status")
            .put("status()", "{installed,os_permission_granted,platform_enabled,connected,device_label,scan_approved,device_approved,requests_enabled,busy,generation,recent_operations,last_failure}"))
        .put("subscriptions", "Use await ble.watch(characteristic_id,onEvent,onError) for a managed notification pump; it returns an async stop function. host.observe(eventMethod,params,onBatch,{onError,intervalMs}) multiplexes bounded event polling. Manual subscribe requires repeated ble.events() draining; subscribe alone does not deliver samples. Stop watchers on disconnect/revoke, clear stale selections after scans, and expose last_failure.phase/platform_code.")
        .put("startup", "Call host.describe() then install UI and signal host.ready(). ble_available=false in preview: skip BLE there. Start scan/connect from explicit UI controls after readiness, never at module startup. Catch errors using error.code and error.receipt; show uncertainty for failed/timed-out writes.")
        .put("methods", methodSchemas())
    fun id(): String = UUID.randomUUID().toString()
}

/** Platform interface also used by deterministic permission/epoch tests. All calls run on main. */
internal interface WorkspaceBleAdapter {
    fun status(): JSONObject
    fun permitted(): Boolean
    suspend fun scan(): JSONArray
    fun deviceLabel(id: String): String
    suspend fun connect(id: String): JSONObject
    fun services(): JSONArray
    suspend fun read(id: String): JSONObject
    suspend fun write(id: String, hex: String): JSONObject
    suspend fun subscribe(id: String, enabled: Boolean): JSONObject
    fun events(): JSONObject
    fun close()
}
