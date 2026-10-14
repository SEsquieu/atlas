package com.grinningfrog.atlas.workspace.application

import org.json.JSONObject

/** Constructed by Core from a bound transport, never deserialized from package messages. */
data class WorkspacePrincipal internal constructor(
    val realmId: String,
    val workspaceId: String,
    val revisionId: String,
    val instanceId: String,
    val epoch: Long,
    val mode: WorkspaceInstanceMode,
)

enum class WorkspaceInstanceMode { PREVIEW, TEST, FOREGROUND, PERSISTENT }

data class WorkspaceGrant(
    val realmId: String,
    val workspaceId: String,
    val capability: String,
    val operations: Set<String>,
    val resources: Set<String>,
    val modes: Set<WorkspaceInstanceMode>,
    val generation: Long,
    val revoked: Boolean = false,
)

data class WorkspaceAuthorityState(
    val adapterInstalled: Boolean,
    val platformEnabled: Boolean,
    val osPermissionGranted: Boolean,
    val activeRevisionId: String,
    val activeEpoch: Long,
    val active: Boolean,
    val grantGeneration: Long,
    val grant: WorkspaceGrant?,
)

/** Evaluate again on dispatch AND delivery. A successful earlier check is not a lease. */
object WorkspaceAuthority {
    fun denial(principal: WorkspacePrincipal, capability: String, operation: String, resource: String, state: WorkspaceAuthorityState): String? {
        if (!state.active || principal.revisionId != state.activeRevisionId || principal.epoch != state.activeEpoch) return "REVISION_CONFLICT"
        if (principal.mode == WorkspaceInstanceMode.PREVIEW || principal.mode == WorkspaceInstanceMode.TEST) return "EXECUTION_MODE_DENIED"
        if (!state.adapterInstalled) return "UNSUPPORTED_CAPABILITY"
        if (!state.platformEnabled) return "PLATFORM_DISABLED"
        if (!state.osPermissionGranted) return "OS_PERMISSION_REQUIRED"
        val grant = state.grant ?: return "WORKSPACE_GRANT_REQUIRED"
        if (grant.revoked || grant.generation != state.grantGeneration) return "GRANT_REVOKED"
        if (grant.realmId != principal.realmId || grant.workspaceId != principal.workspaceId || grant.capability != capability) return "WORKSPACE_GRANT_REQUIRED"
        if (principal.mode !in grant.modes || operation !in grant.operations || resource !in grant.resources) return "WORKSPACE_GRANT_REQUIRED"
        return null
    }

    /** Update compatibility requires subset containment, never a union of unrelated grants. */
    fun covers(grant: WorkspaceGrant, operations: Set<String>, resources: Set<String>, modes: Set<WorkspaceInstanceMode>): Boolean =
        !grant.revoked && grant.operations.containsAll(operations) && grant.resources.containsAll(resources) && grant.modes.containsAll(modes)
}

object WorkspaceHostProtocol {
    private val statuses = setOf("succeeded", "failed", "pending", "waiting_user")
    fun request(raw: String): JSONObject {
        require(raw.toByteArray(Charsets.UTF_8).size <= WorkspaceApplicationContract.MAX_MESSAGE_BYTES) { "QUOTA_EXCEEDED" }
        val value = WorkspaceApplicationContract.parseObject(raw)
        WorkspaceApplicationContract.checkJson(value)
        WorkspaceApplicationContract.exactKeys(value, setOf("api_version", "request_id", "method", "params"), setOf("api_version", "request_id", "method", "params"))
        require(value.get("api_version") is Number && value.getDouble("api_version") == 1.0) { "UNSUPPORTED_HOST_VERSION" }
        require(value.get("request_id") is String && value.getString("request_id").length in 1..128) { "Invalid request_id" }
        require(value.get("method") is String && value.getString("method").matches(Regex("[a-z][a-z0-9_.]{0,63}"))) { "Invalid method" }
        value.getJSONObject("params")
        return value
    }

    fun response(requestId: String, status: String, operationId: String, payload: JSONObject): JSONObject {
        require(status in statuses)
        WorkspaceApplicationContract.checkJson(payload)
        return JSONObject().put("api_version", 1).put("request_id", requestId).put("operation_id", operationId).put("status", status)
            .put(if (status == "failed") "error" else "result", payload)
    }

    fun failure(requestId: String, operationId: String, code: String, summary: String, retry: String = "never", details: JSONObject = JSONObject()): JSONObject =
        response(requestId, "failed", operationId, JSONObject().put("code", code).put("summary", summary).put("retry", retry).put("details", details))
}
