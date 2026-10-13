package com.grinningfrog.atlas.dev

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.grinningfrog.atlas.AtlasApplication
import com.grinningfrog.atlas.BuildConfig
import com.grinningfrog.atlas.model.ProviderKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

object DevelopmentPanel {
    const val available = true
    @Composable fun Content(sourceSessionId: String?) = DevelopmentProbeScreen(sourceSessionId)
}

/** Developer source set only. Production compiles a no-op hook instead. */
@Composable
internal fun DevelopmentProbeScreen(sourceSessionId: String?) {
    val app = LocalContext.current.applicationContext as AtlasApplication
    val authState by app.chatGptAuth.state.collectAsState()
    val providers = remember(authState) { app.inferenceProviders() }
    val runner = remember(app) { DevelopmentProbeRunner(app) }
    val definitions = remember(runner) { runner.definitions() }
    var endpointId by remember { mutableStateOf<String?>(null) }
    val endpoint = providers.firstOrNull { it.id == endpointId } ?: providers.firstOrNull { it.kind == ProviderKind.CHATGPT_PLAN } ?: providers.firstOrNull()
    var options by remember { mutableStateOf(ProbeOptions()) }
    var showTools by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ProbeResult?>(null) }
    var status by remember { mutableStateOf("Start with Minimal, then change one option at a time.") }
    var running by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) { onDispose { job?.cancel() } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Atlas DEV · Request diagnostics", style = MaterialTheme.typography.headlineSmall)
        Text("${BuildConfig.VERSION_NAME} · ${BuildConfig.APPLICATION_ID}", style = MaterialTheme.typography.labelMedium)
        Text("Separate app and data. Sign in through Inference first. Tests target the selected endpoint without route fallback; model tool proposals never execute. Hosted search, when enabled, may run on the provider.")
        Text("Endpoint: ${endpoint?.name ?: "None connected"} · ${endpoint?.model.orEmpty()}")
        if (providers.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(enabled = !running, onClick = { val index = providers.indexOfFirst { it.id == endpoint?.id }; endpointId = providers[(index + 1) % providers.size].id }) { Text("Next endpoint") }
        }
        Text("Change the ChatGPT model on the Inference page; these probes use the same saved authorization and transport.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(enabled = !running, onClick = { options = ProbeOptions() }) { Text("Minimal") }
            TextButton(enabled = !running, onClick = { options = ProbeOptions(tools = definitions.map { it.name }.toSet()) }) { Text("Tools only") }
            TextButton(enabled = !running, onClick = { options = ProbeOptions(hostedSearch = true) }) { Text("Search only") }
        }
        TextButton(enabled = !running, onClick = { options = ProbeOptions(instructions = true, hostedSearch = true, tools = definitions.map { it.name }.toSet()) }) { Text("Atlas instructions + all tools + search") }
        OutlinedTextField(options.prompt, { options = options.copy(prompt = it.take(4000)) }, enabled = !running, label = { Text("Test prompt") }, modifier = Modifier.fillMaxWidth())
        ProbeToggle("Atlas system instructions", options.instructions, !running) { options = options.copy(instructions = it) }
        ProbeToggle("Attach current DEV session history", options.history, !running && sourceSessionId != null) { options = options.copy(history = it) }
        ProbeToggle("Attach current DEV workspace context", options.workspace, !running && sourceSessionId != null) { options = options.copy(workspace = it) }
        Text("History/workspace comes only from a session in this DEV app. Import a session archive through Data & logs when reproducing a real request.", style = MaterialTheme.typography.bodySmall)
        ProbeToggle("Hosted web search (ChatGPT)", options.hostedSearch, !running && endpoint?.kind == ProviderKind.CHATGPT_PLAN) { options = options.copy(hostedSearch = it) }
        OutlinedTextField(options.instructionOverride, { options = options.copy(instructionOverride = it.take(16000)) }, enabled = !running,
            label = { Text("Optional system instruction override") }, modifier = Modifier.fillMaxWidth())
        TextButton(onClick = { showTools = !showTools }) { Text("Atlas tools: ${options.tools.size}/${definitions.size} · ${if (showTools) "Hide" else "Select"}") }
        if (showTools) {
            Row {
                TextButton(enabled = !running, onClick = { options = options.copy(tools = definitions.map { it.name }.toSet()) }) { Text("All") }
                TextButton(enabled = !running, onClick = { options = options.copy(tools = emptySet()) }) { Text("None") }
            }
            definitions.forEach { tool -> ProbeToggle(tool.name, tool.name in options.tools, !running) { enabled -> options = options.copy(tools = if (enabled) options.tools + tool.name else options.tools - tool.name) } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !running && endpoint != null, onClick = {
                val selected = endpoint ?: return@Button
                val selectedOptions = options
                running = true; status = "Testing ${selected.model}…"; result = null
                job = scope.launch {
                    try {
                        result = runner.run(selected, selectedOptions, sourceSessionId)
                        status = "Probe finished. Inspect the report and export it for review."
                    } catch (_: CancellationException) { status = "Probe cancelled; no tools executed." }
                    catch (error: Exception) { status = error.message ?: "Probe failed" }
                    finally { running = false }
                }
            }) { Text("Run probe") }
            TextButton(enabled = running, onClick = { job?.cancel() }) { Text("Cancel") }
        }
        Text(status)
        result?.let { completed ->
            Button(onClick = { runCatching { app.sessionArchive.share(completed.sessionId) }.onFailure { status = it.message ?: "Export failed" } }) { Text("Export probe logs") }
            SelectionContainer { Text(completed.report, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun ProbeToggle(label: String, checked: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, change, enabled = enabled)
        Text(label, Modifier.weight(1f))
    }
}
