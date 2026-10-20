package com.grinningfrog.atlas.workspace.application

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/** In-memory consent for one activity foreground lifetime; never stores device handles. */
internal class WorkspaceConsentStore {
    private data class Key(val realm: String, val workspace: String, val revision: String, val epoch: Long, val contract: String)
    private val entries = linkedMapOf<Key, WorkspaceCapabilityGrants>()
    private val audit = mutableMapOf<Key, (String)->Unit>()
    fun acquire(principal: WorkspacePrincipal, requested: Map<String, Set<String>>, onExpiry: ((String)->Unit)? = null): WorkspaceCapabilityGrants {
        val contract = requested.toSortedMap().entries.joinToString(";") { (name, operations) -> "$name:${operations.sorted().joinToString(",")}" }
        val key = Key(principal.realmId, principal.workspaceId, principal.revisionId, principal.epoch, contract)
        val obsolete = entries.keys.filter { it.realm == key.realm && it.workspace == key.workspace && it != key }
        obsolete.forEach { entries.remove(it)?.revoke(); audit.remove(it)?.invoke("revision_epoch_or_contract_changed") }
        if (key !in entries && entries.size >= 128) { val oldest=entries.keys.first();entries.remove(oldest)?.revoke();audit.remove(oldest)?.invoke("consent_capacity") }
        if(onExpiry!=null)audit[key]=onExpiry
        return entries.getOrPut(key) { WorkspaceCapabilityGrants() }
    }
    fun clear() {
        entries.forEach { (key, grants) ->
            val hadConsent=grants.hasAnyConsent()
            grants.revoke()
            if(hadConsent)audit[key]?.invoke("activity_stopped")
        }
    }

    companion object {
        private val stores = mutableMapOf<Lifecycle, WorkspaceConsentStore>()
        // Called from composition on Main. Observer remains installed when a panel is absent,
        // so backgrounding from the workspace list still revokes hidden workspace consent.
        fun forLifecycle(lifecycle: Lifecycle): WorkspaceConsentStore = stores.getOrPut(lifecycle) {
            WorkspaceConsentStore().also { store ->
                val observer = object : LifecycleEventObserver {
                    override fun onStateChanged(source: androidx.lifecycle.LifecycleOwner, event: Lifecycle.Event) {
                        if (event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) store.clear()
                        if (event == Lifecycle.Event.ON_DESTROY) { lifecycle.removeObserver(this); stores.remove(lifecycle) }
                    }
                }
                lifecycle.addObserver(observer)
            }
        }
    }
}
