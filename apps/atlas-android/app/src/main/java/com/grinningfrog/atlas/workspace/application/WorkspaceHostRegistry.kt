package com.grinningfrog.atlas.workspace.application

import org.json.JSONArray
import org.json.JSONObject

/** Canonical initial registry. Later broker families extend this, not a parallel prompt-only API. */
object WorkspaceHostRegistry {
    private val emptySchema = JSONObject("""{"type":"object","properties":{},"required":[],"additionalProperties":false}""")
    private val methods = linkedMapOf("host.describe" to emptySchema, "host.ready" to emptySchema)
    fun describe(principal: WorkspacePrincipal): JSONObject = WorkspaceApplicationContract.descriptor()
        .put("instance_mode", principal.mode.name.lowercase()).put("workspace_id", principal.workspaceId).put("revision_id", principal.revisionId)
        .put("epoch", principal.epoch).put("methods", JSONObject().apply { methods.forEach { (name, schema) -> put(name, JSONObject(schema.toString())) } })
        .put("authority", "core_bound_transport").put("authorization_granted", false)
    fun validate(method: String, params: JSONObject): Boolean {
        val schema = methods[method] ?: return false
        WorkspaceJsonSchema.validate(schema, params)
        return true
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
        export const host=Object.freeze({request,describe:()=>request('host.describe'),ready:()=>request('host.ready')});
    """.trimIndent()
}
