package com.grinningfrog.atlas

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import com.grinningfrog.atlas.ui.SystemSectionTabs
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DashboardCustomize
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Shapes
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.grinningfrog.atlas.data.SecureSettings
import com.grinningfrog.atlas.data.AtlasDatabase
import com.grinningfrog.atlas.data.ChatGptUsageSummary
import com.grinningfrog.atlas.data.SessionArchive
import com.grinningfrog.atlas.cloud.ManagedAccountClient
import com.grinningfrog.atlas.media.MediaRepository
import com.grinningfrog.atlas.model.AtlasEvent
import com.grinningfrog.atlas.model.DeliveryStatus
import com.grinningfrog.atlas.model.ListeningState
import com.grinningfrog.atlas.model.MessageKind
import com.grinningfrog.atlas.model.MessageRole
import com.grinningfrog.atlas.model.ContextMode
import com.grinningfrog.atlas.model.ProviderEndpoint
import com.grinningfrog.atlas.model.PromptProfile
import com.grinningfrog.atlas.model.RouteTable
import com.grinningfrog.atlas.model.RuntimePhase
import com.grinningfrog.atlas.model.RuntimeSnapshot
import com.grinningfrog.atlas.model.SessionStatus
import com.grinningfrog.atlas.model.ToolCallStatus
import com.grinningfrog.atlas.runtime.AtlasMobileRuntime
import com.grinningfrog.atlas.runtime.AtlasSessionService
import com.grinningfrog.atlas.ui.AtlasUiPresentation
import com.grinningfrog.atlas.provider.EndpointSecurity
import com.grinningfrog.atlas.provider.ProviderConnectionTester
import com.grinningfrog.atlas.provider.ChatGptAuthManager
import com.grinningfrog.atlas.provider.ChatGptAuthState
import com.grinningfrog.atlas.intent.IdleRuntimeStatus
import com.grinningfrog.atlas.intent.IntentAutonomyMode
import com.grinningfrog.atlas.intent.IntentState
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import com.grinningfrog.atlas.workspace.WorkspacePage

class MainActivity : ComponentActivity() {
    private var service by mutableStateOf<AtlasSessionService?>(null)
    private var permissionsGranted by mutableStateOf(false)
    private var onboardingComplete by mutableStateOf(false)
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
        val app = application as AtlasApplication
        permissionsGranted = requiredPermissionsGranted()
        onboardingComplete = app.settings.onboardingComplete
        setContent {
            AtlasTheme {
                val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                    permissionsGranted = requiredPermissionsGranted()
                    if (permissionsGranted && onboardingComplete) startAndBindRuntime()
                }
                AtlasApp(
                    runtime = service?.runtime,
                    settings = app.settings,
                    mediaRepository = app.mediaRepository,
                    managedAccount = app.managedAccount,
                    chatGptAuth = app.chatGptAuth,
                    database = app.database,
                    sessionArchive = app.sessionArchive,
                    onboardingComplete = onboardingComplete,
                    permissionsGranted = permissionsGranted,
                    onCompleteOnboarding = {
                        app.settings.onboardingComplete = true
                        onboardingComplete = true
                        if (permissionsGranted) startAndBindRuntime()
                    },
                    onRequestPermissions = { permissionLauncher.launch(requestedPermissions()) },
                    onOpenSystemSettings = {
                        startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))
                    },
                )
            }
        }
        if (permissionsGranted && onboardingComplete) startAndBindRuntime()
    }

    override fun onStart() {
        super.onStart()
        if (permissionsGranted && onboardingComplete && !bound) startAndBindRuntime()
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
private enum class AppPage { SESSION, WORKSPACE, SYSTEM }

@Composable
private fun AtlasApp(
    runtime: AtlasMobileRuntime?,
    settings: SecureSettings,
    mediaRepository: MediaRepository,
    managedAccount: ManagedAccountClient,
    chatGptAuth: ChatGptAuthManager,
    database: AtlasDatabase,
    sessionArchive: SessionArchive,
    onboardingComplete: Boolean,
    permissionsGranted: Boolean,
    onCompleteOnboarding: () -> Unit,
    onRequestPermissions: () -> Unit,
    onOpenSystemSettings: () -> Unit,
) {
    var page by remember { mutableStateOf(AppPage.SESSION) }
    val sessionActionScope = rememberCoroutineScope()
    var providerRevision by remember { mutableIntStateOf(0) }
    val chatGptState by chatGptAuth.state.collectAsState()
    val providers = remember(providerRevision, chatGptState) { buildList {
        addAll(settings.loadProviders()); chatGptAuth.endpointOrNull()?.let(::add)
    } }
    val snapshot by runtime?.state?.collectAsState() ?: remember { mutableStateOf(RuntimeSnapshot()) }

    if (!onboardingComplete) {
        Onboarding(onCompleteOnboarding)
        return
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Column(if (page != AppPage.SYSTEM) Modifier.imePadding() else Modifier) {
                if (permissionsGranted && runtime != null && page != AppPage.SYSTEM) {
                    AtlasControls(runtime, snapshot, providers.isNotEmpty(), sessionActionScope)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .7f))
                NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
                    NavigationBarItem(page == AppPage.SESSION, { page = AppPage.SESSION }, { Icon(Icons.Default.GraphicEq, null) }, label = { Text("Session") }, colors = atlasNavigationColors())
                    NavigationBarItem(page == AppPage.WORKSPACE, { page = AppPage.WORKSPACE }, { Icon(Icons.Default.DashboardCustomize, null) }, label = { Text("Workspace") }, colors = atlasNavigationColors())
                    NavigationBarItem(page == AppPage.SYSTEM, { page = AppPage.SYSTEM }, { Icon(Icons.Default.Settings, null) }, label = { Text("System") }, colors = atlasNavigationColors())
                }
            }
        },
    ) { padding ->
        when {
            !permissionsGranted -> PermissionGate(Modifier.padding(padding), onRequestPermissions, onOpenSystemSettings)
            runtime == null -> LoadingRuntime(Modifier.padding(padding))
            page == AppPage.SESSION -> AtlasHomePage(runtime, snapshot, providers.isNotEmpty(), mediaRepository, sessionActionScope, Modifier.padding(padding), onOpenSystem = { page = AppPage.SYSTEM })
            page == AppPage.WORKSPACE -> WorkspacePage(runtime, snapshot, database, sessionActionScope, Modifier.padding(padding))
            page == AppPage.SYSTEM -> SystemPage(runtime, snapshot, settings, providers, managedAccount, chatGptAuth, database, sessionArchive, Modifier.padding(padding)) { providerRevision++ }
        }
    }
}

@Composable
private fun AtlasBrandHeader(kicker: String, status: String) {
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Atlas", fontWeight = FontWeight.Bold, fontSize = 21.sp, letterSpacing = (-.3).sp)
            Text(kicker, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(start = 10.dp).weight(1f))
            Text(status, color = statusColor(status), fontFamily = FontFamily.Monospace, fontSize = 10.sp, letterSpacing = .4.sp)
        }
        Spacer(Modifier.height(13.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun atlasNavigationColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = MaterialTheme.colorScheme.primary,
    selectedTextColor = MaterialTheme.colorScheme.primary,
    indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = .12f),
    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
)

@Composable
private fun Onboarding(onComplete: () -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    val titles = listOf("Stay with me.", "You control what leaves", "Start with your own inference")
    val bodies = listOf(
        "Atlas Core owns the durable session, current physical context, memory, tools, and audit trail on this phone. Models are replaceable intelligence—not the owner of your session.",
        "Camera frames are resized before storage or inference. Live Context is visibly opt-in. Android speech recognition may use a service chosen by your device. Atlas can be wrong; do not use this alpha as emergency or professional safety authority.",
        "No account is required. Connect an OpenAI-compatible HTTPS or trusted-LAN endpoint, test it, then start a session. Managed Atlas cloud inference is on the way. You can pause, export, or permanently delete session data at any time.",
    )
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        AtlasBrandHeader("PHYSICAL INTELLIGENCE", "0${step + 1} / 03")
        Spacer(Modifier.weight(1f))
        Icon(if (step == 1) Icons.Default.Security else Icons.Default.GraphicEq, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(20.dp))
        Text("${step + 1} of 3", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(titles[step], style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(12.dp))
        Text(bodies[step], style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(28.dp))
        Button(onClick = { if (step < 2) step++ else onComplete() }, modifier = Modifier.fillMaxWidth()) {
            Text(if (step < 2) "Continue" else "Continue to permissions")
        }
        if (step > 0) TextButton(onClick = { step-- }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Back") }
        Spacer(Modifier.weight(.55f))
    }
}

@Composable
private fun PermissionGate(modifier: Modifier, onRequest: () -> Unit, onOpenSettings: () -> Unit) {
    Column(modifier.fillMaxSize().padding(24.dp)) {
        AtlasBrandHeader("FIELD SYSTEM 01", "PERMISSIONS")
        Spacer(Modifier.weight(1f))
        Text("Put Atlas in the room", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text("Atlas needs camera and microphone access while a physical session is active. Android shows a persistent notification, and Atlas records every observation and inference attempt in its local event log.")
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRequest) { Text("Grant session permissions") }
        TextButton(onClick = onOpenSettings) { Text("Open Android app settings") }
        Spacer(Modifier.height(12.dp))
        Text("No Atlas account is required. Provider credentials stay encrypted on this phone.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(.6f))
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
private fun AtlasHomePage(
    runtime: AtlasMobileRuntime,
    snapshot: RuntimeSnapshot,
    hasProvider: Boolean,
    mediaRepository: MediaRepository,
    actionScope: kotlinx.coroutines.CoroutineScope,
    modifier: Modifier,
    onOpenSystem: () -> Unit,
) {
    var name by remember { mutableStateOf("Everyday Atlas") }
    var goal by remember { mutableStateOf("Help me understand and act safely in my current surroundings.") }
    var actionError by remember { mutableStateOf<String?>(null) }
    val session = snapshot.session
    val active = session?.status == SessionStatus.ACTIVE
    val awaitingClarification = snapshot.pendingClarification != null
    val turnAvailable = snapshot.activeTurn == null || awaitingClarification ||
        snapshot.activeTurn?.status == com.grinningfrog.atlas.model.TurnStatus.SOFT_TIMED_OUT

    fun runAction(block: suspend () -> Unit) {
        actionScope.launch { runCatching { block() }.onFailure { actionError = it.message ?: it.javaClass.simpleName } }
    }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, top = 18.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            AtlasIdentityHeader(snapshot, onOpenSystem)
        }

        if (!hasProvider) item {
            InstrumentCard(accent = AtlasAmber) {
                Text("Atlas needs somewhere to think", style = MaterialTheme.typography.titleLarge)
                Text("Connect a model running on this phone, your network, or a cloud provider.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onOpenSystem) { Text("Connect a model") }
            }
        }

        if (session == null || session.status == SessionStatus.DONE) item {
            Column(Modifier.padding(vertical = 26.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Ready when you are.", style = MaterialTheme.typography.headlineLarge)
                Text("Start a thread for whatever you are doing. Atlas will keep its context, observations, and decisions together on this phone.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(name, { name = it }, label = { Text("Session name") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(goal, { goal = it }, label = { Text("What are we doing?") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                Button({ runAction { runtime.createAndStartSession(name, goal) } }, enabled = hasProvider, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.PlayArrow, null); Text(" Start session")
                }
            }
        } else {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(session.name, style = MaterialTheme.typography.headlineLarge)
                    Text(session.goal, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        StatePill(if (session.contextMode == ContextMode.LIVE) "Live context" else "Manual context", session.contextMode == ContextMode.LIVE)
                        snapshot.contextAgeMs?.let { StatePill("Seen ${duration(it)} ago", it < 60_000) }
                    }
                }
            }

            item {
                val live = session.contextMode == ContextMode.LIVE
                InstrumentCard(accent = if (live) MaterialTheme.colorScheme.primary else null) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Live Context", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(if (live) "Atlas is maintaining rolling physical context." else "Atlas observes only when you ask or tap Observe.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = live, onCheckedChange = { enabled -> runAction { runtime.setLiveContextEnabled(enabled) } }, enabled = active)
                    }
                }
            }

            snapshot.pendingClarification?.let { clarification ->
                item {
                    InstrumentCard(accent = AtlasAmber) {
                        Text("Atlas needs one detail", style = MaterialTheme.typography.labelLarge, color = AtlasAmber)
                        Text(clarification.question, style = MaterialTheme.typography.titleLarge)
                        if (clarification.options.isNotEmpty()) Text(clarification.options.joinToString("  ·  "), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Answer naturally. A short reply is enough.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            (snapshot.executionTasks.firstOrNull { it.state !in setOf(com.grinningfrog.atlas.runtime.ExecutionState.COMPLETED, com.grinningfrog.atlas.runtime.ExecutionState.CANCELLED, com.grinningfrog.atlas.runtime.ExecutionState.FAILED) } ?: snapshot.executionTasks.firstOrNull())?.let { task ->
                item {
                    InstrumentCard {
                        Text("Task · ${if (task.state == com.grinningfrog.atlas.runtime.ExecutionState.COMPLETED) "response finished" else task.state.name.lowercase()}", style = MaterialTheme.typography.labelLarge)
                        Text(task.objective, maxLines = 3)
                        AtlasUiPresentation.deliveryLabel(task)?.let { Text(it, style = MaterialTheme.typography.labelLarge) }
                        if(task.document.optBoolean("recovery_required")) Text("Continuing verification recovery", style = MaterialTheme.typography.bodySmall)
                        Text("${task.document.optInt("slice_count")} slices · ${task.usage.optLong("model_steps")} model steps · ${task.usage.optLong("tool_calls")} tools", style = MaterialTheme.typography.bodySmall)
                        if (task.waitReason.isNotBlank()) Text("Current blocker: ${task.waitReason.replace('_', ' ')} · updated ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(task.document.optLong("updated_at")))}", color = AtlasAmber)
                        val recovered = task.document.optJSONObject("checkpoint")?.optJSONArray("resolved_operations")?.length() ?: 0
                        if (recovered > 0) Text("$recovered earlier errors resolved; history retained.", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (task.state in setOf(com.grinningfrog.atlas.runtime.ExecutionState.PAUSED, com.grinningfrog.atlas.runtime.ExecutionState.WAITING) && task.waitReason !in setOf("clarification", "authorization", "unknown_operation_outcome", "task_allowance")) TextButtonCompact("Resume") { runAction { runtime.resumeExecution(task.id) } }
                            if (task.state !in setOf(com.grinningfrog.atlas.runtime.ExecutionState.COMPLETED, com.grinningfrog.atlas.runtime.ExecutionState.CANCELLED, com.grinningfrog.atlas.runtime.ExecutionState.FAILED)) TextButtonCompact("Cancel task") { runtime.cancelExecution(task.id) }
                        }
                    }
                }
            }

            snapshot.turnDiagnostics.firstOrNull()?.let { report -> item { TurnDiagnosticsCard(report) } }

            snapshot.activeTurn?.let { turn ->
                item {
                    InstrumentCard(accent = if (turn.status == com.grinningfrog.atlas.model.TurnStatus.SOFT_TIMED_OUT) AtlasAmber else MaterialTheme.colorScheme.primary) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(activeWorkLabel(snapshot), style = MaterialTheme.typography.labelLarge, color = statusColor(snapshot.phase.name))
                                Text(activeWorkDetail(snapshot), style = MaterialTheme.typography.titleMedium)
                            }
                            if (turn.status !in setOf(com.grinningfrog.atlas.model.TurnStatus.WAITING_FOR_CONFIRMATION, com.grinningfrog.atlas.model.TurnStatus.WAITING_FOR_USER_CLARIFICATION)) {
                                TextButtonCompact("Cancel") { runtime.cancelActiveTurn() }
                            }
                        }
                    }
                }
            }

            val waitingCalls = snapshot.pendingToolCalls.filter { it.status == ToolCallStatus.WAITING_FOR_CONFIRMATION }
            items(waitingCalls, key = { it.id }) { call ->
                InstrumentCard(accent = AtlasAmber) {
                    Text("Permission required", style = MaterialTheme.typography.labelLarge, color = AtlasAmber)
                    Text(toolDisplayName(call.name), style = MaterialTheme.typography.titleLarge)
                    call.reason?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Text("${call.risk.name.lowercase().replace('_', ' ')} action", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ runAction { runtime.resolveToolCall(call.id, true) } }) { Text("Allow") }
                        OutlinedButton({ runAction { runtime.resolveToolCall(call.id, false) } }) { Text("Decline") }
                    }
                }
            }

            if (snapshot.latestObservation != null || snapshot.deviceHealth.motion != com.grinningfrog.atlas.model.MotionState.UNKNOWN) item {
                ObservationSurface(snapshot, mediaRepository)
            }

            val dialogue = snapshot.messages.filter { it.kind == MessageKind.DIALOGUE && it.content.isNotBlank() }.takeLast(24)
            if (dialogue.isNotEmpty()) {
                item { Text("Conversation", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
                items(dialogue, key = { "home-${it.id}" }) { message ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.role == MessageRole.USER) Arrangement.End else Arrangement.Start) {
                        Card(
                            modifier = Modifier.fillMaxWidth(.9f),
                            colors = CardDefaults.cardColors(containerColor = if (message.role == MessageRole.USER) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer),
                        ) {
                            Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(if (message.role == MessageRole.USER) "You" else "Atlas", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                                AutoLinkedText(message.content)
                            }
                        }
                    }
                }
            }

            if (snapshot.streamingResponse?.isNotBlank() == true) item {
                InstrumentCard { AutoLinkedText(snapshot.streamingResponse.orEmpty()) }
            }

            if (snapshot.pendingToolCalls.any { it.status == ToolCallStatus.UNKNOWN }) item {
                NoticeCard("Action outcome unknown", "Atlas restarted while a tool was running. It will not repeat the action automatically.")
            }
            if (actionError != null || snapshot.latestError != null) item {
                NoticeCard(if (actionError != null || snapshot.phase == RuntimePhase.ERROR) "Atlas needs attention" else "Runtime notice · check current task status", actionError ?: snapshot.latestError.orEmpty())
            }

            item { Text("${dialogue.size} recent messages", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }

        }
    }
}

@Composable
private fun AtlasIdentityHeader(snapshot: RuntimeSnapshot, onOpenSystem: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(34.dp).border(1.dp, statusColor(snapshot.phase.name).copy(alpha = .8f), RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center,
        ) { Text("A", color = statusColor(snapshot.phase.name), fontWeight = FontWeight.Black) }
        Column(Modifier.padding(start = 11.dp).weight(1f)) {
            Text("Atlas", style = MaterialTheme.typography.titleLarge)
            Text(AtlasUiPresentation.runtimeLabel(snapshot), style = MaterialTheme.typography.bodySmall, color = statusColor(snapshot.phase.name))
        }
        IconButton(onClick = onOpenSystem) { Icon(Icons.Default.Settings, "Open system settings", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun InstrumentCard(accent: Color? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().then(if (accent != null) Modifier.border(1.dp, accent.copy(alpha = .35f), MaterialTheme.shapes.medium) else Modifier),
    ) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp), content = content) }
}

@Composable
private fun StatePill(text: String, active: Boolean) {
    Box(
        Modifier.border(1.dp, if (active) MaterialTheme.colorScheme.primary.copy(alpha = .45f) else MaterialTheme.colorScheme.outline, RoundedCornerShape(50)).padding(horizontal = 9.dp, vertical = 5.dp)
    ) { Text(text, style = MaterialTheme.typography.labelSmall, color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable
private fun ObservationSurface(snapshot: RuntimeSnapshot, mediaRepository: MediaRepository) {
    val observation = snapshot.latestObservation
    InstrumentCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("What Atlas can establish", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            observation?.let { Text(snapshot.contextAgeMs?.let(::duration) ?: "now", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatePill(snapshot.deviceHealth.motion.name.lowercase().replace('_', ' '), snapshot.deviceHealth.motion != com.grinningfrog.atlas.model.MotionState.UNKNOWN)
            observation?.let { StatePill(if (it.stability == com.grinningfrog.atlas.model.ContextStability.STABLE) "stable view" else "changing view", it.stability == com.grinningfrog.atlas.model.ContextStability.STABLE) }
        }
        observation?.summary?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
        if (observation != null) {
            remember(observation.media.id) { BitmapFactory.decodeFile(mediaRepository.resolve(observation.media).absolutePath)?.asImageBitmap() }?.let { bitmap ->
                Image(bitmap, "Latest Atlas observation", Modifier.fillMaxWidth().height(152.dp), contentScale = ContentScale.Crop)
            }
            Text("Camera evidence · ${observation.media.width}×${observation.media.height} · ${observation.confidence?.let { "${(it * 100).toInt()}% confidence" } ?: "confidence not reported"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AtlasControls(runtime: AtlasMobileRuntime, snapshot: RuntimeSnapshot, hasProvider: Boolean, actionScope: kotlinx.coroutines.CoroutineScope) {
    val session = snapshot.session ?: return
    if (session.status == SessionStatus.DONE) return
    var prompt by rememberSaveable(session.id) { mutableStateOf("") }
    var showText by rememberSaveable(session.id) { mutableStateOf(false) }
    var showBlocker by remember(session.id) { mutableStateOf(false) }
    var actionError by remember(session.id) { mutableStateOf<String?>(null) }
    val active = session.status == SessionStatus.ACTIVE
    val needsUser = AtlasUiPresentation.needsUserAction(snapshot)
    val canSend = AtlasUiPresentation.canSubmitMessage(snapshot, hasProvider)
    val available = canSend
    fun runAction(block: suspend () -> Unit) {
        actionScope.launch { runCatching { block() }.onFailure { actionError = it.message ?: "Action failed" } }
    }
    fun send() { if (canSend && prompt.isNotBlank()) { val text = prompt; prompt = ""; runAction { runtime.ask(text) } } }
    LaunchedEffect(needsUser) { if (!needsUser) showBlocker = false }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(horizontal = 16.dp, vertical = 4.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { runAction { if (active) runtime.pauseSession() else runtime.resumeSession() } }) {
                Icon(if (active) Icons.Default.Pause else Icons.Default.PlayArrow, if (active) "Pause session" else "Resume session")
            }
            Text(AtlasUiPresentation.runtimeLabel(snapshot), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f), maxLines = 1)
            if (needsUser) IconButton(onClick = { showBlocker = true }) { Icon(Icons.Default.Warning, "Atlas needs your input. Open pending action", tint = AtlasAmber) }
            TextButton(onClick = { runAction { runtime.endSession() } }) { Text("End", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        VoiceDock(snapshot, active, active && hasProvider && (available || snapshot.phase == RuntimePhase.SPEAKING),
            { runAction { runtime.listenAndAsk() } }, { runAction { runtime.captureNow() } }, { showText = !showText }, needsUser)
        if (showText) OutlinedTextField(prompt, { prompt = it }, label = { Text("Message Atlas") }, enabled = active && hasProvider, modifier = Modifier.fillMaxWidth(), maxLines = 3,
            trailingIcon = { IconButton(enabled = canSend && prompt.isNotBlank(), onClick = { send() }) { Icon(Icons.AutoMirrored.Filled.Send, "Send") } },
            keyboardActions = KeyboardActions(onSend = { send() }), keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Send))
        actionError?.let { error -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(error, color = MaterialTheme.colorScheme.error, maxLines = 2, modifier = Modifier.weight(1f))
            TextButton(onClick = { actionError = null }) { Text("Dismiss") }
        } }
    }
    if (showBlocker && needsUser) AlertDialog(
        onDismissRequest = { showBlocker = false }, title = { Text("Atlas needs your input") },
        text = { Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            snapshot.pendingClarification?.takeIf { it.blocking }?.let { question ->
                Text(question.question, style = MaterialTheme.typography.titleMedium)
                if (question.options.isNotEmpty()) Text(question.options.joinToString(" · "))
                Text("Reply by voice or text.")
                TextButton(onClick = { showBlocker = false; showText = true }) { Text("Type an answer") }
            }
            snapshot.pendingToolCalls.filter { it.status == ToolCallStatus.WAITING_FOR_CONFIRMATION }.forEach { call ->
                Text(toolDisplayName(call.name), style = MaterialTheme.typography.titleMedium)
                call.reason?.let { Text(it) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { runAction { runtime.resolveToolCall(call.id, true) } }, enabled = active) { Text("Allow") }
                    OutlinedButton(onClick = { runAction { runtime.resolveToolCall(call.id, false) } }, enabled = active) { Text("Decline") }
                }
            }
            AtlasUiPresentation.attentionTasks(snapshot).forEach { task ->
                Text(task.objective, maxLines = 3)
                Text("Waiting: ${task.waitReason.replace('_', ' ')}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (task.waitReason !in setOf("clarification", "authorization", "unknown_operation_outcome", "task_allowance"))
                        TextButton(onClick = { runAction { runtime.resumeExecution(task.id) } }, enabled = active) { Text("Resume task") }
                    TextButton(onClick = { runtime.cancelExecution(task.id) }) { Text("Cancel task") }
                }
            }
            if (!active) Text("Resume the session to respond or approve an action.")
        } }, confirmButton = { TextButton(onClick = { showBlocker = false }) { Text("Close") } },
    )
}

@Composable
private fun VoiceDock(snapshot: RuntimeSnapshot, active: Boolean, canTalk: Boolean, onTalk: () -> Unit, onObserve: () -> Unit, onToggleText: () -> Unit, needsUser: Boolean = false) {
    val glow = if (needsUser) {
        val transition = rememberInfiniteTransition(label = "User action alert")
        val pulse by transition.animateFloat(.25f, .7f, infiniteRepeatable(tween(1800), RepeatMode.Reverse), label = "Amber glow")
        pulse
    } else 0f
    val working = snapshot.phase in setOf(RuntimePhase.THINKING, RuntimePhase.CAPTURING) || snapshot.executionTasks.any { it.state == com.grinningfrog.atlas.runtime.ExecutionState.RUNNING }
    val liveVoice = snapshot.listeningState != ListeningState.INACTIVE || snapshot.phase == RuntimePhase.SPEAKING
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (snapshot.partialTranscript?.isNotBlank() == true) Text(snapshot.partialTranscript.orEmpty(), maxLines = 2, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(AtlasUiPresentation.voiceLabel(snapshot, active), style = MaterialTheme.typography.labelLarge, color = if (liveVoice) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onObserve, enabled = active && snapshot.activeTurn == null, modifier = Modifier.size(52.dp).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)) { Icon(Icons.Default.CameraAlt, "Observe now") }
            IconButton(onClick = onTalk, enabled = canTalk, modifier = Modifier.size(72.dp).semantics { stateDescription = if (needsUser) "Atlas needs your input" else AtlasUiPresentation.voiceLabel(snapshot, active) }.shadow(if (needsUser) (4 + glow * 12).dp else 0.dp, CircleShape, ambientColor = AtlasAmber, spotColor = AtlasAmber).border(if (needsUser) 2.dp else 1.dp, if (needsUser) AtlasAmber.copy(alpha = glow) else if (liveVoice || working) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape).background(if (liveVoice || working) MaterialTheme.colorScheme.primary.copy(alpha = .1f) else Color.Transparent, CircleShape)) {
                Icon(if (snapshot.phase == RuntimePhase.SPEAKING) Icons.Default.GraphicEq else Icons.Default.Mic, if (snapshot.phase == RuntimePhase.SPEAKING) "Interrupt and talk" else "Talk to Atlas", Modifier.size(30.dp), tint = if (canTalk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onToggleText, enabled = active, modifier = Modifier.size(52.dp).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)) { Icon(Icons.AutoMirrored.Filled.Send, "Type a message") }
        }
    }
}

private fun activeWorkLabel(snapshot: RuntimeSnapshot) = when (snapshot.phase) {
    RuntimePhase.CAPTURING -> "OBSERVING"
    RuntimePhase.THINKING -> "ACTIVE TURN"
    RuntimePhase.SPEAKING -> "RESPONDING"
    else -> snapshot.activeTurn?.status?.name?.replace('_', ' ') ?: "ACTIVE"
}

private fun activeWorkDetail(snapshot: RuntimeSnapshot) = when {
    snapshot.activeTurn?.status == com.grinningfrog.atlas.model.TurnStatus.SOFT_TIMED_OUT -> "The interaction timed out. Atlas is preserving any late completion for diagnosis."
    snapshot.phase == RuntimePhase.CAPTURING -> "Taking a current physical sample"
    snapshot.phase == RuntimePhase.THINKING -> "Building a response from current context"
    else -> "Step ${snapshot.activeTurn?.stepCount ?: 0}"
}

private fun toolDisplayName(name: String) = name.replace('_', ' ').replaceFirstChar { it.uppercase() }

@Composable
private fun statusColor(status: String): Color = when (status.uppercase()) {
    "ERROR" -> MaterialTheme.colorScheme.error
    "DEGRADED", "SOFT_TIMED_OUT", "PERMISSIONS" -> AtlasAmber
    "STOPPED", "PAUSED", "DONE", "READY" -> MaterialTheme.colorScheme.onSurfaceVariant
    else -> MaterialTheme.colorScheme.primary
}

@Composable
private fun SystemPage(
    runtime: AtlasMobileRuntime,
    snapshot: RuntimeSnapshot,
    settings: SecureSettings,
    providers: List<ProviderEndpoint>,
    managedAccount: ManagedAccountClient,
    chatGptAuth: ChatGptAuthManager,
    database: AtlasDatabase,
    archive: SessionArchive,
    modifier: Modifier,
    onProvidersChanged: () -> Unit,
) {
    var section by remember { mutableStateOf("Inference") }
    val idleStatus by runtime.idleStatus?.collectAsState() ?: remember { mutableStateOf(IdleRuntimeStatus()) }
    Column(modifier.fillMaxSize()) {
        if (com.grinningfrog.atlas.dev.DevelopmentPanel.available) {
            TextButton(onClick = { section = "Developer" }) { Text("DEV · Diagnostics") }
        }
        SystemSectionTabs(section, { section = it })
        Box(Modifier.weight(1f)) {
            when (section) {
                "Developer" -> com.grinningfrog.atlas.dev.DevelopmentPanel.Content(snapshot.session?.id)
                "Inference" -> ProviderPage(settings, providers, managedAccount, chatGptAuth, database, Modifier.fillMaxSize(), onProvidersChanged)
                "Intent" -> IntentRuntimePage(runtime, idleStatus, snapshot, Modifier.fillMaxSize())
                "Tools" -> CapabilityToolsPage(settings, Modifier.fillMaxSize())
                else -> EventsPage(runtime, snapshot, settings, archive, Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun CapabilityToolsPage(settings: SecureSettings, modifier: Modifier) {
    val context = LocalContext.current
    var locationGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        locationGranted = result.values.any { it } ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
    var braveKey by remember { mutableStateOf("") }
    var searchConfigured by remember { mutableStateOf(!settings.braveSearchKey().isNullOrBlank()) }
    LazyColumn(modifier, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            AtlasBrandHeader("ATLAS TOOLS", "Device-owned")
            Spacer(Modifier.height(14.dp))
            Text("Capabilities", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
            Text("Providers can propose these tools. Atlas applies policy, asks when required, executes them, and records the result.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Location", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(if (locationGranted) "● Android permission granted" else "Permission not granted", color = if (locationGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Enables foreground current-location and reverse-geocoding tools. Atlas asks before sharing a fresh location with a model.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!locationGranted) Button(onClick = {
                        locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
                    }) { Text("Enable location") }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Structured findings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Available", color = MaterialTheme.colorScheme.primary)
                    Text("Atlas can preserve capability ideas, bugs, UX issues, policy gaps, and proposed improvements as structured session events included in exports.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Independent web search", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(if (searchConfigured) "● Brave fallback configured" else "Optional", color = if (searchConfigured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("ChatGPT-hosted search is available automatically when the connected model and account permit it. Configure Brave only as a provider-independent fallback for local, LAN, or other inference routes.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(braveKey, { braveKey = it }, label = { Text(if (searchConfigured) "Replace Brave Search API key" else "Brave Search API key") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { settings.saveBraveSearchKey(braveKey); searchConfigured = true; braveKey = "" }, enabled = braveKey.isNotBlank()) { Text("Save") }
                        if (searchConfigured) TextButton(onClick = { settings.saveBraveSearchKey(""); searchConfigured = false }) { Text("Remove") }
                    }
                }
            }
        }
    }
}

@Composable
private fun IntentRuntimePage(runtime: AtlasMobileRuntime, status: IdleRuntimeStatus, snapshot: RuntimeSnapshot, modifier: Modifier) {
    LazyColumn(modifier, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            AtlasBrandHeader("IDLE RUNTIME", status.lastDecision.name)
            Spacer(Modifier.height(14.dp))
            Text("Persistent intent", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
            Text("Core keeps unfinished work durable. Models are optional, bounded capabilities.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Autonomy mode", fontWeight = FontWeight.Bold)
                    IntentAutonomyMode.entries.forEach { mode ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(mode.name, fontWeight = if (status.mode == mode) FontWeight.Bold else FontWeight.Normal)
                                Text(modeDescription(mode), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (status.mode != mode) TextButton(onClick = { runtime.setIntentAutonomyMode(mode) }) { Text("Select") }
                        }
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("Runtime status", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("${status.cognitionMode.name} · pressure ${(status.runtimePressure * 100).toInt()}%")
                    Text(status.lastReason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    status.nextEvaluationAtMs?.let { Text("Next evaluation ${DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(it))}", style = MaterialTheme.typography.bodySmall) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (status.paused) Button({ runtime.resumeIdleRuntime() }) { Text("Resume") }
                        else OutlinedButton({ runtime.pauseIdleRuntime() }) { Text("Pause") }
                        OutlinedButton({ runtime.forceIdleEvaluation() }) { Text("Evaluate now") }
                    }
                }
            }
        }
        item {
            val t = status.telemetry
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Activity telemetry", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Runtime evaluations ${t.runtimeEvaluations} · deterministic work ${t.deterministicWorkUnits}")
                    Text("Local calls ${t.localModelCalls} · cloud calls ${t.cloudModelCalls}")
                    Text("Idle tokens ${t.idleInputTokens + t.idleOutputTokens} · cloud cost \$${"%.4f".format(t.idleCloudCostUsd)}")
                    val remaining = status.budgetRemaining
                    Text("Today remaining: ${remaining.inputTokens + remaining.outputTokens} tokens · \$${"%.4f".format(remaining.cloudCostUsd)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            val domains = remember(snapshot.recentEvents) { inferenceDomains(snapshot.recentEvents) }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Inference by runtime domain", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Interactive ${domains["INTERACTIVE"] ?: 0} · perception ${domains["PERCEPTION"] ?: 0}")
                    Text("Memory ${domains["MEMORY"] ?: 0} · persistent intent ${domains["INTENT"] ?: 0} · diagnostics ${domains["DIAGNOSTIC"] ?: 0}")
                    Text("Current session event window. Intent autonomy controls only INTENT.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (status.topIntents.isEmpty()) item { NoticeCard("No active intents", "Nothing is valid idle work. Zero model calls is correct behavior.") }
        else {
            item { Text("Top intents", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            items(status.topIntents, key = { it.intent.id }) { ranked ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(ranked.intent.subject, fontWeight = FontWeight.SemiBold)
                        Text("${ranked.intent.state.name} · score ${(ranked.score.pressure * 100).toInt()}%", color = MaterialTheme.colorScheme.primary)
                        ranked.eligibility.reason?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        ranked.intent.nextAction?.let { Text("Next: $it", style = MaterialTheme.typography.bodySmall) }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (IntentStateMachineUi.canDormant(ranked.intent.state)) TextButton({ runtime.changeIntentState(ranked.intent.id, IntentState.DORMANT) }) { Text("Dormant") }
                            if (ranked.intent.state in setOf(IntentState.DORMANT, IntentState.WAITING_EVENT, IntentState.WAITING_USER, IntentState.FAILED, IntentState.BLOCKED)) TextButton({ runtime.changeIntentState(ranked.intent.id, IntentState.ELIGIBLE) }) { Text("Reactivate") }
                            TextButton({ runtime.changeIntentState(ranked.intent.id, IntentState.ABANDONED) }) { Text("Abandon") }
                        }
                    }
                }
            }
        }
    }
}

private object IntentStateMachineUi {
    fun canDormant(state: IntentState) = state in setOf(IntentState.NEW, IntentState.ELIGIBLE, IntentState.BLOCKED)
}

private fun inferenceDomains(events: List<AtlasEvent>): Map<String, Int> = events.asSequence()
    .filter { it.type == "provider.requested" }
    .mapNotNull { event -> runCatching { org.json.JSONObject(event.dataJson).optString("inferenceDomain").takeIf(String::isNotBlank) }.getOrNull() }
    .groupingBy { it }
    .eachCount()

private fun modeDescription(mode: IntentAutonomyMode) = when (mode) {
    IntentAutonomyMode.OFF -> "No persistent idle intent processing"
    IntentAutonomyMode.PERSIST_ONLY -> "Continuity only; no autonomous work or model calls"
    IntentAutonomyMode.DETERMINISTIC -> "Approved non-inference work; hard zero model calls"
    IntentAutonomyMode.LOCAL -> "Local/private inference allowed; cloud forbidden"
    IntentAutonomyMode.BUDGETED -> "Local and cloud inference within hard budgets"
}

@Composable
private fun SessionPage(runtime: AtlasMobileRuntime, snapshot: RuntimeSnapshot, hasProvider: Boolean, mediaRepository: MediaRepository, actionScope: kotlinx.coroutines.CoroutineScope, modifier: Modifier, onConfigureInference: () -> Unit) {
    var name by remember { mutableStateOf("Everyday Atlas") }
    var goal by remember { mutableStateOf("Help me understand and act safely in my current surroundings.") }
    var prompt by remember { mutableStateOf("") }
    var actionError by remember { mutableStateOf<String?>(null) }
    val session = snapshot.session
    val active = session?.status == SessionStatus.ACTIVE
    val awaitingClarification = snapshot.pendingClarification != null

    fun runAction(block: suspend () -> Unit) {
        actionScope.launch { runCatching { block() }.onFailure { actionError = it.message ?: it.javaClass.simpleName } }
    }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            AtlasBrandHeader("PHYSICAL INTELLIGENCE", snapshot.phase.name)
            Spacer(Modifier.height(20.dp))
            Text("Stay with me.", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black)
            Text("One continuous thread, owned by this phone. The model is only where Atlas thinks.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (!hasProvider) item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Connect inference to begin", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text("Use a local, LAN, or cloud OpenAI-compatible endpoint. No Atlas account is required.")
                    Button(onClick = onConfigureInference) { Text("Set up inference") }
                }
            }
        }

        if (snapshot.session == null || snapshot.session.status == SessionStatus.DONE) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("New physical session", style = MaterialTheme.typography.titleLarge)
                        OutlinedTextField(name, { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(goal, { goal = it }, label = { Text("Goal") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                        Button({ runAction { runtime.createAndStartSession(name, goal) } }, enabled = hasProvider, modifier = Modifier.fillMaxWidth()) {
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

        snapshot.turnDiagnostics.forEach { report ->
            item(key = "diagnostics-${report.turn.id}") { TurnDiagnosticsCard(report) }
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

        if (session != null && session.status != SessionStatus.DONE) item {
            val canTalk = active && (snapshot.activeTurn == null || snapshot.phase == RuntimePhase.SPEAKING || awaitingClarification ||
                snapshot.activeTurn?.status == com.grinningfrog.atlas.model.TurnStatus.SOFT_TIMED_OUT)
            val title = when {
                snapshot.listeningState == ListeningState.PREPARING -> "Getting the microphone ready"
                snapshot.listeningState == ListeningState.READY -> "Speak now"
                snapshot.listeningState == ListeningState.HEARING -> "I’m listening"
                snapshot.listeningState == ListeningState.PROCESSING -> "Got it"
                snapshot.phase == RuntimePhase.SPEAKING -> "Atlas is speaking"
                snapshot.phase == RuntimePhase.THINKING -> "Atlas is thinking"
                else -> "Talk to Atlas"
            }
            val detail = when {
                !active -> "Resume the session to talk."
                snapshot.listeningState == ListeningState.PREPARING -> "Wait for the soft chirp and haptic pulse."
                snapshot.listeningState == ListeningState.READY -> "The microphone is live."
                snapshot.listeningState == ListeningState.HEARING -> snapshot.partialTranscript ?: "Keep going — Atlas will detect when you finish."
                snapshot.listeningState == ListeningState.PROCESSING -> snapshot.partialTranscript ?: "Turning your speech into a message."
                snapshot.phase == RuntimePhase.SPEAKING -> "Tap below to interrupt and speak. Atlas remembers only completed sentences as heard."
                snapshot.phase == RuntimePhase.THINKING -> "Building a short, context-aware response."
                else -> "Tap once, then begin after the subtle haptic and chirp."
            }
            Card(colors = CardDefaults.cardColors(containerColor = when {
                snapshot.listeningState in setOf(ListeningState.READY, ListeningState.HEARING) -> MaterialTheme.colorScheme.primaryContainer
                snapshot.phase == RuntimePhase.SPEAKING -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surface
            })) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(if (snapshot.phase == RuntimePhase.SPEAKING) Icons.Default.GraphicEq else Icon…19815 tokens truncated…tools. */
    fun reconcileLateInference(turnId: String, requestId: String, input: Long?, output: Long?) = atomic {
        val task=forTurn(turnId) ?: return@atomic
        val reservation=task.document.optJSONObject("inference_reservations")?.optJSONObject(requestId) ?: return@atomic
        reconcileUsage(task,requestId,reservation,input,output)
        event(task,"execution.late_usage_reconciled",JSONObject().put("request_id",requestId).put("turn_id",turnId))
    }
    private fun reconcileUsage(task: ExecutionTask, requestId: String, reservation: JSONObject, input: Long?, output: Long?) {
        task.usage.put("input_tokens",maxOf(0,task.usage.optLong("input_tokens")-reservation.optLong("input")+(input ?: reservation.optLong("input"))))
            .put("output_tokens",maxOf(0,task.usage.optLong("output_tokens")-reservation.optLong("output")+(output ?: reservation.optLong("output"))))
        task.document.optJSONObject("inference_reservations")?.remove(requestId)
        if(task.document.optJSONObject("reservation")?.optString("request_id")==requestId)task.document.remove("reservation")
        save(task)
    }
    /** Truncation yields for continuation, but repeated incomplete output is a diagnosable wait. */
    fun observeResponse(lease: ExecutionLease, finishReason: String?): Boolean = atomic {
        check(lease); val task=get(lease.taskId)!!
        val truncated=finishReason in setOf("length", "max_tokens", "max_output_tokens")
        val count=if(truncated) task.document.optInt("consecutive_cutoffs")+1 else 0
        task.document.put("consecutive_cutoffs",count);save(task)
        if(count>=3) control(task.id,ExecutionState.WAITING,"context_cutoff")
        !truncated
    }
    fun reserveTool(lease: ExecutionLease, executionTurnId: String? = null, operationId: String? = null): Boolean = atomic {
        check(lease);val task=get(lease.taskId)!!;if(!available(task.id)) return@atomic false
        task.usage.put("tool_calls",task.usage.optLong("tool_calls")+1);save(task.copy(leaseUntil=now()+LEASE_MS))
        if(executionTurnId!=null && operationId!=null) event(task,"execution.tool_dispatched",JSONObject().put("turnId",executionTurnId).put("operation_id",operationId))
        true
    }
    fun finish(lease: ExecutionLease, outcome: SliceOutcome, activeMs: Long): ExecutionTask? = atomic {
        if(!valid(lease)) return@atomic get(lease.taskId)
        val task=get(lease.taskId)!!;task.usage.put("active_ms",task.usage.optLong("active_ms")+activeMs.coerceAtLeast(0));task.document.remove("active_started_at")
        save(task)
        task.document.put("consecutive_retries",0)
        val all=calls(task.id);val pending=pending(task.id).any { it.status==ToolCallStatus.UNKNOWN || it.status==ToolCallStatus.RUNNING }
        val last=all.groupBy { it.name }.values.mapNotNull { it.lastOrNull() }
        val superseded = all.filter(::failedOperation).mapNotNull { call -> (supersedingControl(call, all) ?: supersedingActivation(call, all))?.let { call to it } }.toMap()
        val diagnosticFailures=last.filter { nonBlockingAuditFailure(it, task, all) }
        val failures=last.filter { it !in diagnosticFailures && it !in superseded }.filter(::failedOperation)
        val work = ExecutionWorkState.build(all, task.document.optJSONObject("workspace_target")?.optString("workspace_id"))
        val requirement=task.document.optJSONObject("completion_requirement")
        val missingCriteria=work.optJSONObject("latest_candidate")?.optString("candidate_id")?.let {missingAcceptance(currentTurn(task.id),it)} ?: emptyList()
        work.put("missing_criteria",JSONArray(missingCriteria))
        val pixelsPending=!pixelsSatisfied(task,all)
        val deliveryPending=(missingCriteria.isNotEmpty() || pixelsPending) || requirement?.optString("delivery")=="activation" &&
            requirement?.optString("workspace_id")==task.document.optJSONObject("workspace_target")?.optString("workspace_id") &&
            work.optString("delivery_status")!="activated"
        val signature=failures.joinToString("|") { call ->
            if (recoverableVerification(call)) {
                val r=ExecutionWorkState.receipt(call)
                JSONObject().put("tool",call.name).put("error",call.error).apply {
                    for (key in listOf("content_hash","passed","detail","host_failures","missing_operations","missing_criteria","failed_steps","failed_fixtures","notification_verified"))
                        if(r.has(key))put(key,r.get(key))
                    r.optJSONArray("failed_steps")?.let { steps -> for(i in 0 until steps.length()) {
                        steps.getJSONObject(i).remove("ui_state");steps.getJSONObject(i).remove("recovery_guidance")
                    } }
                }.toString()
            } else call.name+":"+(call.error ?: call.resultJson.orEmpty()).take(1000)
        }
        val completionSignature=if(deliveryPending) "activation_required:"+(work.optJSONObject("latest_candidate")?.optString("content_hash") ?: "no_candidate") else ""
        val effectiveSignature=signature+completionSignature
        val failedThisSlice=failures.any { it.turnId==currentTurn(task.id) }
        val priorFrontier = task.document.optJSONObject("work_state")?.optString("frontier")
        val madeProgress = priorFrontier != work.getString("frontier")
        val stagnant = if (madeProgress) 0 else task.document.optInt("no_progress_slices") + 1
        task.document.put("no_progress_slices", stagnant)
        work.put("no_progress_slices", stagnant)
        task.document.put("work_state", work)
        event(task, "execution.progress", JSONObject().put("frontier_changed", madeProgress).put("no_progress_slices", stagnant))
        val repeatedCalls=failures.filter(ToolRetryPolicy::deterministicFailure).maxOfOrNull { failed -> all.count { prior ->
            prior.name==failed.name && ToolRetryPolicy.sameArguments(prior.argumentsJson,failed.argumentsJson) && ToolRetryPolicy.deterministicFailure(prior)
        } } ?: 0
        val repeated=maxOf(repeatedCalls, if(effectiveSignature.isNotBlank() && (failedThisSlice || !madeProgress) && effectiveSignature==task.document.optString("failure_signature")) task.document.optInt("repeated_failures")+1 else if(failedThisSlice || deliveryPending) 1 else 0)
        task.document.put("failure_signature",effectiveSignature).put("repeated_failures",repeated)
        val recent=all.takeLast(20)
        val definitionId=all.lastOrNull { it.name == "workspace_package_activate" || runCatching {JSONObject(it.argumentsJson).has("definition")}.getOrDefault(false) }?.id ?: task.document.getJSONObject("checkpoint").optString("latest_definition_operation_id").takeIf { it.isNotBlank() && it!="null" }
        task.document.put("checkpoint",JSONObject().put("last_turn_id",currentTurn(task.id)).put("operation_ids",JSONArray(recent.map {it.id}))
            .put("diagnostic_operation_ids", JSONArray(diagnosticFailures.map { it.id }))
            .put("unresolved_operation_ids", JSONArray(failures.map { it.id }))
            .put("resolved_operations", JSONArray(superseded.map { (failed, success) -> JSONObject().put("operation_id", failed.id)
                .put("tool", failed.name).put("occurred_at_ms", failed.updatedAtMs).put("resolved_at_ms", success.updatedAtMs)
                .put("resolved_by", success.id).put("resolution", if (failed.name == ToolHarness.CLARIFICATION_TOOL) "corrected_control_accepted" else "superseded_by_committed_activation") }))
            .put("completed_operations",JSONArray(recent.filter {it.status==ToolCallStatus.COMPLETED}.map {it.id}))
            .put("latest_definition_operation_id",definitionId))
        val recover = outcome == SliceOutcome.RESPONDED && (deliveryPending || failures.isNotEmpty()) && failures.all(::recoverableVerification)
        val reason=when { pending -> "unknown_operation_outcome";outcome==SliceOutcome.RESPONDED&&failures.isEmpty()&&!deliveryPending->"";!available(task.id)->"task_allowance";repeated>=3->"repeated_failure";recover -> "";outcome==SliceOutcome.RESPONDED&&failures.isNotEmpty()->"unresolved_tool_failure";else->when(outcome){SliceOutcome.WAITING_USER->"clarification";SliceOutcome.WAITING_PERMISSION->"authorization";SliceOutcome.WAITING_PROVIDER->"provider";SliceOutcome.BLOCKED->"runtime_blocker";else->""} }
        task.document.put("wait_reason_at_ms", if (reason.isNotBlank()) now() else JSONObject.NULL)
        task.document.put("wait_reason",reason).put("completion_kind",if(outcome==SliceOutcome.RESPONDED&&reason.isBlank()&&!recover) "response_finished; consult operation receipts for effects" else JSONObject.NULL)
        task.document.put("recovery_required", recover && reason.isBlank())
        if (recover && reason.isBlank()) event(task,"execution.recovery_required",JSONObject().put("reason",if(deliveryPending && failures.isEmpty()) "activation_required" else "recoverable_verification_failure").put("operation_ids",JSONArray(failures.map { it.id })))
        val state=if(reason.isNotBlank()) ExecutionState.WAITING else if(recover) ExecutionState.READY else if(outcome==SliceOutcome.RESPONDED) ExecutionState.COMPLETED else ExecutionState.READY
        val result=task.copy(state=state,owner=null,leaseUntil=0,nextRunAt=0);save(result);event(result,"execution.checkpoint",JSONObject().put("outcome",outcome.name).put("checkpoint",task.document.getJSONObject("checkpoint")).put("wait_reason",reason));result
    }
    private fun supersedingControl(call: AtlasToolCall, all: List<AtlasToolCall>): AtlasToolCall? {
        if (call.name != ToolHarness.CLARIFICATION_TOOL || call.status != ToolCallStatus.REJECTED) return null
        val args = runCatching { JSONObject(call.argumentsJson) }.getOrNull() ?: return null
        return all.drop(all.indexOf(call) + 1).firstOrNull {
            if (it.name != call.name || it.status != ToolCallStatus.COMPLETED) false else {
                val result = runCatching { JSONObject(it.resultJson.orEmpty()) }.getOrDefault(JSONObject())
                val next = runCatching { JSONObject(it.argumentsJson) }.getOrDefault(JSONObject())
                result.optBoolean("accepted") && next.optString("action") == args.optString("action") &&
                    next.optString("clarification_id") == args.optString("clarification_id")
            }
        }
    }

    private fun recoverableVerification(call: AtlasToolCall) =
        (call.name in setOf("workspace_package_test", "workspace_package_ui_test", "workspace_package_validate") ||
            call.name=="workspace_package_read_file" && call.status==ToolCallStatus.FAILED && call.error.orEmpty().startsWith("EMPTY_PACKAGE_DRAFT") ||
            call.name=="workspace_package_stage" && (call.status==ToolCallStatus.COMPLETED && runCatching {JSONObject(call.resultJson.orEmpty()).optString("error")=="STAGING_ARGUMENTS_INVALID"}.getOrDefault(false) || call.status==ToolCallStatus.FAILED && (call.error.orEmpty().startsWith("VALIDATION_FAILED") || call.error.orEmpty().startsWith("Unknown fields:"))) ||
            call.name=="workspace_package_activate" && call.status==ToolCallStatus.COMPLETED && runCatching {JSONObject(call.resultJson.orEmpty()).optString("error") in setOf("ACCEPTANCE_REQUIRED","CHECK_REQUIRED")}.getOrDefault(false)) &&
        call.status in setOf(ToolCallStatus.COMPLETED, ToolCallStatus.REJECTED, ToolCallStatus.FAILED)

    private fun failedOperation(call: AtlasToolCall): Boolean = call.status in setOf(ToolCallStatus.FAILED, ToolCallStatus.REJECTED) ||
        (call.status == ToolCallStatus.COMPLETED && runCatching { JSONObject(call.resultJson.orEmpty()).let { result ->
            listOf("valid", "ok", "passed", "structurally_valid").any { result.has(it) && !result.optBoolean(it) }
        } }.getOrDefault(false))

    /** Only discarded, side-effect-free candidate attempts can be superseded. Unknown outcomes never are. */
    private fun supersedingActivation(call: AtlasToolCall, all: List<AtlasToolCall>): AtlasToolCall? {
        if(call.name=="workspace_accept_unverified" && call.status in setOf(ToolCallStatus.FAILED,ToolCallStatus.REJECTED)) {
            val args=runCatching {JSONObject(call.argumentsJson)}.getOrNull() ?: return null
            val workspace=args.optString("workspace_id").takeIf(String::isNotBlank) ?: return null
            val candidate=args.optString("candidate_id").takeIf(String::isNotBlank) ?: return null
            return all.drop(all.indexOf(call)+1).lastOrNull { success ->
                success.name=="workspace_package_activate" && success.status==ToolCallStatus.COMPLETED &&
                    runCatching {val r=receipt(success)?.let(::JSONObject) ?: return@runCatching false
                        r.optBoolean("activated") && r.optString("workspace_id")==workspace && r.optString("candidate_id")==candidate &&
                            database.loadComposableWorkspace(workspace)?.liveRevisionId==r.optString("revision_id")
                    }.getOrDefault(false)
            }
        }
        if (call.name !in setOf("workspace_package_stage", "workspace_package_test", "workspace_package_ui_test", "workspace_package_validate") ||
            call.status !in setOf(ToolCallStatus.FAILED, ToolCallStatus.REJECTED, ToolCallStatus.COMPLETED)) return null
        val args = runCatching { JSONObject(call.argumentsJson) }.getOrNull() ?: return null
        val workspace = args.optString("workspace_id").takeIf(String::isNotBlank) ?: return null
        val base = args.optString("base_revision_id").takeIf(String::isNotBlank) ?: args.optString("candidate_id").takeIf(String::isNotBlank)?.let { candidate ->
            database.readableDatabase.rawQuery("SELECT base_revision_id FROM workspace_package_candidates WHERE workspace_id=? AND candidate_id=?", arrayOf(workspace, candidate)).use { if (it.moveToFirst()) it.getString(0) else null }
        } ?: return null
        return all.drop(all.indexOf(call) + 1).lastOrNull { success ->
            if (success.name != "workspace_package_activate" || success.status != ToolCallStatus.COMPLETED) false
            else runCatching {
                val receipt = receipt(success)?.let(::JSONObject) ?: return@runCatching false
                receipt.optBoolean("activated") && receipt.optString("workspace_id") == workspace && receipt.optString("previous_revision_id") == base &&
                    database.loadComposableWorkspace(workspace)?.liveRevisionId == receipt.optString("revision_id")
            }.getOrDefault(false)
        }
    }
    private fun nonBlockingAuditFailure(call: AtlasToolCall, task: ExecutionTask, all: List<AtlasToolCall>): Boolean {
        if(call.name=="atlas_task_handoff_pixels" && call.status in setOf(ToolCallStatus.FAILED,ToolCallStatus.REJECTED)) {
            val requirement=task.document.optJSONObject("completion_requirement")
            if(requirement?.optBoolean("require_pixels")==true)return false
            val workspace=task.document.optJSONObject("workspace_target")?.optString("workspace_id") ?: return false
            return all.any { success -> success.name=="workspace_package_activate" && success.status==ToolCallStatus.COMPLETED &&
                runCatching { val r=receipt(success)?.let(::JSONObject) ?: return@runCatching false
                    r.optBoolean("activated") && r.optString("workspace_id")==workspace && database.loadComposableWorkspace(workspace)?.liveRevisionId==r.optString("revision_id")
                }.getOrDefault(false) }
        }
        if(call.name=="workspace_package_read_file" && call.status==ToolCallStatus.FAILED && call.error.orEmpty().startsWith("EMPTY_PACKAGE_DRAFT")) {
            val workspace=runCatching {JSONObject(call.argumentsJson).getString("workspace_id")}.getOrNull() ?: return false
            return all.drop(all.indexOf(call)+1).any {next -> next.name=="workspace_package_stage" && next.status==ToolCallStatus.COMPLETED && runCatching {val r=JSONObject(next.resultJson.orEmpty());r.optString("workspace_id")==workspace && r.optBoolean("structurally_valid")}.getOrDefault(false)}
        }
        if(call.name=="workspace_capture_view" && call.status==ToolCallStatus.FAILED) {
            val args=runCatching {JSONObject(call.argumentsJson)}.getOrDefault(JSONObject())
            val requirement=task.document.optJSONObject("completion_requirement")
            if(requirement?.optBoolean("require_pixels")==true)return false
            val error=call.error.orEmpty()+call.resultJson.orEmpty()
            if(!error.contains("WORKSPACE_NOT_OPEN") && !error.contains("Target workspace is not open for pixel capture") && !error.contains("Workspace panel is not visible or has not drawn this revision"))return false
            if(requirement?.optString("pixels_owner")=="user" && requirement.optString("workspace_id")==args.optString("workspace_id"))return true
            return all.any { success -> success.name=="workspace_package_activate" && success.status==ToolCallStatus.COMPLETED &&
                runCatching {val r=receipt(success)?.let(::JSONObject) ?: return@runCatching false
                    r.optBoolean("activated") && r.optString("workspace_id")==args.optString("workspace_id") &&
                    database.loadComposableWorkspace(r.getString("workspace_id"))?.liveRevisionId==r.optString("revision_id")
                }.getOrDefault(false) }
        }
        if (call.name != CapabilityDiscovery.TOOL || call.status != ToolCallStatus.FAILED) return false
        val args = runCatching { JSONObject(call.argumentsJson) }.getOrNull() ?: return false
        val result = runCatching { JSONObject(call.resultJson.orEmpty()) }.getOrNull() ?: return false
        if (args.optString("stage") != "resume_local" || args.has("blocker_id") || result.optString("error_code") != "missing_blocker") return false
        if (CapabilityDiscovery(database).active(currentTurn(task.id))) return false
        val mutations = setOf("workspace_package_create", "workspace_package_activate", "workspace_create", "workspace_replace_definition", "workspace_patch_definition", "workspace_update_metadata",
            "workspace_add_record", "workspace_update_record", "workspace_delete_record", "workspace_restore_record")
        // A completion claim alone is insufficient: require an atomic committed effect receipt.
        return all.any { it.name in mutations && it.status == ToolCallStatus.COMPLETED && receipt(it) != null }
    }
    private fun pixelsSatisfied(task: ExecutionTask, all: List<AtlasToolCall>): Boolean {
        val requirement=task.document.optJSONObject("completion_requirement") ?: return true
        if(!requirement.optBoolean("require_pixels"))return true
        val workspace=requirement.optString("workspace_id")
        val revision=database.loadComposableWorkspace(workspace)?.liveRevisionId ?: return false
        return all.any {it.name=="workspace_capture_view" && it.status==ToolCallStatus.COMPLETED && runCatching {
            val r=JSONObject(it.resultJson.orEmpty());r.optBoolean("ok") && r.optString("workspace_id")==workspace && r.optString("revision_id")==revision
        }.getOrDefault(false)}
    }
    fun outstanding(taskId: String): JSONArray {
        val task=get(taskId) ?: return JSONArray();val all=calls(taskId)
        return JSONArray(all.groupBy {it.name}.values.mapNotNull {it.lastOrNull()}.filter {
            failedOperation(it) && !nonBlockingAuditFailure(it,task,all) && supersedingControl(it,all)==null && supersedingActivation(it,all)==null
        }.map {JSONObject().put("operation_id",it.id).put("tool",it.name).put("error",it.error).put("result",it.resultJson?.take(1200))})
    }
    /** Repair stale optional-inspection waits before creating another inference slice. */
    fun reconcileDelivered(taskId: String): ExecutionTask? = atomic {
        val task=get(taskId) ?: return@atomic null
        if(task.state !in setOf(ExecutionState.WAITING,ExecutionState.PAUSED,ExecutionState.READY) ||
            task.waitReason !in setOf("unresolved_tool_failure","repeated_failure") || pending(taskId).isNotEmpty())return@atomic task
        val work=ExecutionWorkState.build(calls(taskId),task.document.optJSONObject("workspace_target")?.optString("workspace_id"))
        val activation=work.optJSONObject("activation") ?: return@atomic task
        if(work.optString("delivery_status")!="activated" || database.loadComposableWorkspace(activation.optString("workspace_id"))?.liveRevisionId!=activation.optString("revision_id") || outstanding(taskId).length()>0)return@atomic task
        if(missingAcceptance(currentTurn(taskId),activation.optString("candidate_id")).isNotEmpty() || !pixelsSatisfied(task,calls(taskId)))return@atomic task
        val checkpoint=task.document.optJSONObject("checkpoint") ?: JSONObject()
        val diagnostics=calls(taskId).filter {nonBlockingAuditFailure(it,task,calls(taskId))}.map {it.id}
        checkpoint.put("unresolved_operation_ids",JSONArray()).put("diagnostic_operation_ids",JSONArray(diagnostics))
        task.document.put("checkpoint",checkpoint)
        task.document.put("work_state",work).put("wait_reason","").put("recovery_required",false).put("completion_kind","delivered; optional inspection unavailable")
        val result=task.copy(state=ExecutionState.COMPLETED,generation=task.generation+1,owner=null,leaseUntil=0,nextRunAt=0)
        save(result);event(result,"execution.delivery_reconciled");result
    }
    fun control(taskId: String, state: ExecutionState, reason: String, budget: ExecutionBudget? = null): ExecutionTask? = atomic {
        val t=get(taskId) ?: return@atomic null
        if(t.state in setOf(ExecutionState.COMPLETED,ExecutionState.CANCELLED,ExecutionState.FAILED)) return@atomic t
        if(t.state==ExecutionState.RUNNING) { t.usage.put("active_ms",t.usage.optLong("active_ms")+(now()-t.document.optLong("active_started_at",now())).coerceAtLeast(0));t.document.remove("active_started_at") }
        if(state in setOf(ExecutionState.READY,ExecutionState.PAUSED,ExecutionState.CANCELLED)) reconcileLocal(t)
        if(state==ExecutionState.READY && pending(taskId).any {it.status==ToolCallStatus.UNKNOWN || it.status==ToolCallStatus.RUNNING}) return@atomic t
        if(state==ExecutionState.CANCELLED) {
            pending(taskId).filter { it.status in setOf(ToolCallStatus.PROPOSED,ToolCallStatus.APPROVED,ToolCallStatus.WAITING_FOR_CONFIRMATION) }.forEach { call ->
                val feedback=JSONObject().put("ok",false).put("error","Task cancelled before execution").toString()
                database.updateToolCall(call.id,ToolCallStatus.REJECTED,resultJson=feedback,error=reason)
                database.insertMessage(AtlasMessage(sessionId=t.sessionId,turnId=call.turnId,role=MessageRole.TOOL,content=feedback,createdAtMs=now(),kind=MessageKind.TOOL_RESULT,toolCallId=call.id))
            }
            database.updateTurn(currentTurn(taskId),TurnStatus.CANCELLED,error=reason)
            database.loadPendingClarification(t.sessionId)?.takeIf {forTurn(it.sourceTurnId)?.id==taskId}?.let {database.updateClarification(it.id,ClarificationStatus.ABANDONED)}
        }
        t.document.put("wait_reason",reason);budget?.let { t.document.put("budget",it.json()) }
        val result=t.copy(state=state,generation=t.generation+1,owner=null,leaseUntil=0,nextRunAt=0);save(result);event(result,"execution.${state.name.lowercase()}",JSONObject().put("reason",reason));result
    }
    fun retry(lease: ExecutionLease, reason: String, activeMs: Long = 0): ExecutionTask? = atomic {
        if(!valid(lease)) return@atomic get(lease.taskId)
        val t=get(lease.taskId)!!;t.usage.put("active_ms",t.usage.optLong("active_ms")+activeMs.coerceAtLeast(0));t.document.remove("active_started_at");val n=t.document.optInt("consecutive_retries")+1;t.document.put("consecutive_retries",n).put("wait_reason",reason)
        val result=t.copy(state=if(n>=8) ExecutionState.WAITING else ExecutionState.READY,owner=null,leaseUntil=0,nextRunAt=now()+minOf(60_000L,1000L shl minOf(n,6)))
        save(result);event(result,"execution.provider_retry",JSONObject().put("attempt",n).put("next_run_at",result.nextRunAt));result
    }
    fun context(taskId: String): String {
        val t=get(taskId) ?: return ""
        val work = ExecutionWorkState.build(calls(taskId), t.document.optJSONObject("workspace_target")?.optString("workspace_id"))
        val latest=calls(taskId).lastOrNull {it.name == "workspace_package_activate" || runCatching {JSONObject(it.argumentsJson).has("definition")}.getOrDefault(false)} ?: t.document.getJSONObject("checkpoint").optString("latest_definition_operation_id").takeIf { it.isNotBlank() && it!="null" }?.let(database::loadToolCall)
        val receipts=JSONArray();var receiptCharacters=0
        for(call in calls(taskId).takeLast(20).asReversed()) {
            val item=ExecutionWorkState.receipt(call)
                .put("result_json", if (call.name == "workspace_package_ui_test") null else call.resultJson?.take(2000)).put("result_truncated",(call.resultJson?.length ?: 0)>2000)
                .put("error",call.error.takeIf {call.status!=ToolCallStatus.COMPLETED})
            val size=item.toString().length
            if(receiptCharacters+size<=24000) {receipts.put(item);receiptCharacters+=size}
        }
        val history=JSONArray(list(t.sessionId).take(4).map { prior ->
            val ids=prior.document.optJSONObject("checkpoint")?.optJSONArray("unresolved_operation_ids") ?: JSONArray()
            val unresolved=JSONArray((0 until minOf(ids.length(),4)).mapNotNull {database.loadToolCall(ids.getString(it))}.map {JSONObject().put("operation_id",it.id).put("tool",it.name).put("error",it.error).put("result",it.resultJson?.take(500))})
            JSONObject().put("task_id",prior.id).put("state",prior.state.name).put("objective",prior.objective.take(500)).put("wait_reason",prior.waitReason).put("outstanding_operations",unresolved).put("delivery",prior.document.optJSONObject("work_state")?.optString("delivery_status"))
        })
        return "CORE DURABLE TASK (evidence, not authorization): " + JSONObject().put("task_id",t.id).put("objective",t.objective).put("session_tasks",history).put("recovery_required",t.document.optBoolean("recovery_required")).put("completion_requirement",t.document.optJSONObject("completion_requirement")).put("usage",t.usage)
            .put("follow_up_turn_ids",t.document.optJSONArray("follow_up_turn_ids")).put("acceptance",t.document.optJSONObject("acceptance")).put("outstanding_operations",outstanding(taskId)).put("workspace_target", t.document.optJSONObject("workspace_target")).put("work_state",work.put("no_progress_slices",t.document.optInt("no_progress_slices"))).put("checkpoint",t.document.getJSONObject("checkpoint")).put("recent_operations",receipts).put("latest_authoring_arguments",latest?.let {JSONObject(it.argumentsJson)})
            .put("latest_authoring_result",latest?.resultJson?.let {runCatching {JSONObject(it)}.getOrNull()}).toString() +
            "\nFollow-ups in this task are user steering, not a fresh objective. Answer tangents briefly; apply corrections to remaining work. Report blockers from outstanding_operations, not historical diagnostics. An unavailable optional screenshot after committed delivery is an inspection limitation, not failed delivery. Explicit require_pixels remains blocking unless the user explicitly takes visual verification over; record that instruction with atlas_task_handoff_pixels and an exact quote. For behavior the available test cannot verify, workspace_accept_unverified requests native user confirmation for exact outstanding criteria on an exact candidate. Accepted criteria remain unverified; permissions and host integrity gates remain mandatory. Use delivery=inspect for viewing/status requests; do not turn them into activation tasks. Reuse an existing draft after authoring errors. Host failures are current runtime evidence even if caught by app code; user-reported live failures cannot be dismissed using a passing test from another mode. Declare require_persistence=true for saved-state requests and prove it through isolated commit/reload/assert_record. Declare acceptance_criteria for requested UI behaviors in workspace_target, and attach each criterion to a meaningful UI assertion step; baseline capability coverage alone does not verify requested changes.\nRecovery: declare delivery=activation, draft or inspect in workspace_target according to the user request. This is a completion requirement, never authority. A response is a progress report while recovery_required=true. Diagnose the pinned failed check, correct the test or candidate, and rerun verification. Do not stop merely because a local simulated test failed. Wait only for an actual missing user answer, authorization, unavailable resource or uncertain effect. Claim activation only from a committed activation receipt matching the candidate. If the user requested a draft or review, do not activate it without authorization." +
            "\nWorkspace targeting: ambiguous commands use the captured workspace_target. Explicitly named edit targets must be resolved with workspace_target; inspect other workspaces as references without retargeting. Never change target merely because the user browses. Ask clarification for duplicate names or unclear components. Continue the pending objective from recorded receipts. work_state holds the exact latest used candidate and checks for this target. A failed UI assertion is not a workspace migration problem: compare expected and actual text, correct the test or implementation, and exercise the remaining operations. Never reconstruct or combine UUID fragments. Reuse exact receipt identifiers. Successful reads alone are not app progress. If no_progress_slices grows, reassess the failing test or edit strategy instead of repeating inspection; retain the task and existing candidate. A new slice is not a new user request, not new authority, and not proof of success. Avoid repeating committed effects."
    }
    private fun reconcileLocal(task: ExecutionTask) {
        val local=task.document.getJSONObject("scope").getJSONArray("atomic_local_tools")
        val names=(0 until local.length()).map(local::getString).toSet()
        pending(task.id).filter {it.status==ToolCallStatus.UNKNOWN}.forEach { call ->
            val receipt=receipt(call)
            if(receipt!=null) database.updateToolCall(call.id,ToolCallStatus.COMPLETED,resultJson=receipt)
            else if(call.name in names) database.updateToolCall(call.id,ToolCallStatus.FAILED,error="Atomic local operation rolled back; no effect receipt")
        }
    }
    fun recover(sessionId: String) = atomic {
        running(sessionId).forEach { task ->
            task.usage.put("active_ms",task.usage.optLong("active_ms")+(now()-task.document.optLong("active_started_at",now())).coerceIn(0,LEASE_MS));task.document.remove("active_started_at")
            reconcileLocal(task)
            val unknown=pending(task.id).any {it.status==ToolCallStatus.UNKNOWN}
            task.document.put("wait_reason",if(unknown) "unknown_operation_outcome" else "process_restart")
            val result=task.copy(state=if(unknown) ExecutionState.WAITING else ExecutionState.READY,generation=task.generation+1,owner=null,leaseUntil=0)
            save(result);event(result,"execution.recovered")
        }
    }
    fun receipt(call: AtlasToolCall): String? = database.readableDatabase.rawQuery("SELECT session_id,arguments_hash,result_json FROM local_operation_receipts WHERE operation_id=?",arrayOf(call.id)).use { c ->
        if(!c.moveToFirst()) null else {require(c.getString(0)==call.sessionId&&c.getString(1)==hash(call.name+"\n"+call.argumentsJson)){"Operation ID reused with different scope or arguments"};c.getString(2)} }
    fun localOperation(call: AtlasToolCall, lease: ExecutionLease?, execute: () -> ToolExecutionResult): ToolExecutionResult = atomic {
        receipt(call)?.let { return@atomic ToolExecutionResult(it) }
        forTurn(call.turnId)?.let { task -> require(lease != null && lease.taskId == task.id) { "A task effect requires its execution lease" };check(lease) }
        val result=execute()
        lease?.let(::check)
        database.writableDatabase.insertOrThrow("local_operation_receipts",null,ContentValues().apply {put("operation_id",call.id);put("session_id",call.sessionId);put("arguments_hash",hash(call.name+"\n"+call.argumentsJson));put("result_json",result.resultJson)})
        if(database.loadToolCall(call.id)!=null) database.updateToolCall(call.id,ToolCallStatus.COMPLETED,resultJson=result.resultJson)
        result
    }
}

/** Driver is the same foreground slice used by the app. No model-authored scheduling or extra authority. */
class DurableTaskExecutor(private val store: ExecutionTaskStore, private val now: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) }) {
    suspend fun run(taskId: String, driver: suspend (ExecutionLease, String) -> SliceOutcome): ExecutionTask? {
        val owner=UUID.randomUUID().toString()
        while(true) {
            currentCoroutineContext().ensureActive()
            val task=store.get(taskId) ?: return null
            if(task.state!=ExecutionState.READY) return task
            if(task.nextRunAt>now()) {sleep(minOf(task.nextRunAt-now(),60_000));continue}
            if(!store.available(taskId)) return store.control(taskId,ExecutionState.WAITING,"task_allowance")
            val lease=store.claim(taskId,owner) ?: return store.get(taskId)
            val turnId=store.currentTurn(taskId)
            val started=now()
            val outcome=try { driver(lease,turnId) } catch(cancelled: CancellationException) {
                val current=store.get(taskId)
                if(current?.state==ExecutionState.RUNNING && current.owner==lease.owner && current.generation==lease.generation) store.control(taskId,ExecutionState.PAUSED,"runner interrupted")
                throw cancelled
            }
            catch(error: com.grinningfrog.atlas.provider.InferenceUnavailableException) {
                if(!error.outcomeAmbiguous && error.providerCode !in setOf("subscription_sharing_usage_limit_exceeded","subscription_sharing_invalid_user","subscription_sharing_user_not_eligible")) {
                    val retry=store.retry(lease,error.message.orEmpty(),now()-started);if(retry?.state!=ExecutionState.READY) return retry;store.nextTurn(taskId);continue
                }
                return store.finish(lease,SliceOutcome.WAITING_PROVIDER,now()-started)
            } catch(error: Exception) { store.finish(lease,SliceOutcome.BLOCKED,now()-started);throw error }
            val result=store.finish(lease,outcome,now()-started) ?: return null
            if(result.state!=ExecutionState.READY) return result
            store.nextTurn(taskId)
            sleep(1) // A cooperative boundary, not an additional inference or a user interaction.
        }
    }
}
