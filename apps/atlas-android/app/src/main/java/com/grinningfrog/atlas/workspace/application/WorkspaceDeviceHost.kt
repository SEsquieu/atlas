package com.grinningfrog.atlas.workspace.application

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/** Permission UI lives outside the authored WebView. It cannot be clicked by workspace JavaScript. */
@Composable
internal fun WorkspaceDeviceHost(principal: WorkspacePrincipal, bundle: JSONObject, current: () -> Boolean, modifier: Modifier, database: com.grinningfrog.atlas.data.AtlasDatabase, adapterFactory: (android.content.Context,Map<String,Set<String>>)->List<WorkspaceCapabilityAdapter> = WorkspaceCapabilityRegistry::create, content: @Composable (WorkspaceDeviceBroker?, Modifier) -> Unit) {
    val requested = remember(bundle) { WorkspaceCapabilityRegistry.requested(bundle.getJSONObject("manifest")) }
    if (requested.isEmpty()) { content(null, modifier); return }
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val consentStore = remember(owner) { WorkspaceConsentStore.forLifecycle(owner.lifecycle) }
    var version by remember { mutableIntStateOf(0) }
    var prompt by remember { mutableStateOf<String?>(null) }
    var consentResult by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }
    var permissionResult by remember { mutableStateOf<CompletableDeferred<Unit>?>(null) }
    val adapters = remember(principal) { adapterFactory(context,requested) }
    val audit = remember(principal) { WorkspaceDeviceAudit.Writer(database,principal.workspaceId) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ -> permissionResult?.complete(Unit) }
    val broker = remember(principal) { WorkspaceDeviceBroker(principal, adapters,
        current = { owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && current() },
        consent = { message ->
            val result = CompletableDeferred<Boolean>(); consentResult = result; prompt = message
            try { withTimeout(40_000) { result.await() } } finally { if (consentResult === result) { consentResult = null; prompt = null } }
        },
        permission = { adapter ->
            if (adapter.permitted()) true else {
                val result = CompletableDeferred<Unit>(); permissionResult = result
                try { launcher.launch(adapter.permissions); withTimeout(40_000) { result.await() }; adapter.permitted() }
                finally { if (permissionResult === result) permissionResult = null }
            }
        }, scope = scope, changed = { version++ }, audit = { audit.record(it) },
        grants = consentStore.acquire(principal, requested) { reason ->
            val writer=WorkspaceDeviceAudit.Writer(database,principal.workspaceId)
            writer.record(JSONObject().put("kind","consent_expired").put("at_ms",System.currentTimeMillis())
                .put("instance_id",principal.instanceId).put("revision_id",principal.revisionId).put("epoch",principal.epoch)
                .put("details",JSONObject().put("reason",reason).put("foreground_consent_retained",false)))
            writer.close()
        }) }
    DisposableEffect(broker, owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) broker.revoke(reason="activity_stopped") }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); broker.close(preserveConsent = true); audit.close(); consentResult?.cancel(); permissionResult?.cancel() }
    }
    // Periodic status sampling shows peripheral disconnects and Android permission changes without a page request.
    LaunchedEffect(broker) { while (true) { kotlinx.coroutines.delay(750); broker.refreshAuthority(); version++ } }
    val status = remember(version) { broker.status() }
    val label=status.optString("capability_label","Device")
    Column(modifier) {
        Surface(tonalElevation = 2.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(when {
                    !status.optBoolean("requests_enabled") -> "$label access blocked"
                    status.optBoolean("connected") -> "$label · ${status.optString("device_label")}"
                    status.optBoolean("busy") -> "$label · request in progress"
                    !status.optBoolean("installed") -> "$label is unavailable"
                    !status.optBoolean("os_permission_granted") -> "$label · permission needed"
                    !status.optBoolean("platform_enabled") -> "$label is off"
                    else -> "$label · ready"
                }, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 2)
                if (status.optBoolean("requests_enabled")) TextButton(onClick = { broker.revoke(blockRequests = true) }) { Text("Revoke") }
                else TextButton(onClick = { broker.enableRequests() }) { Text("Enable requests") }
            }
        }
        content(broker, Modifier.fillMaxWidth().weight(1f))
    }
    if (prompt != null) AlertDialog(onDismissRequest = { consentResult?.complete(false) },
        title = { Text("${bundle.getJSONObject("manifest").getString("title")} · Device access") },
        text = { Text(prompt!!) },
        confirmButton = { TextButton(onClick = { consentResult?.complete(true) }) { Text("Allow") } },
        dismissButton = { TextButton(onClick = { consentResult?.complete(false) }) { Text("Deny") } })
}
