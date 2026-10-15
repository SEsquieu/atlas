package com.grinningfrog.atlas.workspace.application

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnAttach
import androidx.core.view.doOnLayout
import com.grinningfrog.atlas.data.AtlasDatabase
import com.grinningfrog.atlas.workspace.ComposableWorkspace
import com.grinningfrog.atlas.workspace.WorkspaceRevision
import kotlinx.coroutines.delay
import java.util.UUID
import org.json.JSONObject

@Composable
internal fun WorkspaceApplicationPanel(workspace: ComposableWorkspace, revision: WorkspaceRevision, database: AtlasDatabase, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val data = remember(database) { WorkspaceApplicationData(database) }
    var refresh by remember(workspace.id, revision.id) { mutableIntStateOf(0) }
    var failure by remember(workspace.id, revision.id) { mutableStateOf<String?>(null) }
    val epoch = remember(refresh, workspace.id, revision.id) { data.epoch(workspace.id) }
    if (epoch < 0 || failure != null) {
        Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(failure ?: "This application is stopped. Records are preserved.")
            Button(onClick = {
                runCatching { data.setEnabled(workspace.id, revision.id, true) }.onSuccess { failure = null; refresh++ }.onFailure { failure = it.message }
            }) { Text("Retry application") }
        }
        return
    }
    key(workspace.id, revision.id, epoch) {
        var ready by remember { mutableStateOf(false) }
        fun failed(message: String) {
            runCatching { data.setEnabled(workspace.id, revision.id, false) }
            failure = message
        }
        val principal = remember { WorkspacePrincipal(workspace.realmId, workspace.id, revision.id, UUID.randomUUID().toString(), epoch, WorkspaceInstanceMode.FOREGROUND) }
        val bundle = remember(revision.id) { JSONObject(revision.definitionJson) }
        WorkspaceDeviceHost(principal, bundle, current = { runCatching { data.fence(principal); true }.getOrDefault(false) }, modifier = modifier) { devices, viewportModifier ->
            val presentation = remember {
                runCatching {
                    WorkspaceWebPresentation(context, JSONObject(revision.definitionJson), principal,
                        current = { runCatching { data.fence(principal); true }.getOrDefault(false) },
                        onReady = { ready = true }, onFailure = { failed(it) }, data = data, devices = devices)
                }.onFailure { failed(it.message ?: "Application could not start") }.getOrNull()
            }
            if (presentation != null) {
                DisposableEffect(presentation) {
                    val token = Any()
                    com.grinningfrog.atlas.workspace.WorkspaceRenderSurface.register(token, workspace.id, revision.id, inspect = { presentation.inspectLayout() }) {
                        presentation.captureVisiblePixels()
                    }
                    val listener = android.view.ViewTreeObserver.OnDrawListener {
                        com.grinningfrog.atlas.workspace.WorkspaceRenderSurface.drawn(token, JSONObject().put("format", WorkspaceApplicationContract.FORMAT).put("revision_id", revision.id).put("runtime", presentation.diagnostics()).put("drawn_at_ms", System.currentTimeMillis()))
                    }
                    presentation.view.viewTreeObserver.addOnDrawListener(listener)
                    onDispose {
                        com.grinningfrog.atlas.workspace.WorkspaceRenderSurface.unregister(token)
                        if (presentation.view.viewTreeObserver.isAlive) presentation.view.viewTreeObserver.removeOnDrawListener(listener)
                        presentation.dispose()
                    }
                }
                LaunchedEffect(presentation) { delay(10_000); if (!ready) failed("Application did not signal readiness. Records are preserved.") }
                AndroidView(factory = {
                    android.widget.FrameLayout(context).apply {
                        addView(presentation.view, android.widget.FrameLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT))
                        presentation.view.doOnAttach { view -> view.doOnLayout { presentation.loadAttached() } }
                    }
                }, modifier = viewportModifier)
            }
        }
    }
}
