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
        "data.prepare" to JSONObject("""{"type":"object","properties":{"expected_data_version":{"type":"integer"},"request_key":{"type":"string","maxLength":128},"mutations":{"type":"array","maxItems":32,"items":{"type":"object","description":"create: kind,collection,data; update: kind,collection,record_id,data; delete: kind,collection,record_id. data is a strict JSON object; unknown mutation fields are rejected."}}},"required":["expected_data_version","request_key","mutations"],"additionalProperties":false}"""),
        "data.commit" to operationSchema, "data.inspect" to operationSchema, "data.cancel" to operationSchema,
    )
    fun describe(principal: WorkspacePrincipal, storage: Boolean = false, bluetooth: Boolean = false, capabilityBindings: Set<String> = if(bluetooth)setOf(WorkspaceBleContract.CAPABILITY) else emptySet()): JSONObject = WorkspaceApplicationContract.descriptor()
        .put("instance_mode", principal.mode.name.lowercase()).put("workspace_id", principal.workspaceId).put("revision_id", principal.revisionId)
        .put("epoch", principal.epoch).put("methods", JSONObject().apply { (methods + if (storage) storageMethods else emptyMap()).forEach { (name, schema) -> put(name, JSONObject(schema.toString())) } })
        .apply {
            val bindings=if(principal.mode==WorkspaceInstanceMode.FOREGROUND)capabilityBindings else emptySet()
            val available = WorkspaceBleContract.CAPABILITY in bindings
            put("capability_registry",WorkspaceCapabilityRegistry.describe())
            put("ble_available", available).put("ble", WorkspaceBleContract.descriptor())
            val installedMethods=WorkspaceCapabilityRegistry.methods(bindings)
            for(name in installedMethods.keys())getJSONObject("methods").put(name,installedMethods.getJSONObject(name))

        }
        .put("authority", "core_bound_transport").put("storage_authorized", storage)
        .put("storage_limits",JSONObject().put("query_limit",JSONObject().put("minimum",1).put("maximum",100).put("default",50)).put("query_offset",JSONObject().put("minimum",0).put("maximum",30000)).put("mutations_per_prepare",32))
        .put("storage_contract", "Workspace-scoped foreground records, or isolated disposable test records when storage_scope=isolated_test_database. query limit is 1..100 (default 50); use next_offset to paginate. query returns data_version and a next_offset; null ends pagination. prepare requires expected_data_version, mutations and a reusable request_key. Retry the same key with identical input after response loss; commit/inspect/cancel use the returned operation_id. Revisions fence old callers. Device access, when declared, uses the separate native BLE broker; there are no direct browser device, network, credential, or background APIs.")
    fun validate(method: String, params: JSONObject, storage: Boolean = false): Boolean {
        val schema = methods[method] ?: if (storage) storageMethods[method] else null
        if (schema == null) return false
        WorkspaceApplicationContract.checkJson(params)
        if (method == "data.prepare") {
            WorkspaceApplicationContract.exactKeys(params, setOf("expected_data_version", "request_key", "mutations"), setOf("expected_data_version", "request_key", "mutations"))
            params.getJSONArray("mutations") // full closed mutation validation belongs to the transaction store
        } else WorkspaceJsonSchema.validate(schema, params)
        return true
    }
    fun dispatch(principal: WorkspacePrincipal, method: String, params: JSONObject, data: WorkspaceApplicationData?, bluetooth: Boolean = false, capabilityBindings: Set<String> = if(bluetooth)setOf(WorkspaceBleContract.CAPABILITY) else emptySet()): JSONObject {
        val storage = data != null && data.acceptsMode(principal)
        require(validate(method, params, storage)) { "UNSUPPORTED_CAPABILITY" }
        if (method == "host.describe" || method == "host.ready") return describe(principal, storage, bluetooth, capabilityBindings).put("storage_scope",if(storage && data?.isolatedTestStorage==true) "isolated_test_database" else if(storage) "live_workspace" else "unavailable")
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
        async function capabilityCall(method,params={}) {
          const submitted=await value(method,params);
          if(!submitted.operation_id) return submitted;
          const deadline=Date.now()+50000;
          while(Date.now()<deadline) {
            const receipt=await value(method.split('.')[0]+'.operation',{operation_id:submitted.operation_id});
            if(receipt.status==='succeeded') return receipt.result;
            if(receipt.status==='failed') { const error=Error(receipt.error?.summary||'Bluetooth operation failed'); error.code=receipt.error?.code; error.receipt=receipt; throw error; }
            await new Promise(resolve=>setTimeout(resolve,250));
          }
          const error=Error('Bluetooth result timeout; inspect before retrying writes'); error.code='BLE_TIMEOUT'; error.operation_id=submitted.operation_id; throw error;
        }
        const bleCall=(method,params={})=>capabilityCall('ble.'+method,params);
        const channels=new Map(); let observers=0;
        function observe(method,params,listener,options={}) {
          if(!method.endsWith('.events') || typeof listener!=='function')throw Error('An event-stream method and listener are required');
          if(observers>=8)throw Error('Subscription quota');strict(params);
          const key=JSON.stringify([method,params]), interval=Math.max(250,Math.min(5000,Number(options.intervalMs)||500));
          let channel=channels.get(key);
          if(!channel) { channel={listeners:new Set(),timer:null,stopped:false};channels.set(key,channel); }
          const item={listener,onError:typeof options.onError==='function'?options.onError:()=>{}};
          channel.listeners.add(item);observers++;
          const notifyError=(entry,error)=>{try{entry.onError(error)}catch(_){}};
          if(channel.timer===null) {
            const tick=async()=>{
              if(channel.stopped)return;
              try {
                const result=await capabilityCall(method,params);
                if(channel.stopped)return;
                for(const entry of [...channel.listeners]) { try{entry.listener(result)}catch(error){notifyError(entry,error)} }
              } catch(error) {
                if(error.code==='BLE_BUSY' || error.code==='CAPABILITY_BUSY') { if(!channel.stopped)channel.timer=setTimeout(tick,interval);return; }
                channel.stopped=true;channels.delete(key);
                for(const entry of [...channel.listeners]) { observers--;notifyError(entry,error) }channel.listeners.clear();return;
              }
              if(!channel.stopped)channel.timer=setTimeout(tick,interval);
            };channel.timer=setTimeout(tick,0);
          }
          let stopped=false;
          return ()=>{if(stopped)return;stopped=true;if(channel.listeners.delete(item))observers--;if(channel.listeners.size===0){channel.stopped=true;clearTimeout(channel.timer);channels.delete(key);}};
        }
        async function watchBle(characteristic_id,listener,onError=()=>{}) {
          await bleCall('subscribe',{characteristic_id,enabled:true});
          const stop=observe('ble.events',{},batch=>{for(const event of batch.events||[])if(event.characteristic_id===characteristic_id)listener(event);if(batch.dropped)onError(Object.assign(Error('Notification samples dropped'),{code:'EVENTS_DROPPED',dropped:batch.dropped}));},{onError});
          return async()=>{stop();await bleCall('subscribe',{characteristic_id,enabled:false});};
        }
        export const ble=Object.freeze({watch:watchBle,status:()=>value('ble.status'),scan:()=>bleCall('scan'),connect:device_id=>bleCall('connect',{device_id}),services:()=>bleCall('services'),read:characteristic_id=>bleCall('read',{characteristic_id}),write:(characteristic_id,hex)=>bleCall('write',{characteristic_id,hex}),subscribe:(characteristic_id,enabled=true)=>bleCall('subscribe',{characteristic_id,enabled}),events:()=>bleCall('events'),disconnect:()=>value('ble.disconnect'),revoke:()=>value('ble.revoke'),operation:operation_id=>value('ble.operation',{operation_id})});
        export const host=Object.freeze({request,call:capabilityCall,observe,describe:()=>value('host.describe'),ready:()=>value('host.ready')});
        export const data=Object.freeze({query:params=>value('data.query',params),prepare:params=>value('data.prepare',params),commit:operation_id=>value('data.commit',{operation_id}),inspect:operation_id=>value('data.inspect',{operation_id}),cancel:operation_id=>value('data.cancel',{operation_id})});
    """.trimIndent()
}
