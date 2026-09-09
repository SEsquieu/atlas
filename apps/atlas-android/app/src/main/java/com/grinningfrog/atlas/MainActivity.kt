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
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.grinningfrog.atlas.data.SecureSettings
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
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.UUID

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
private enum class AppPage { ATLAS, SESSION, SYSTEM }

@Composable
private fun AtlasApp(
    runtime: AtlasMobileRuntime?,
    settings: SecureSettings,
    mediaRepository: MediaRepository,
    managedAccount: ManagedAccountClient,
    sessionArchive: SessionArchive,
    onboardingComplete: Boolean,
    permissionsGranted: Boolean,
    onCompleteOnboarding: () -> Unit,
    onRequestPermissions: () -> Unit,
    onOpenSystemSettings: () -> Unit,
) {
    var page by remember { mutableStateOf(AppPage.ATLAS) }
    val sessionActionScope = rememberCoroutineScope()
    var providerRevision by remember { mutableIntStateOf(0) }
    val providers = remember(providerRevision) { settings.loadProviders() }
    val snapshot by runtime?.state?.collectAsState() ?: remember { mutableStateOf(RuntimeSnapshot()) }

    if (!onboardingComplete) {
        Onboarding(onCompleteOnboarding)
        return
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Column {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .7f))
                NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
                    NavigationBarItem(page == AppPage.ATLAS, { page = AppPage.ATLAS }, { Icon(Icons.Default.GraphicEq, null) }, label = { Text("Atlas") }, colors = atlasNavigationColors())
                    NavigationBarItem(page == AppPage.SESSION, { page = AppPage.SESSION }, { Icon(Icons.Default.History, null) }, label = { Text("Session") }, colors = atlasNavigationColors())
                    NavigationBarItem(page == AppPage.SYSTEM, { page = AppPage.SYSTEM }, { Icon(Icons.Default.Settings, null) }, label = { Text("System") }, colors = atlasNavigationColors())
                }
            }
        },
    ) { padding ->
        when {
            !permissionsGranted -> PermissionGate(Modifier.padding(padding), onRequestPermissions, onOpenSystemSettings)
            runtime == null -> LoadingRuntime(Modifier.padding(padding))
            page == AppPage.ATLAS -> AtlasHomePage(runtime, snapshot, providers.isNotEmpty(), mediaRepository, sessionActionScope, Modifier.padding(padding), onOpenSession = { page = AppPage.SESSION }, onOpenSystem = { page = AppPage.SYSTEM })
            page == AppPage.SESSION -> SessionPage(runtime, snapshot, providers.isNotEmpty(), mediaRepository, sessionActionScope, Modifier.padding(padding), onConfigureInference = { page = AppPage.SYSTEM })
            page == AppPage.SYSTEM -> SystemPage(runtime, snapshot, settings, providers, managedAccount, sessionArchive, Modifier.padding(padding)) { providerRevision++ }
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
    onOpenSession: () -> Unit,
    onOpenSystem: () -> Unit,
) {
    var name by remember { mutableStateOf("Everyday Atlas") }
    var goal by remember { mutableStateOf("Help me understand and act safely in my current surroundings.") }
    var prompt by remember { mutableStateOf("") }
    var showText by remember { mutableStateOf(false) }
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

            val latestAtlas = snapshot.messages.lastOrNull { it.kind == MessageKind.DIALOGUE && it.role == MessageRole.ASSISTANT && it.content.isNotBlank() }
            if (latestAtlas != null && snapshot.phase != RuntimePhase.SPEAKING) item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Atlas", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(latestAtlas.content, style = MaterialTheme.typography.bodyLarge)
                    TextButtonCompact("Open session") { onOpenSession() }
                }
            }

            if (snapshot.pendingToolCalls.any { it.status == ToolCallStatus.UNKNOWN }) item {
                NoticeCard("Action outcome unknown", "Atlas restarted while a tool was running. It will not repeat the action automatically.")
            }
            if (actionError != null || snapshot.latestError != null) item {
                NoticeCard("Atlas needs attention", actionError ?: snapshot.latestError.orEmpty())
            }

            item {
                VoiceDock(
                    snapshot = snapshot,
                    active = active,
                    canTalk = active && (turnAvailable || snapshot.phase == RuntimePhase.SPEAKING),
                    onTalk = { runAction { runtime.listenAndAsk() } },
                    onObserve = { runAction { runtime.captureNow() } },
                    onToggleText = { showText = !showText },
                )
            }

            if (showText) item {
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("Message Atlas") },
                    enabled = active && turnAvailable,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = { IconButton(enabled = active && turnAvailable && prompt.isNotBlank(), onClick = { val text = prompt; prompt = ""; runAction { runtime.ask(text) } }) { Icon(Icons.AutoMirrored.Filled.Send, "Send") } },
                    keyboardActions = KeyboardActions(onSend = { if (prompt.isNotBlank()) { val text = prompt; prompt = ""; runAction { runtime.ask(text) } } }),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Send),
                )
            }

            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    TextButtonCompact("Session details") { onOpenSession() }
                    if (active) TextButtonCompact("Pause") { runAction { runtime.pauseSession() } }
                    else if (session.status == SessionStatus.PAUSED) TextButtonCompact("Resume") { runAction { runtime.resumeSession() } }
                }
            }
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
private fun VoiceDock(snapshot: RuntimeSnapshot, active: Boolean, canTalk: Boolean, onTalk: () -> Unit, onObserve: () -> Unit, onToggleText: () -> Unit) {
    val liveVoice = snapshot.listeningState != ListeningState.INACTIVE || snapshot.phase == RuntimePhase.SPEAKING
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (snapshot.partialTranscript?.isNotBlank() == true) Text(snapshot.partialTranscript.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (snapshot.streamingResponse?.isNotBlank() == true) Text(snapshot.streamingResponse.orEmpty().takeLast(260), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(7) { index ->
                Box(Modifier.width(2.dp).height(if (liveVoice) (8 + (index % 4) * 4).dp else 3.dp).background(if (liveVoice) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape))
            }
        }
        Text(AtlasUiPresentation.voiceLabel(snapshot, active), style = MaterialTheme.typography.labelLarge, color = if (liveVoice) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onObserve, enabled = active && snapshot.activeTurn == null, modifier = Modifier.size(52.dp).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)) { Icon(Icons.Default.CameraAlt, "Observe now") }
            IconButton(onClick = onTalk, enabled = canTalk, modifier = Modifier.size(72.dp).border(1.dp, if (liveVoice) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape).background(if (liveVoice) MaterialTheme.colorScheme.primary.copy(alpha = .1f) else Color.Transparent, CircleShape)) {
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
    archive: SessionArchive,
    modifier: Modifier,
    onProvidersChanged: () -> Unit,
) {
    var section by remember { mutableStateOf("Inference") }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (section == "Inference") Button({ section = "Inference" }, Modifier.weight(1f)) { Text("Inference") }
            else OutlinedButton({ section = "Inference" }, Modifier.weight(1f)) { Text("Inference") }
            if (section == "Data") Button({ section = "Data" }, Modifier.weight(1f)) { Text("Data & logs") }
            else OutlinedButton({ section = "Data" }, Modifier.weight(1f)) { Text("Data & logs") }
        }
        Box(Modifier.weight(1f)) {
            if (section == "Inference") ProviderPage(settings, providers, managedAccount, Modifier.fillMaxSize(), onProvidersChanged)
            else EventsPage(runtime, snapshot, settings, archive, Modifier.fillMaxSize())
        }
    }
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
                        Icon(if (snapshot.phase == RuntimePhase.SPEAKING) Icons.Default.GraphicEq else Icons.Default.Mic, null, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f)) {
                            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    snapshot.streamingResponse?.takeIf(String::isNotBlank)?.let {
                        Text(it.takeLast(360), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(
                        onClick = { runAction { runtime.listenAndAsk() } },
                        enabled = canTalk,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Mic, null)
                        Text(if (snapshot.phase == RuntimePhase.SPEAKING) " Interrupt and talk" else " Talk")
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

        snapshot.pendingClarification?.let { clarification ->
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text("Atlas needs one detail", fontWeight = FontWeight.Bold)
                        Text(clarification.question, style = MaterialTheme.typography.titleMedium)
                        if (clarification.options.isNotEmpty()) {
                            Text(clarification.options.joinToString(" · "), color = MaterialTheme.colorScheme.onTertiaryContainer)
                        }
                        Text("Answer naturally—short replies like “the black one” are enough.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer)
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
                            if (message.role == MessageRole.ASSISTANT && message.deliveryStatus in setOf(DeliveryStatus.INTERRUPTED, DeliveryStatus.FAILED)) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    if (message.deliveryStatus == DeliveryStatus.INTERRUPTED) "Speech was interrupted; the unheard remainder will not be treated as shared context."
                                    else "Speech playback failed; the text remains available here.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            if (snapshot.phase == RuntimePhase.SPEAKING) item { TextButtonCompact("Stop speaking") { runtime.stopSpeaking() } }
        }

        val lateDiagnostics = snapshot.messages.filter {
            it.kind == MessageKind.INTERNAL && it.role == MessageRole.ASSISTANT && it.content.isNotBlank()
        }.takeLast(5)
        if (lateDiagnostics.isNotEmpty()) {
            item { Text("Late model results", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            items(lateDiagnostics, key = { "late-${it.id}" }) { message ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .65f))) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("DIAGNOSTIC · NOT CONVERSATION CONTEXT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Text(message.content)
                        Text("Completed after the interaction deadline. Preserved for alpha tuning and session export.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        snapshot.activeTurn?.let { turn ->
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Agent turn · ${turn.status.name.lowercase().replace('_', ' ')}", fontWeight = FontWeight.SemiBold)
                            Text("step ${turn.stepCount} · durable turn ${turn.id.take(8)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (turn.status !in setOf(com.grinningfrog.atlas.model.TurnStatus.WAITING_FOR_CONFIRMATION, com.grinningfrog.atlas.model.TurnStatus.WAITING_FOR_USER_CLARIFICATION)) {
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
                enabled = active && (snapshot.activeTurn == null || awaitingClarification || snapshot.activeTurn?.status == com.grinningfrog.atlas.model.TurnStatus.SOFT_TIMED_OUT),
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = { IconButton(enabled = active && (snapshot.activeTurn == null || awaitingClarification || snapshot.activeTurn?.status == com.grinningfrog.atlas.model.TurnStatus.SOFT_TIMED_OUT) && prompt.isNotBlank(), onClick = { val text = prompt; prompt = ""; runAction { runtime.ask(text) } }) { Icon(Icons.AutoMirrored.Filled.Send, "Send") } },
                keyboardActions = KeyboardActions(onSend = { if (prompt.isNotBlank()) { val text = prompt; prompt = ""; runAction { runtime.ask(text) } } }),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Send),
            )
        }
        item {
            OutlinedButton({ runAction { runtime.captureNow() } }, enabled = active && (snapshot.activeTurn == null || snapshot.activeTurn?.status == com.grinningfrog.atlas.model.TurnStatus.SOFT_TIMED_OUT), modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.CameraAlt, null); Text(" Observe now")
            }
        }
        item { HealthCard(snapshot) }
    }
}

@Composable
private fun ProviderPage(settings: SecureSettings, providers: List<ProviderEndpoint>, managedAccount: ManagedAccountClient, modifier: Modifier, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    val managedState by managedAccount.state.collectAsState()
    val tester = remember { ProviderConnectionTester() }
    var name by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var vision by remember { mutableStateOf(true) }
    var tools by remember { mutableStateOf(true) }
    var streaming by remember { mutableStateOf(true) }
    var reasoning by remember { mutableStateOf(false) }
    var draftCheck by remember { mutableStateOf<com.grinningfrog.atlas.provider.ProviderCheck?>(null) }
    var endpointChecks by remember { mutableStateOf<Map<String, com.grinningfrog.atlas.provider.ProviderCheck>>(emptyMap()) }
    val routes = remember(providers) { settings.loadRoutes() }

    fun draftEndpoint(id: String = "connection-test") = ProviderEndpoint(
        id, name.trim().ifBlank { "Endpoint" }, EndpointSecurity.assess(baseUrl).normalizedBaseUrl,
        model.trim(), id, supportsVision = vision, supportsTools = tools, supportsStreaming = streaming, reasoningEnabled = reasoning,
    )

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            AtlasBrandHeader("where Atlas thinks", "Inference")
            Spacer(Modifier.height(20.dp))
            Text("Bring whatever brain you want.", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text("Route by capability, never by a model baked into Atlas Core.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (providers.isNotEmpty()) item {
            InstrumentCard {
                Text("Capability routes", style = MaterialTheme.typography.titleMedium)
                RouteSummaryRow("Fast", routes.fast, providers)
                RouteSummaryRow("Vision", routes.vision, providers)
                RouteSummaryRow("Reasoning", routes.reasoning, providers)
                RouteSummaryRow("Fallback", routes.fallback, providers)
                Text("Routes currently follow endpoint order. No routing behavior has been changed in this visual pass.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Direct · bring your own", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("OpenAI-compatible local, LAN, or cloud endpoint. Requests go directly from this phone; keys are encrypted with Android Keystore.")
                }
            }
        }
        item {
            if (managedState.configured) ManagedAccountCard(managedAccount, settings, onChanged)
            else ManagedInferenceSoonCard()
        }
        if (providers.isNotEmpty()) item { Text("Configured endpoints", style = MaterialTheme.typography.titleMedium) }
        items(providers, key = { it.id }) { endpoint ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(endpoint.name, fontWeight = FontWeight.SemiBold)
                        Text("${endpoint.model} · ${listOfNotNull("text", "vision".takeIf { endpoint.supportsVision }, "tools".takeIf { endpoint.supportsTools }, "streaming".takeIf { endpoint.supportsStreaming }).joinToString(" + ")}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(endpoint.baseUrl, style = MaterialTheme.typography.bodySmall)
                        Text(runCatching { EndpointSecurity.assess(endpoint.baseUrl).notice }.getOrElse { "Blocked: ${it.message}" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val check = endpointChecks[endpoint.id]
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButtonCompact("Test") {
                                scope.launch {
                                    val result = tester.check(endpoint, settings.apiKey(endpoint))
                                    endpointChecks = endpointChecks + (endpoint.id to result)
                                    if (result.ok) settings.markProviderVerified(endpoint.id)
                                }
                            }
                            Text(check?.message ?: settings.providerVerifiedAt(endpoint.id)?.let { "Previously connected" } ?: "Not tested", style = MaterialTheme.typography.bodySmall,
                                color = if (check?.ok == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(endpoint.supportsVision, { enabled ->
                                settings.updateProviderCapabilities(endpoint.id, enabled, endpoint.supportsTools, endpoint.supportsStreaming)
                                val all = settings.loadProviders()
                                val ids = all.map { it.id }
                                settings.saveRoutes(RouteTable(ids, all.filter { it.supportsVision }.map { it.id }, ids, ids))
                                onChanged()
                            })
                            Text("Vision")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(endpoint.supportsTools, { enabled ->
                                settings.updateProviderCapabilities(endpoint.id, endpoint.supportsVision, enabled, endpoint.supportsStreaming)
                                onChanged()
                            })
                            Text("Tools")
                            Checkbox(endpoint.supportsStreaming, { enabled ->
                                settings.updateProviderCapabilities(endpoint.id, endpoint.supportsVision, endpoint.supportsTools, enabled)
                                onChanged()
                            })
                            Text("Stream")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(endpoint.reasoningEnabled, { enabled ->
                                settings.updateProviderReasoning(endpoint.id, enabled)
                                onChanged()
                            })
                            Text("Reasoning (slower; off is recommended on-device)")
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
                    OutlinedTextField(name, { name = it; draftCheck = null }, label = { Text("Name (for example, Home Ollama)") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(baseUrl, { baseUrl = it; draftCheck = null }, label = { Text("Base URL") }, placeholder = { Text("https://provider.example/v1") }, modifier = Modifier.fillMaxWidth())
                    runCatching { EndpointSecurity.assess(baseUrl).notice }.getOrNull()?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    OutlinedTextField(model, { model = it; draftCheck = null }, label = { Text("Model") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(apiKey, { apiKey = it; draftCheck = null }, label = { Text("API key (optional)") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(vision, { vision = it }); Text("Endpoint accepts image input")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(tools, { tools = it }); Text("Endpoint supports OpenAI-compatible tool calls")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(streaming, { streaming = it }); Text("Endpoint supports SSE streaming")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(reasoning, { reasoning = it }); Text("Enable model reasoning (slower; off by default)")
                    }
                    OutlinedButton(
                        enabled = baseUrl.isNotBlank() && model.isNotBlank(),
                        onClick = { scope.launch {
                            draftCheck = runCatching { tester.check(draftEndpoint(), apiKey.takeIf(String::isNotBlank)) }
                                .getOrElse { com.grinningfrog.atlas.provider.ProviderCheck(false, it.message ?: "Invalid endpoint") }
                        } },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Test connection") }
                    draftCheck?.let { Text(it.message, color = if (it.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
                    Button(
                        enabled = name.isNotBlank() && model.isNotBlank() && draftCheck?.ok == true,
                        onClick = {
                            val id = UUID.randomUUID().toString()
                            settings.saveProvider(draftEndpoint(id), apiKey)
                            settings.markProviderVerified(id)
                            val all = settings.loadProviders()
                            val allIds = all.map { it.id }
                            settings.saveRoutes(RouteTable(allIds, all.filter { it.supportsVision }.map { it.id }, allIds, allIds))
                            name = ""; baseUrl = ""; model = ""; apiKey = ""; reasoning = false; draftCheck = null; onChanged()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Save endpoint") }
                }
            }
        }
    }
}

@Composable
private fun RouteSummaryRow(label: String, ids: List<String>, providers: List<ProviderEndpoint>) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(88.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(ids.mapNotNull { id -> providers.firstOrNull { it.id == id }?.name }.joinToString(" → ").ifBlank { "No route" }, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ManagedInferenceSoonCard() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .55f))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("ATLAS CLOUD // ON THE WAY", color = MaterialTheme.colorScheme.primary, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.sp)
            Text("Managed cloud inference", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Use Atlas without managing provider keys, with capability routing and a clear usage allowance. It is not enabled in this closed-alpha build yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
private fun EventsPage(runtime: AtlasMobileRuntime, snapshot: RuntimeSnapshot, settings: SecureSettings, archive: SessionArchive, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    var retentionDays by remember { mutableIntStateOf(settings.mediaRetentionDays) }
    var confirmDelete by remember { mutableStateOf(false) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete this session permanently?") },
        text = { Text("This removes its transcript, memory, observations, events, tool records, and Atlas-owned images from this phone. This cannot be undone.") },
        confirmButton = { TextButton(onClick = {
            confirmDelete = false
            scope.launch { runCatching { runtime.deleteCurrentSession() }
                .onSuccess { actionMessage = "Session deleted from this phone" }
                .onFailure { actionMessage = it.message ?: "Deletion failed" } }
        }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            AtlasBrandHeader("LOCAL RECORD", "DEVICE OWNED")
            Spacer(Modifier.height(20.dp))
            Text("Data & activity", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text("Your session stays on this phone except for content sent to your chosen inference and speech services.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("Session controls", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Exports contain a readable transcript and the complete machine-readable Atlas state bundle. Provider credentials are never included.", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = snapshot.session != null, onClick = {
                            snapshot.session?.let { runCatching { archive.share(it.id) }.onFailure { error -> actionMessage = error.message } }
                        }) { Icon(Icons.Default.IosShare, null); Text(" Export") }
                        OutlinedButton(enabled = snapshot.session != null, onClick = { confirmDelete = true }) {
                            Icon(Icons.Default.Delete, null); Text(" Delete")
                        }
                    }
                    actionMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Image retention", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Processed images from completed sessions are removed after $retentionDays days. Transcripts and audit records remain until you delete the session.", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1, 7, 30).forEach { days ->
                            if (retentionDays == days) Button(onClick = {}) { Text("$days d") }
                            else OutlinedButton(onClick = {
                                retentionDays = days; settings.mediaRetentionDays = days
                                scope.launch { archive.enforceMediaRetention(days) }
                            }) { Text("$days d") }
                        }
                    }
                }
            }
        }
        item {
            Text("Local event stream", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Monotonic sequence · newest first", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
private fun TextButtonCompact(text: String, onClick: () -> Unit) {
    androidx.compose.material3.TextButton(onClick, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) { Text(text) }
}

private fun duration(milliseconds: Long): String = when {
    milliseconds < 1_000 -> "${milliseconds}ms"
    milliseconds < 60_000 -> "${milliseconds / 1_000}s"
    else -> "${milliseconds / 60_000}m"
}

private val AtlasColors = darkColorScheme(
    primary = Color(0xFF5CFF78),
    onPrimary = Color(0xFF001707),
    primaryContainer = Color(0xFF0A1A0F),
    onPrimaryContainer = Color(0xFFC3FFD0),
    secondary = Color(0xFFD7DED9),
    onSecondary = Color(0xFF111512),
    secondaryContainer = Color(0xFF111713),
    onSecondaryContainer = Color(0xFFDDE7DF),
    tertiary = Color(0xFFE2B96A),
    tertiaryContainer = Color(0xFF241B0D),
    onTertiaryContainer = Color(0xFFF4D79B),
    background = Color(0xFF010302),
    onBackground = Color(0xFFEEF5EF),
    surface = Color(0xFF080C09),
    onSurface = Color(0xFFEEF5EF),
    surfaceVariant = Color(0xFF101612),
    onSurfaceVariant = Color(0xFF98A29B),
    outline = Color(0xFF253029),
    outlineVariant = Color(0xFF18201B),
    error = Color(0xFFFF7B78),
    errorContainer = Color(0xFF25100F),
    onErrorContainer = Color(0xFFFFDAD7),
)

private val AtlasAmber = Color(0xFFE2B96A)

private val AtlasShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

private val BaseTypography = Typography()
private val AtlasTypography = Typography(
    headlineLarge = BaseTypography.headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-.7).sp),
    titleLarge = BaseTypography.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-.2).sp),
    titleMedium = BaseTypography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = BaseTypography.labelLarge.copy(fontFamily = FontFamily.Monospace, letterSpacing = .7.sp),
    labelMedium = BaseTypography.labelMedium.copy(fontFamily = FontFamily.Monospace, letterSpacing = .6.sp),
    labelSmall = BaseTypography.labelSmall.copy(fontFamily = FontFamily.Monospace, letterSpacing = .5.sp),
)

@Composable
private fun AtlasTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AtlasColors, typography = AtlasTypography, shapes = AtlasShapes, content = content)
}
