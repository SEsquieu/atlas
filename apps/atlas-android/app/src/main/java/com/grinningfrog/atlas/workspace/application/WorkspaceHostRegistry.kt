package com.grinningfrog.atlas.workspace.application

import org.json.JSONArray
import org.json.JSONObject

/** Canonical initial registry. Later broker families extend this, not a parallel prompt-only API. */
object WorkspaceHostRegistry {
    private val emptySchema = JSONObject("""{"type":"object","properties":{},"required":[],"additionalProperties":false}""")
    private val operationSchema = JSONObject("""{"type":"object","properties":{"operation_id":{"type":"string","maxLength":128}},"required":["operation_id"],"additionalProperties":false}""")
    private val methods = linkedMapOf("host.describe" to emptySchema, "host.ready" to emptySchema)
    private val storageMethods = linkedMapOf(
        "data.query" to JSONObject("""{"type":"object","properties":{"collection":{"type":"string","maxLength":64},"limit":{"type":"integer"},"offset":{"type":"integer"},"expected_data_version":{"type":"integer"}},"required":["collection"],"additionalProperties":false}"""),
        "data.prepare" to JSONObject("""{"type":"object","properties":{"expected_data_version":{"type":"integer"},"request_key":{"type":"string","maxLength":128},"mutations":{"type":"array","maxItems":32,"items":{"type":"object","description":"create: kind,collection,data; update: kind,collection,record_id,data; delete: kind,collection,record_id. data is a strict JSON object; unknown mutation fields are rejected."}}},"required":["expected_data_version","mutations"],"additionalProperties":false}"""),
        "data.commit" to operationSchema, "data.inspect" to operationSchema, "data.cancel" to operationSchema,
    )
    fun describe(principal: WorkspacePrincipal, storage: Boolean = false): JSONObject = WorkspaceApplicationContract.descriptor()
        .put("instance_mode", principal.mode.name.lowercase()).put("workspace_id", principal.workspaceId).put("revision_id", principal.revisionId)
        .put("epoch", principal.epoch).put("methods", JSONObject().apply { (methods + if (storage) storageMethods else emptyMap()).forEach { (name, schema) -> put(name, JSONObject(schema.toString())) } })
        .put("authority", "core_bound_transport").put("authorization_granted", storage)
        .put("storage_contract", "Workspace-scoped foreground records only. query returns data_version and a next_offset; null ends pagination. prepare requires expected_data_version, mutations and a reusable request_key. Retry the same key with identical input after response loss; commit/inspect/cancel use the returned operation_id. Revisions fence old callers. No device, network, credential, or background methods are installed.")
    fun validate(method: String, params: JSONObject, storage: Boolean = false): Boolean {
        val schema = methods[method] ?: if (storage) storageMethods[method] else null
        if (schema == null) return false
        WorkspaceApplicationContract.checkJson(params)
        if (method == "data.prepare") {
            WorkspaceApplicationContract.exactKeys(params, setOf("expected_data_version", "request_key", "mutations"), setOf("expected_data_version", "mutations"))
            params.getJSONArray("mutations") // full closed mutation validation belongs to the transaction store
        } else WorkspaceJsonSchema.validate(schema, params)
        return true
    }
    fun dispatch(principal: WorkspacePrincipal, method: String, params: JSONObject, data: WorkspaceApplicationData?): JSONObject {
        val storage = data != null && principal.mode == WorkspaceInstanceMode.FOREGROUND
        require(validate(method, params, storage)) { "UNSUPPORTED_CAPABILITY" }
        if (method == "host.describe" || method == "host.ready") return describe(principal, storage)
        val store = requireNotNull(data)
        return when (method) {
            "data.query" -> store.query(principal, params)
            "data.prepare" -> store.prepare(principal, params)
            "data.commit" -> store.commit(principal, params.getString("operation_id"))
            "data.inspect" -> store.inspect(principal, params.getString("operation_id"))
            "data.cancel" -> store.cancel(principal, params.getString("operation_id"))
            else -> error("UNSUPPORTED_CAPABILITY")
        }
    }
    /** The SDK imports from a reserved local path; authors cannot replace this module. */
    fun sdk(): String = """
        const pending = new Map(); let next = 0;
        function strict(value, depth=0) {
          if (depth>32) throw Error('JSON nesting limit');
          if (value===null || typeof value==='string' || typeof value==='boolean') return;
          if (typeof value==='number' && Number.isFinite(value) && Math.abs(value)<=Number.MAX_SAFE_INTEGER) return;
          if (Array.isArray(value)) { for (let i=0;i<value.length;i++) strict(value[i],depth+1); return; }
          if (typeof value==='object' && Object.getPrototypeOf(value)===Object.prototype) { for(const key of Object.keys(value)) strict(value[key],depth+1); return; }
          throw Error('Not strict JSON');
        }
        atlasTransport.addEventListener('message', event => {
          const message=JSON.parse(event.data), item=pending.get(message.request_id);
          if(!item) return; pending.delete(message.request_id); clearTimeout(item.timer); item.resolve(message);
        });
        export async function request(method,params={}) {
          strict(params); if(pending.size>=32) throw Error('Host request quota');
          const id=String(++next), raw=JSON.stringify({api_version:1,request_id:id,method,params});
          if(new TextEncoder().encode(raw).length>65536) throw Error('Host message quota');
          return await new Promise((resolve,reject)=>{
            const timer=setTimeout(()=>{pending.delete(id);reject(Error('Host response timeout'));},6000);
            pending.set(id,{resolve,reject,timer});
            try { atlasTransport.postMessage(raw); } catch(error) {clearTimeout(timer);pending.delete(id);reject(error);}
          });
        }
        async function value(method,params={}) {
          const response=await request(method,params);
          if(response.status!=='succeeded') { const error=Error(response.error?.summary||'Host request failed'); error.code=response.error?.code; error.receipt=response; throw error; }
          return response.result;
        }
        export const host=Object.freeze({request,describe:()=>value('host.describe'),ready:()=>value('host.ready')});
        export const data=Object.freeze({query:params=>value('data.query',params),prepare:params=>value('data.prepare',params),commit:operation_id=>value('data.commit',{operation_id}),inspect:operation_id=>value('data.inspect',{operation_id}),cancel:operation_id=>value('data.cancel',{operation_id})});
    """.trimIndent()
}
