package com.grinningfrog.atlas

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.grinningfrog.atlas.data.SecureSettings
import com.grinningfrog.atlas.cloud.ManagedAccountClient
import com.grinningfrog.atlas.media.MediaRepository
import com.grinningfrog.atlas.model.AtlasEvent
import com.grinningfrog.atlas.model.MessageKind
import com.grinningfrog.atlas.model.MessageRole
import com.grinningfrog.atlas.model.ContextMode
import com.grinningfrog.atlas.model.ProviderEndpoint
import com.grinningfrog.atlas.model.RouteTable
import com.grinningfrog.atlas.model.RuntimePhase
import com.grinningfrog.atlas.model.RuntimeSnapshot
import com.grinningfrog.atlas.model.SessionStatus
import com.grinningfrog.atlas.model.ToolCallStatus
import com.grinningfrog.atlas.runtime.AtlasMobileRuntime
import com.grinningfrog.atlas.runtime.AtlasSessionService
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.UUID

class MainActivity : ComponentActivity() {
    private var service by mutableStateOf<AtlasSessionService?>(null)
    private var permissionsGranted by mutableStateOf(false)
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as AtlasSessionService.LocalBinder).service
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionsGranted = requiredPermissionsGranted()
        setContent {
            AtlasTheme {
                val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                    permissionsGranted = requiredPermissionsGranted()
                    if (permissionsGranted) startAndBindRuntime()
                }
                AtlasApp(
                    runtime = service?.runtime,
                    settings = (application as AtlasApplication).settings,
                    mediaRepository = (application as AtlasApplication).mediaRepository,
                    managedAccount = (application as AtlasApplication).managedAccount,
                    permissionsGranted = permissionsGranted,
                    onRequestPermissions = { permissionLauncher.launch(requestedPermissions()) },
                )
            }
        }
        if (permissionsGranted) startAndBindRuntime()
    }

    override fun onStart() {
        super.onStart()
        if (permissionsGranted && !bound) startAndBindRuntime()
    }

    override fun onStop() {
        if (bound) {
            unbindService(connection)
            bound = false
        }
        super.onStop()
    }

    private fun startAndBindRuntime() {
        AtlasSessionService.start(this)
        bound = bindService(Intent(this, AtlasSessionService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    private fun requiredPermissionsGranted() = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestedPermissions() = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()
}

private enum class AppPage { SESSION, PROVIDERS, EVENTS }

@Composable
private fun AtlasApp(
    runtime: AtlasMobileRuntime?,
    settings: SecureSettings,
    mediaRepository: MediaRepository,
    managedAccount: ManagedAccountClient,
    permissionsGranted: Boolean,
    onRequestPermissions: () -> Unit,
) {
    var page by remember { mutableStateOf(AppPage.SESSION) }
    var providerRevision by remember { mutableIntStateOf(0) }
    val providers = remember(providerRevision) { settings.loadProviders() }
    val snapshot by runtime?.state?.collectAsState() ?: remember { mutableStateOf(RuntimeSnapshot()) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(page == AppPage.SESSION, { page = AppPage.SESSION }, { Icon(Icons.Default.GraphicEq, null) }, label = { Text("Session") })
                NavigationBarItem(page == AppPage.PROVIDERS, { page = AppPage.PROVIDERS }, { Icon(Icons.Default.Hub, null) }, label = { Text("Inference") })
                NavigationBarItem(page == AppPage.EVENTS, { page = AppPage.EVENTS }, { Icon(Icons.Default.History, null) }, label = { Text("Events") })
            }
        },
    ) { padding ->
        when {
            !permissionsGranted -> PermissionGate(Modifier.padding(padding), onRequestPermissions)
            runtime == null -> LoadingRuntime(Modifier.padding(padding))
            page == AppPage.SESSION -> SessionPage(runtime, snapshot, providers.isNotEmpty(), mediaRepository, Modifier.padding(padding))
            page == AppPage.PROVIDERS -> ProviderPage(settings, providers, managedAccount, Modifier.padding(padding)) { providerRevision++ }
            page == AppPage.EVENTS -> EventsPage(snapshot, Modifier.padding(padding))
        }
    }
}

@Composable
private fun PermissionGate(modifier: Modifier, onRequest: () -> Unit) {
    Column(modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center) {
        Text("Put Atlas in the room", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text("Atlas needs camera and microphone access while a physical session is active. Android shows a persistent notification, and Atlas records every observation and inference attempt in its local event log.")
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRequest) { Text("Grant session permissions") }
        Spacer(Modifier.height(12.dp))
        Text("No Atlas account is required. Provider credentials stay encrypted on this phone.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LoadingRuntime(modifier: Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text("Starting Atlas Core on this phone…")
        }
    }
}

@Composable
private fun SessionPage(runtime: AtlasMobileRuntime, snapshot: RuntimeSnapshot, hasProvider: Boolean, mediaRepository: MediaRepository, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("Everyday Atlas") }
    var goal by remember { mutableStateOf("Help me understand and act safely in my current surroundings.") }
    var prompt by remember { mutableStateOf("") }
    var actionError by remember { mutableStateOf<String?>(null) }
    val session = snapshot.session
    val active = session?.status == SessionStatus.ACTIVE

    fun runAction(block: suspend () -> Unit) {
        scope.launch { runCatching { block() }.onFailure { actionError = it.message ?: it.javaClass.simpleName } }
    }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Atlas", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black)
                PhasePill(snapshot.phase)
            }
            Text("The physical session belongs to this device. Inference is a replaceable route.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (!hasProvider) item {
            NoticeCard("Inference not configured", "Add a direct local, LAN, or cloud endpoint under Inference. Atlas can still create the durable session, but it cannot reason yet.")
        }

        if (snapshot.session == null || snapshot.session.status == SessionStatus.DONE) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("New physical session", style = MaterialTheme.typography.titleLarge)
                        OutlinedTextField(name, { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(goal, { goal = it }, label = { Text("Goal") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                        Button({ runAction { runtime.createAndStartSession(name, goal) } }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.PlayArrow, null); Text(" Start session")
                        }
                    }
                }
            }
        } else item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(snapshot.session.name, style = MaterialTheme.typography.titleLarge)
                    Text(snapshot.session.goal, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (active) {
                            OutlinedButton({ runAction { runtime.pauseSession() } }) { Icon(Icons.Default.Pause, null); Text(" Pause") }
                            OutlinedButton({ runAction { runtime.endSession() } }) { Icon(Icons.Default.Stop, null); Text(" End") }
                        } else {
                            Button({ runAction { runtime.resumeSession() } }) { Icon(Icons.Default.PlayArrow, null); Text(" Resume") }
                            OutlinedButton({ runAction { runtime.endSession() } }) { Text("End") }
                        }
                    }
                }
            }
        }

        if (session != null && session.status != SessionStatus.DONE) item {
            val live = session.contextMode == ContextMode.LIVE
            Card(colors = CardDefaults.cardColors(containerColor = if (live) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .55f) else MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Live Context", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(if (live) "Atlas is maintaining a rolling physical context." else "Atlas observes only when you ask or tap Observe.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = live, onCheckedChange = { enabled -> runAction { runtime.setLiveContextEnabled(enabled) } }, enabled = active)
                    }
                    if (live) {
                        Text("Adaptive captures use local scene gating. Background inference is limited to one call per minute and 12 calls per hour.", style = MaterialTheme.typography.bodySmall)
                        snapshot.nextHeartbeatAtMs?.let { Text("Next context check ${DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(it))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    } else if (!active) {
                        Text("Resume the session to change this setting.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        snapshot.latestObservation?.let { observation ->
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column {
                        remember(observation.media.id) { BitmapFactory.decodeFile(mediaRepository.resolve(observation.media).absolutePath)?.asImageBitmap() }?.let { bitmap ->
                            Image(bitmap, "Latest Atlas observation", Modifier.fillMaxWidth().height(220.dp), contentScale = ContentScale.Crop)
                        }
                        Column(Modifier.padding(14.dp)) {
                            Text("Current visual context", fontWeight = FontWeight.SemiBold)
                            Text("age ${snapshot.contextAgeMs?.let(::duration) ?: "—"} · ${observation.motionState.name.lowercase()} · ${observation.media.width}×${observation.media.height} · ${observation.media.byteSize / 1024} KB", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            observation.summary?.let { summary ->
                                Spacer(Modifier.height(5.dp))
                                Text(summary, style = MaterialTheme.typography.bodySmall)
                                observation.interpretedAtMs?.let { Text("Interpreted ${duration((System.currentTimeMillis() - it).coerceAtLeast(0))} ago", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                    }
                }
            }
        }

        val dialogue = snapshot.messages.filter { it.kind == MessageKind.DIALOGUE && it.content.isNotBlank() }.takeLast(30)
        if (dialogue.isNotEmpty()) {
            item { Text("Conversation", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            items(dialogue, key = { it.id }) { message ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.role == MessageRole.USER) Arrangement.End else Arrangement.Start) {
                    Card(
                        modifier = Modifier.fillMaxWidth(.88f),
                        colors = CardDefaults.cardColors(containerColor = if (message.role == MessageRole.USER) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer),
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(if (message.role == MessageRole.USER) "You" else "Atlas", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                            Spacer(Modifier.height(4.dp)); Text(message.content)
                        }
                    }
                }
            }
            if (snapshot.phase == RuntimePhase.SPEAKING) item { TextButtonCompact("Stop speaking") { runtime.stopSpeaking() } }
        }

        snapshot.activeTurn?.let { turn ->
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Agent turn · ${turn.status.name.lowercase().replace('_', ' ')}", fontWeight = FontWeight.SemiBold)
                            Text("step ${turn.stepCount} · durable turn ${turn.id.take(8)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (turn.status != com.grinningfrog.atlas.model.TurnStatus.WAITING_FOR_CONFIRMATION) {
                            OutlinedButton({ runtime.cancelActiveTurn() }) { Text("Cancel") }
                        }
                    }
                }
            }
        }

        items(snapshot.pendingToolCalls.filter { it.status == ToolCallStatus.WAITING_FOR_CONFIRMATION }, key = { it.id }) { call ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = .65f))) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Atlas wants permission", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(call.name, fontWeight = FontWeight.SemiBold)
                    call.reason?.let { Text(it) }
                    Text(call.argumentsJson.take(500), style = MaterialTheme.typography.bodySmall)
                    Text("Risk: ${call.risk.name.lowercase().replace('_', ' ')}", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ runAction { runtime.resolveToolCall(call.id, true) } }) { Text("Allow") }
                        OutlinedButton({ runAction { runtime.resolveToolCall(call.id, false) } }) { Text("Decline") }
                    }
                }
            }
        }

        if (snapshot.pendingToolCalls.any { it.status == ToolCallStatus.UNKNOWN }) item {
            NoticeCard("Tool outcome needs review", "Atlas was restarted while a tool was running. It will not retry the action automatically.")
        }

        if (snapshot.memories.isNotEmpty()) item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("What Atlas is carrying forward", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    snapshot.memories.take(12).forEach { memory ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(memory.kind.name.lowercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                Text(memory.content, style = MaterialTheme.typography.bodySmall)
                            }
                            TextButtonCompact("Forget") { runtime.forgetMemory(memory.id) }
                        }
                    }
                    snapshot.sessionSummary?.let { Text("Older conversation checkpointed through message ${it.throughMessageSequence}.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }

        if (actionError != null || snapshot.latestError != null) item {
            NoticeCard("Runtime needs attention", actionError ?: snapshot.latestError.orEmpty())
        }

        item {
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text("Ask about here and now") },
                enabled = active && snapshot.activeTurn == null,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = { IconButton(enabled = active && snapshot.activeTurn == null && prompt.isNotBlank(), onClick = { val text = prompt; prompt = ""; runAction { runtime.ask(text) } }) { Icon(Icons.AutoMirrored.Filled.Send, "Send") } },
                keyboardActions = KeyboardActions(onSend = { if (prompt.isNotBlank()) { val text = prompt; prompt = ""; runAction { runtime.ask(text) } } }),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Send),
            )
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button({ runAction { runtime.listenAndAsk() } }, enabled = active && snapshot.activeTurn == null, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Mic, null); Text(" Ask") }
                OutlinedButton({ runAction { runtime.captureNow() } }, enabled = active && snapshot.activeTurn == null, modifier = Modifier.weight(1f)) { Icon(Icons.Default.CameraAlt, null); Text(" Observe") }
            }
        }
        item { HealthCard(snapshot) }
    }
}

@Composable
private fun ProviderPage(settings: SecureSettings, providers: List<ProviderEndpoint>, managedAccount: ManagedAccountClient, modifier: Modifier, onChanged: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("http://10.0.2.2:11434") }
    var model by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var vision by remember { mutableStateOf(true) }
    var tools by remember { mutableStateOf(true) }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("Inference", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text("Route by capability, never by a model baked into Atlas Core.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Direct · bring your own", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("OpenAI-compatible local, LAN, or cloud endpoint. Requests go directly from this phone; keys are encrypted with Android Keystore.")
                }
            }
        }
        item { ManagedAccountCard(managedAccount, settings, onChanged) }
        if (providers.isNotEmpty()) item { Text("Configured endpoints", style = MaterialTheme.typography.titleMedium) }
        items(providers, key = { it.id }) { endpoint ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(endpoint.name, fontWeight = FontWeight.SemiBold)
                        Text("${endpoint.model} · ${listOfNotNull("text", "vision".takeIf { endpoint.supportsVision }, "tools".takeIf { endpoint.supportsTools }).joinToString(" + ")}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(endpoint.baseUrl, style = MaterialTheme.typography.bodySmall)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(endpoint.supportsVision, { enabled ->
                                settings.updateProviderCapabilities(endpoint.id, enabled, endpoint.supportsTools)
                                val all = settings.loadProviders()
                                val ids = all.map { it.id }
                                settings.saveRoutes(RouteTable(ids, all.filter { it.supportsVision }.map { it.id }, ids, ids))
                                onChanged()
                            })
                            Text("Vision")
                            Checkbox(endpoint.supportsTools, { enabled ->
                                settings.updateProviderCapabilities(endpoint.id, endpoint.supportsVision, enabled)
                                onChanged()
                            })
                            Text("Tools")
                        }
                    }
                    TextButtonCompact("Remove") {
                        settings.deleteProvider(endpoint.id)
                        val remaining = settings.loadProviders().map { it.id }
                        settings.saveRoutes(RouteTable(remaining, remaining.filter { id -> settings.loadProviders().firstOrNull { it.id == id }?.supportsVision == true }, remaining, remaining))
                        onChanged()
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Add endpoint", style = MaterialTheme.typography.titleLarge)
                    OutlinedTextField(name, { name = it }, label = { Text("Name (for example, Home Ollama)") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("Base URL") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(model, { model = it }, label = { Text("Model") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(apiKey, { apiKey = it }, label = { Text("API key (optional)") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(vision, { vision = it }); Text("Endpoint accepts image input")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(tools, { tools = it }); Text("Endpoint supports OpenAI-compatible tool calls")
                    }
                    Button(
                        enabled = name.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank(),
                        onClick = {
                            val id = UUID.randomUUID().toString()
                            settings.saveProvider(ProviderEndpoint(id, name.trim(), baseUrl.trim(), model.trim(), id, supportsVision = vision, supportsTools = tools), apiKey)
                            val all = settings.loadProviders()
                            val allIds = all.map { it.id }
                            settings.saveRoutes(RouteTable(allIds, all.filter { it.supportsVision }.map { it.id }, allIds, allIds))
                            name = ""; model = ""; apiKey = ""; onChanged()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Save and add to capability routes") }
                }
            }
        }
    }
}

@Composable
private fun ManagedAccountCard(account: ManagedAccountClient, settings: SecureSettings, onChanged: () -> Unit) {
    val state by account.state.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    fun installEndpoint() {
        val endpoint = account.endpoint()
        settings.saveProvider(endpoint, null)
        val all = settings.loadProviders()
        val ids = listOf(endpoint.id) + all.map { it.id }.filterNot { it == endpoint.id }
        settings.saveRoutes(RouteTable(ids, ids.filter { id -> all.firstOrNull { it.id == id }?.supportsVision == true }, ids, ids))
        onChanged()
    }

    fun launchCheckout(block: suspend () -> String) {
        scope.launch {
            runCatching { block() }
                .onSuccess { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(it))) }
                .onFailure(account::reportError)
        }
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .55f))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("Atlas Cloud · managed frontier inference", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (!state.configured) {
                Text("This build has the account and billing plumbing disabled. Configure the gateway and Supabase build values to enable it.")
            } else if (!state.signedIn) {
                Text("Create an Atlas account for metered inference. Subscribe for a recurring allowance or buy additional credit blocks.")
                OutlinedTextField(email, { email = it }, label = { Text("Email") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(password, { password = it }, label = { Text("Password") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !state.busy && email.isNotBlank() && password.length >= 8, onClick = { scope.launch { account.signIn(email, password); if (account.state.value.signedIn) { installEndpoint(); account.refreshBalance() } } }) { Text("Sign in") }
                    OutlinedButton(enabled = !state.busy && email.isNotBlank() && password.length >= 8, onClick = { scope.launch { account.signUp(email, password); if (account.state.value.signedIn) installEndpoint() } }) { Text("Create account") }
                }
            } else {
                LaunchedEffect(state.email) { if (state.balanceMicros == null) account.refreshBalance() }
                Text(state.email.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${state.balanceMicros?.let { "%.2f credits".format(it / 1_000_000.0) } ?: "Balance unavailable"} · ${state.subscriptionStatus}", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !state.busy, onClick = { launchCheckout { account.checkout("subscription") } }) { Text("Subscribe") }
                    OutlinedButton(enabled = !state.busy, onClick = { launchCheckout { account.checkout("credit_block") } }) { Text("Buy credits") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButtonCompact("Use first") { installEndpoint() }
                    TextButtonCompact("Refresh") { scope.launch { account.refreshBalance() } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButtonCompact("Manage billing") { launchCheckout { account.portal() } }
                    TextButtonCompact("Sign out") { scope.launch {
                        settings.deleteProvider(ManagedAccountClient.ENDPOINT_ID)
                        val remaining = settings.loadProviders()
                        val ids = remaining.map { it.id }
                        settings.saveRoutes(RouteTable(ids, remaining.filter { it.supportsVision }.map { it.id }, ids, ids))
                        account.signOut()
                        onChanged()
                    } }
                }
            }
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun EventsPage(snapshot: RuntimeSnapshot, modifier: Modifier) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Event stream", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text("Monotonic local sequence · newest first", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
        }
        if (snapshot.recentEvents.isEmpty()) item { Text("Start a session to create an auditable physical-agent trace.") }
        items(snapshot.recentEvents.asReversed(), key = AtlasEvent::sequence) { event -> EventRow(event) }
    }
}

@Composable
private fun EventRow(event: AtlasEvent) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text("#${event.sequence}  ${event.type}", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(event.atMs)), style = MaterialTheme.typography.bodySmall)
            }
            if (event.dataJson != "{}") Text(event.dataJson.take(420), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HealthCard(snapshot: RuntimeSnapshot) {
    val health = snapshot.deviceHealth
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp)) {
            Text("Runtime health", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(5.dp))
            Text("battery ${health.batteryPercent?.let { "$it%" } ?: "—"} · ${health.thermalStatus} · ${health.network} · ${health.motion.name.lowercase()}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            snapshot.nextHeartbeatAtMs?.let { Text("next heartbeat ${DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(it))}", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun NoticeCard(title: String, detail: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(14.dp)) { Text(title, fontWeight = FontWeight.Bold); Text(detail) }
    }
}

@Composable
private fun PhasePill(phase: RuntimePhase) {
    Box(Modifier.background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 5.dp)) {
        Text(phase.name.lowercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
private fun TextButtonCompact(text: String, onClick: () -> Unit) {
    androidx.compose.material3.TextButton(onClick, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) { Text(text) }
}

private fun duration(milliseconds: Long): String = when {
    milliseconds < 1_000 -> "${milliseconds}ms"
    milliseconds < 60_000 -> "${milliseconds / 1_000}s"
    else -> "${milliseconds / 60_000}m"
}

private val AtlasColors = darkColorScheme(
    primary = Color(0xFF5FE39B),
    onPrimary = Color(0xFF003921),
    primaryContainer = Color(0xFF145233),
    onPrimaryContainer = Color(0xFFA9F4C6),
    background = Color(0xFF07100C),
    surface = Color(0xFF101B15),
    surfaceVariant = Color(0xFF26382E),
)

@Composable
private fun AtlasTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AtlasColors, content = content)
}
