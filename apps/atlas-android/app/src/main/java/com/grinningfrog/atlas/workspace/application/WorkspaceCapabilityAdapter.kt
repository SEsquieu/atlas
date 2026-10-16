package com.grinningfrog.atlas.workspace.application

import org.json.JSONObject

/** The broker owns authority, receipts and lifecycle; adapters own only their platform protocol. */
internal interface WorkspaceCapabilityAdapter {
    val simulated: Boolean get() = false
    val displayName: String get() = name
    val name: String
    val namespace: String
    val requested: Set<String>
    val failureCode: String get() = "CAPABILITY_FAILED"
    val timeoutCode: String get() = "OPERATION_TIMEOUT"
    val busyCode: String get() = "CAPABILITY_BUSY"
    val errors: Set<String> get() = setOf("WORKSPACE_GRANT_REQUIRED","GRANT_REVOKED","OS_PERMISSION_REQUIRED","PLATFORM_DISABLED","DEVICE_UNAVAILABLE","OPERATION_UNAVAILABLE","EXECUTION_MODE_DENIED","REVISION_CONFLICT","UNSUPPORTED_CAPABILITY","QUOTA_EXCEEDED")
    fun grantState(access: WorkspaceCapabilityAccess): JSONObject = JSONObject()
    val permissions: Array<String>
    fun validate(method: String, params: JSONObject)
    fun permitted(): Boolean
    fun status(): JSONObject
    fun check(operation: String, access: WorkspaceCapabilityAccess)
    suspend fun execute(operation: String, params: JSONObject, access: WorkspaceCapabilityAccess): JSONObject
    fun release()
}

internal interface WorkspaceCapabilityAccess {
    fun approved(resource: String): Boolean
    fun requireGrant(resource: String)
    suspend fun authorize(resource: String, description: String, ephemeral: Boolean = false)
    fun phase(value: String)
}

/** Resource handles are disposable. Foreground consent survives ordinary device failures. */
internal class WorkspaceCapabilityGrants {
    var requestsEnabled = true
    private val grants = mutableMapOf<Pair<String,String>,Boolean>()
    fun hasAny(capability: String) = grants.keys.any { it.first == capability }
    fun has(capability: String, resource: String) = grants.containsKey(capability to resource)
    fun add(capability: String, resource: String, ephemeral: Boolean) { require(grants.size < 128 || has(capability, resource)) { "QUOTA_EXCEEDED" }; grants[capability to resource] = ephemeral }
    fun releaseHandles(capability: String) { grants.entries.removeAll { it.key.first == capability && it.value } }
    fun revoke(capability: String? = null) { if (capability == null) grants.clear() else grants.keys.removeAll { it.first == capability } }
}

internal class WorkspaceBleCapability(
    override val requested: Set<String>, private val radio: WorkspaceBleAdapter,
    override val permissions: Array<String> = emptyArray(),
    override val simulated: Boolean = false,
) : WorkspaceCapabilityAdapter {
    override val displayName="Bluetooth LE"
    override val failureCode = "BLE_FAILED"
    override val timeoutCode = "BLE_TIMEOUT"
    override val busyCode = "BLE_BUSY"
    override val errors = WorkspaceBleContract.errors
    override fun grantState(access: WorkspaceCapabilityAccess) = JSONObject().put("scan_approved",scanApproved(access)).put("device_approved",deviceApproved(access))
    override val name = WorkspaceBleContract.CAPABILITY
    override val namespace = "ble"
    private var device: String? = null
    override fun validate(method: String, params: JSONObject) = WorkspaceBleContract.validate(method, params)
    override fun permitted() = radio.permitted()
    override fun status() = radio.status()
    override fun check(operation: String, access: WorkspaceCapabilityAccess) {
        val permission = if (operation == "events") "subscribe" else operation
        require(permission in requested) { "WORKSPACE_GRANT_REQUIRED" }
        if (operation !in setOf("scan", "connect")) { require(permitted()) { "OS_PERMISSION_REQUIRED" }; access.requireGrant("device:${device ?: error("DEVICE_UNAVAILABLE")}") }
    }
    suspend override fun execute(operation: String, params: JSONObject, access: WorkspaceCapabilityAccess): JSONObject = when(operation) {
        "scan" -> {
            access.authorize("nearby_devices", "Allow this workspace to use Bluetooth LE while Atlas remains in the foreground? Nearby device names and services will be visible. Repeated scans and returning to this workspace reuse this approval; access to a selected peripheral is approved separately.")
            JSONObject().put("devices", radio.scan())
        }
        "connect" -> {
            access.requireGrant("nearby_devices")
            val id=params.getString("device_id"); val label=radio.deviceLabel(id)
            access.authorize("device:$id", "Connect to $label? Requested operations: ${requested.sorted().joinToString()}. Writes can change the peripheral's state.", ephemeral=true)
            radio.connect(id).also { device=id }
        }
        "services" -> JSONObject().put("services",radio.services())
        "events" -> radio.events()
        "read" -> radio.read(params.getString("characteristic_id"))
        "write" -> radio.write(params.getString("characteristic_id"),params.getString("hex"))
        "subscribe" -> radio.subscribe(params.getString("characteristic_id"),params.getBoolean("enabled"))
        else -> error("UNSUPPORTED_CAPABILITY")
    }
    fun scanApproved(access: WorkspaceCapabilityAccess) = access.approved("nearby_devices")
    fun deviceApproved(access: WorkspaceCapabilityAccess) = device?.let { access.approved("device:$it") } ?: false
    override fun release() { device=null; radio.close() }
}

/** Installed definitions are discoverable independently of grants or hardware availability. */
object WorkspaceCapabilityRegistry {
    private data class Definition(val descriptor: ()->JSONObject, val request: (JSONObject)->Set<String>, val factory: (android.content.Context,Set<String>)->WorkspaceCapabilityAdapter)
    private val definitions=mapOf(WorkspaceBleContract.CAPABILITY to Definition(WorkspaceBleContract::descriptor,
        { cap -> WorkspaceBleContract.requested(JSONObject().put("capabilities",org.json.JSONArray().put(cap))) },
        { context, operations -> WorkspaceBleCapability(operations,AndroidWorkspaceBle(context.applicationContext),AndroidWorkspaceBle.permissions()) }))
    fun describe(): JSONObject = JSONObject().put("registry_version",1).put("authorization_granted",false)
        .put("capabilities",org.json.JSONArray(definitions.values.map { it.descriptor() }))
        .put("lifecycle","Foreground bindings, native workspace/resource consent and Android permissions are independent. Operation failures release device handles without revoking foreground consent. Background, revision change, deletion and explicit revoke end authority. Operation receipts and audit records never grant access.")
    fun methods(bindings: Set<String>): JSONObject = JSONObject().apply {
        for(name in bindings) definitions[name]?.descriptor()?.getJSONObject("methods")?.let { methods -> for(key in methods.keys())put(key,methods.getJSONObject(key)) }
    }
    fun requested(manifest: JSONObject): Map<String,Set<String>> {
        val result=linkedMapOf<String,Set<String>>();val caps=manifest.getJSONArray("capabilities")
        for(i in 0 until caps.length()) {
            val cap=caps.getJSONObject(i);val name=cap.getString("name")
            require(!result.containsKey(name)) { "UNSUPPORTED_CAPABILITY" }
            result[name]=(definitions[name] ?: error("UNSUPPORTED_CAPABILITY")).request(cap)
        }
        return result
    }
    internal fun create(context: android.content.Context, requests: Map<String,Set<String>>): List<WorkspaceCapabilityAdapter> = requests.map { (name, operations) ->
        (definitions[name] ?: error("UNSUPPORTED_CAPABILITY")).factory(context,operations)
    }
}
