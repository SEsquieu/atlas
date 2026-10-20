package com.grinningfrog.atlas.workspace.application

import kotlinx.coroutines.*
import org.json.JSONObject

/** Per-presentation capability runtime. Requests cannot supply principals, grants or Android handles. */
class WorkspaceDeviceBroker internal constructor(
    private val principal: WorkspacePrincipal,
    private val adapters: List<WorkspaceCapabilityAdapter>,
    private val current: () -> Boolean,
    private val consent: suspend (String) -> Boolean,
    private val permission: suspend (WorkspaceCapabilityAdapter) -> Boolean,
    private val scope: CoroutineScope,
    private val changed: () -> Unit = {},
    private val audit: (JSONObject) -> Unit = {},
    val isSimulation: Boolean = false,
    private val grants: WorkspaceCapabilityGrants = WorkspaceCapabilityGrants(),
) {
    internal constructor(principal: WorkspacePrincipal, requested: Set<String>, current: () -> Boolean, adapter: WorkspaceBleAdapter,
        consent: suspend (String)->Boolean, permission: suspend ()->Boolean, scope: CoroutineScope, changed: ()->Unit = {}, audit: (JSONObject)->Unit = {}) :
        this(principal,listOf(WorkspaceBleCapability(requested,adapter)),current,consent,{permission()},scope,changed,audit)
    val errorCodes: Set<String> get() = adapters.flatMap{it.errors+setOf(it.busyCode,it.failureCode,it.timeoutCode)}.toSet()
    val capabilityNames: Set<String> get() = adapters.map { it.name }.toSet()
    private var generation=0L
    private var active=true
    private var busy=false
    private var requestsEnabled: Boolean
        get() = grants.requestsEnabled
        set(value) { grants.requestsEnabled = value }
    private var job: Job?=null
    private val receipts=linkedMapOf<String,JSONObject>()
    private var auditSequence=0L
    init { require(!isSimulation || principal.mode==WorkspaceInstanceMode.TEST && adapters.all { it.simulated }); require(adapters.map { it.namespace }.distinct().size==adapters.size); event("instance_created") }
    private fun event(kind: String, adapter: WorkspaceCapabilityAdapter?=null, details: JSONObject=JSONObject()) {
        runCatching { audit(JSONObject().put("kind",kind).put("at_ms",System.currentTimeMillis()).put("instance_id",principal.instanceId)
            .put("revision_id",principal.revisionId).put("epoch",principal.epoch).put("generation",generation).put("sequence",++auditSequence)
            .put("capability",adapter?.name ?: JSONObject.NULL).put("details",details)) }
    }
    fun handles(method: String) = adapters.any { method.startsWith(it.namespace+".") }
    private fun fence() {
        require(active && current()) { "REVISION_CONFLICT" }
        require(principal.mode==WorkspaceInstanceMode.FOREGROUND || isSimulation && principal.mode==WorkspaceInstanceMode.TEST) { "EXECUTION_MODE_DENIED" }
    }
    private fun access(adapter: WorkspaceCapabilityAdapter, receipt: JSONObject?=null, token: Long=generation) = object : WorkspaceCapabilityAccess {
        override fun approved(resource: String) = grants.has(adapter.name,resource)
        override fun requireGrant(resource: String) { fence(); require(approved(resource)) { "WORKSPACE_GRANT_REQUIRED" } }
        override fun phase(value: String) { receipt?.put("status",value); event("operation_phase",adapter,JSONObject().put("operation_id",receipt?.optString("operation_id")).put("phase",value)); changed() }
        override suspend fun authorize(resource: String, description: String, ephemeral: Boolean) {
            fence(); require(requestsEnabled) { "WORKSPACE_GRANT_REQUIRED" }
            if(!approved(resource)) {
                phase("waiting_user"); event("grant_requested",adapter,JSONObject().put("resource",resource))
                if(!consent(description)) { requestsEnabled=false; reset("consent_denied",true,false); event("grant_denied",adapter); error("WORKSPACE_GRANT_REQUIRED") }
                fence(); require(generation==token) { "GRANT_REVOKED" }
                if(!adapter.permitted()) { phase("waiting_user"); event("os_permission_requested",adapter); require(permission(adapter)) { "OS_PERMISSION_REQUIRED" } }
                fence(); require(generation==token) { "GRANT_REVOKED" }; require(adapter.permitted()) { "OS_PERMISSION_REQUIRED" }
                grants.add(adapter.name,resource,ephemeral); event("grant_created",adapter,JSONObject().put("resource",resource).put("scope",if(ephemeral)"handle" else "foreground"))
            } else { require(adapter.permitted()) { "OS_PERMISSION_REQUIRED" }; event("grant_reused",adapter,JSONObject().put("resource",resource)) }
            phase("pending")
        }
    }
    fun status(): JSONObject {
        val states=JSONObject()
        for(adapter in adapters) {
            val state=adapter.status()
            val grantState=adapter.grantState(access(adapter)); for(key in grantState.keys())state.put(key,grantState.get(key))
            states.put(adapter.name,state)
        }
        // Keep the existing BLE SDK status fields compatible while exposing a common catalogue.
        val result=if(adapters.size==1) JSONObject(states.getJSONObject(adapters.first().name).toString()) else JSONObject()
        return result.put("capability_label",adapters.joinToString(" / "){it.displayName}).put("capabilities",states).put("requests_enabled",requestsEnabled).put("busy",busy).put("generation",generation)
            .put("instance_id",principal.instanceId).put("foreground",active && current())
            .put("recent_operations",org.json.JSONArray(receipts.values.toList().takeLast(8).map { summary(it) }))
    }
    private fun summary(receipt: JSONObject) = JSONObject().put("operation_id",receipt.getString("operation_id")).put("method",receipt.getString("method"))
        .put("status",receipt.getString("status")).put("started_at_ms",receipt.getLong("started_at_ms"))
        .put("finished_at_ms",receipt.opt("finished_at_ms") ?: JSONObject.NULL).put("error_code",receipt.optJSONObject("error")?.optString("code") ?: JSONObject.NULL)
    fun dispatch(method: String, params: JSONObject): JSONObject = try { dispatchChecked(method,params) } catch(failure: Exception) {
        val adapter=adapters.singleOrNull{method.startsWith(it.namespace+".")}
        val code=failure.message?.takeIf{it in (adapter?.let{it.errors+setOf(it.busyCode,it.failureCode,it.timeoutCode)} ?: emptySet())} ?: "VALIDATION_FAILED"
        event("request_rejected",adapter,JSONObject().put("method",method.take(64)).put("code",code))
        throw failure
    }
    private fun dispatchChecked(method: String, params: JSONObject): JSONObject {
        refreshAuthority(); fence()
        val adapter=adapters.singleOrNull { method.startsWith(it.namespace+".") } ?: error("UNSUPPORTED_CAPABILITY")
        require(adapter.requested.isNotEmpty()) { "UNSUPPORTED_CAPABILITY" }
        adapter.validate(method,params)
        val operation=method.substringAfter('.')
        when(operation) {
            "status" -> return status()
            "revoke" -> { revoke(blockRequests=true); return status() }
            "disconnect" -> { reset("disconnect",false,true); return status() }
            "operation" -> {
                val receipt=receipts[params.getString("operation_id")] ?: error("OPERATION_UNAVAILABLE")
                require(receipt.getString("method").startsWith(adapter.namespace+".")) { "OPERATION_UNAVAILABLE" }
                return JSONObject(receipt.toString())
            }
        }
        require(requestsEnabled) { "WORKSPACE_GRANT_REQUIRED" }
        adapter.check(operation,access(adapter))
        require(!busy) { adapter.busyCode }
        if(receipts.size>=32)receipts.remove(receipts.keys.first())
        val id=java.util.UUID.randomUUID().toString()
        val receipt=JSONObject().put("operation_id",id).put("status","pending").put("method",method).put("started_at_ms",System.currentTimeMillis())
        receipts[id]=receipt; val token=generation; busy=true
        event("operation_started",adapter,summary(receipt))
        job=scope.launch(start=CoroutineStart.LAZY) {
            try {
                val result=withTimeout(45_000) { adapter.execute(operation,params,access(adapter,receipt,token)) }
                fence(); require(token==generation) { "GRANT_REVOKED" }; require(adapter.permitted()) { "OS_PERMISSION_REQUIRED" }
                receipt.put("status","succeeded").put("result",result)
                // Record counts and platform codes, never peripheral names, addresses or characteristic bytes.
                val evidence=JSONObject().put("operation_id",id).put("method",method)
                for(key in listOf("gatt_status","acknowledged","enabled","dropped","truncated")) if(result.has(key)) evidence.put(key,result.get(key))
                for(key in listOf("devices","services","events")) result.optJSONArray(key)?.let { evidence.put(key+"_count",it.length()) }
                event("operation_succeeded",adapter,evidence)
            } catch(failure: Exception) {
                val code=when(failure) { is TimeoutCancellationException->adapter.timeoutCode; is CancellationException->"GRANT_REVOKED"; is SecurityException->"OS_PERMISSION_REQUIRED"; else->failure.message?.takeIf { it in adapter.errors } ?: adapter.failureCode }
                receipt.remove("result"); receipt.put("status","failed").put("error",JSONObject().put("code",code).put("summary",code).put("retry",if(operation=="write")"inspect_device_before_retry" else "explicit_user_action"))
                event("operation_failed",adapter,summary(receipt).put("platform",runCatching { adapter.status().optJSONObject("last_failure") }.getOrNull() ?: JSONObject.NULL))
                if(generation==token) {
                    if(code=="OS_PERMISSION_REQUIRED") { grants.revoke(adapter.name); requestsEnabled=false; event("grants_revoked",adapter,JSONObject().put("reason","os_permission_lost")) }
                    if(code in setOf(adapter.timeoutCode,adapter.failureCode,"DEVICE_UNAVAILABLE","PLATFORM_DISABLED","OS_PERMISSION_REQUIRED","REVISION_CONFLICT","GRANT_REVOKED")) {
                        adapter.release(); grants.releaseHandles(adapter.name)
                        event("handles_released",adapter,JSONObject().put("reason",code).put("foreground_consent_retained",code !in setOf("OS_PERMISSION_REQUIRED","REVISION_CONFLICT","GRANT_REVOKED")))
                        if(code in setOf("REVISION_CONFLICT","GRANT_REVOKED"))grants.revoke(adapter.name)
                    }
                }
            } finally { receipt.put("finished_at_ms",System.currentTimeMillis()); if(generation==token)busy=false; changed() }
        }
        job!!.start(); changed()
        return JSONObject().put("operation_id",id).put("status",receipt.getString("status"))
    }
    fun refreshAuthority() {
        if(!active)return
        if(!current()) { if(busy || adapters.any { grants.hasAny(it.name) })reset("revision_or_foreground_changed",true,true); return }
        for(adapter in adapters) if(!adapter.permitted() && grants.hasAny(adapter.name)) {
            reset("os_permission_lost",true,true); requestsEnabled=false; return
        }
    }
    fun enableRequests() { requestsEnabled=true; event("requests_enabled"); changed() }
    private fun reset(reason: String, revokeConsent: Boolean, cancel: Boolean) {
        if(cancel) { generation++; job?.cancel(); job=null; busy=false }
        for(adapter in adapters) { adapter.release(); if(revokeConsent)grants.revoke(adapter.name) else grants.releaseHandles(adapter.name) }
        if(cancel)receipts.values.filter { it.optString("status") in setOf("pending","waiting_user") }.forEach {
            it.remove("result");it.put("status","failed").put("error",JSONObject().put("code","GRANT_REVOKED").put("summary",reason))
        }
        event(if(revokeConsent)"grants_revoked" else "handles_released",details=JSONObject().put("reason",reason).put("foreground_consent_retained",!revokeConsent)); changed()
    }
    fun revoke(blockRequests: Boolean=false, reason: String="explicit_revoke") { if(blockRequests)requestsEnabled=false; reset(reason,true,true) }
    fun close(preserveConsent: Boolean = false) { if(!active)return; active=false; reset("instance_disposed",!preserveConsent,true) }
}
