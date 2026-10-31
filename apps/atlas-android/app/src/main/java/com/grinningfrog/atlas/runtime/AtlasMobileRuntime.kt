package com.grinningfrog.atlas.runtime

import com.grinningfrog.atlas.data.AtlasDatabase
import com.grinningfrog.atlas.media.MediaRepository
import com.grinningfrog.atlas.device.CameraController
import com.grinningfrog.atlas.device.DeviceHealthMonitor
import com.grinningfrog.atlas.device.MotionMonitor
import com.grinningfrog.atlas.device.SpeechController
import com.grinningfrog.atlas.device.ListeningCallbacks
import com.grinningfrog.atlas.device.SpeechCallbacks
import com.grinningfrog.atlas.model.AtlasSession
import com.grinningfrog.atlas.model.AtlasMessage
import com.grinningfrog.atlas.model.ToolRisk
import com.grinningfrog.atlas.model.AtlasToolCall
import com.grinningfrog.atlas.model.ContextMode
import com.grinningfrog.atlas.model.ClarificationAmbiguity
import com.grinningfrog.atlas.model.ClarificationStatus
import com.grinningfrog.atlas.model.DeliveryStatus
import com.grinningfrog.atlas.model.InferenceRequest
import com.grinningfrog.atlas.model.InferenceResponse
import com.grinningfrog.atlas.model.InferenceStreamEvent
import com.grinningfrog.atlas.model.InferenceMessage
import com.grinningfrog.atlas.model.InferenceDomain
import com.grinningfrog.atlas.model.InferencePurpose
import com.grinningfrog.atlas.model.InferenceProvenance
import com.grinningfrog.atlas.model.MediaPurpose
import com.grinningfrog.atlas.model.PendingClarification
import com.grinningfrog.atlas.model.ListeningState
import com.grinningfrog.atlas.model.RouteCapability
import com.grinningfrog.atlas.model.RuntimePhase
import com.grinningfrog.atlas.model.RuntimeSnapshot
import com.grinningfrog.atlas.model.SessionStatus
import com.grinningfrog.atlas.model.MessageKind
import com.grinningfrog.atlas.model.MessageRole
import com.grinningfrog.atlas.model.SessionSummary
import com.grinningfrog.atlas.model.SpeechSegment
import com.grinningfrog.atlas.model.SpeechSegmentStatus
import com.grinningfrog.atlas.model.ToolCallStatus
import com.grinningfrog.atlas.model.ToolCallProposal
import com.grinningfrog.atlas.model.TurnStatus
import com.grinningfrog.atlas.model.VisualObservation
import com.grinningfrog.atlas.provider.CapabilityRouter
import com.grinningfrog.atlas.intent.AndroidIdleRuntime
import com.grinningfrog.atlas.intent.IdleRuntimeStatus
import com.grinningfrog.atlas.intent.IntentAutonomyMode
import com.grinningfrog.atlas.intent.IntentState
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

class AtlasMobileRuntime(
    private val database: AtlasDatabase,
    private val mediaRepository: MediaRepository,
    private val camera: CameraController,
    private val speech: SpeechController,
    private val motion: MotionMonitor,
    private val health: DeviceHealthMonitor,
    private val router: CapabilityRouter,
    private val scope: CoroutineScope,
    private val idleRuntime: AndroidIdleRuntime? = null,
    private val additionalTools: List<AtlasToolAdapter> = emptyList(),
    private val runtimeBundles: com.grinningfrog.atlas.runtime.core.RuntimeBundleStore? = null,
    private val runtimeCore: com.grinningfrog.atlas.runtime.core.RuntimeCoreEngine? = null,
    private val onLiveContextChanged: (Boolean) -> Unit = {},
) {
    private val operations = Mutex()
    private val taskRunner = Mutex()
    private val generation = AtomicLong(0)
    private val mutableState = MutableStateFlow(RuntimeSnapshot())
    val state: StateFlow<RuntimeSnapshot> = mutableState.asStateFlow()
    val idleStatus: StateFlow<IdleRuntimeStatus>? get() = idleRuntime?.status
    private var heartbeatJob: Job? = null
    private var compactionJob: Job? = null
    private var activeTurnJob: Job? = null
    private var activeTurnId: String? = null
    private var lateTurnJob: Job? = null
    private var lateTurnId: String? = null
    private var devicesStarted = false
    private val contextAssembler = ContextAssembler()
    private val capabilityDiscovery = CapabilityDiscovery(database)
    private val executionTasks = ExecutionTaskStore(database)
    private var activeExecutionLease: ExecutionLease? = null
    private var activeExecutionTaskId: String? = null
    private val toolHarness by lazy {
        ToolHarness(
            database = database,
            capture = { reason, purpose -> captureLocked(requireActiveSession(), reason, purpose) },
            deviceHealth = health::snapshot,
            additionalTools = additionalTools,
            executionLease = { activeExecutionLease },
        )
    }

    suspend fun initialize() {
        val session = database.loadLatestSession()
        session?.let {
            database.recoverInterruptedRuntime(it.id); executionTasks.recover(it.id)
            if (it.status == SessionStatus.ACTIVE) executionTasks.list(it.id).filter { task -> task.state == ExecutionState.PAUSED && task.waitReason == "runner interrupted" }.forEach { task ->
                executionTasks.control(task.id, ExecutionState.READY, "runtime restarted")?.takeIf { resumed -> resumed.state == ExecutionState.READY }?.let { resumed -> executionTasks.nextTurn(resumed.id) }
            }
        }
        session?.let { database.expirePendingClarification(it.id) }
        val observation = session?.let { database.loadLatestObservation(it.id) }
        val latestResponse = session?.let { database.loadMessages(it.id, limit = 80).lastOrNull { message -> message.role == MessageRole.ASSISTANT && message.content.isNotBlank() }?.content }
        if (session?.status == SessionStatus.ACTIVE) startDevices()
        publish(session = session, observation = observation, response = latestResponse, phase = if (session?.status == SessionStatus.ACTIVE) RuntimePhase.READY else RuntimePhase.STOPPED)
        val live = session?.let { it.status == SessionStatus.ACTIVE && it.contextMode == ContextMode.LIVE } == true
        onLiveContextChanged(live)
        if (live) startHeartbeat()
        idleRuntime?.start()
        if (session?.status == SessionStatus.ACTIVE) resumeReadyExecution(session.id)
    }

    suspend fun createAndStartSession(name: String, goal: String): AtlasSession = operations.withLock {
        publish(phase = RuntimePhase.STARTING)
        startDevices()
        val session = database.createSession(name.ifBlank { "Atlas session" }, goal)
        database.updateSessionStatus(session.id, SessionStatus.ACTIVE)
        val active = session.copy(status = SessionStatus.ACTIVE, updatedAtMs = System.currentTimeMillis())
        publish(session = active, observation = null, phase = RuntimePhase.READY)
        onLiveContextChanged(false)
        active
    }

    suspend fun activateComposableWorkspace(workspaceId: String?) = operations.withLock {
        val session = requireSession()
        val sequence = withContext(kotlinx.coroutines.Dispatchers.IO) { database.activateComposableWorkspace(session.id, workspaceId) }
        publish(session = session.copy(activeComposableWorkspaceId = workspaceId, workspaceNavigationSequence = sequence))
    }

    suspend fun openImportedSession(sessionId: String) {
        cancelActiveTurn("opening imported session")
        compactionJob?.cancel(); compactionJob = null
        operations.withLock {
            heartbeatJob?.cancel(); heartbeatJob = null
            onLiveContextChanged(false); speech.stopSpeaking(); stopDevices()
            mutableState.value.session?.takeIf { it.status != SessionStatus.DONE }?.let {
                database.updateSessionStatus(it.id, SessionStatus.PAUSED)
            }
            val imported = requireNotNull(database.loadSession(sessionId)) { "Imported session is unavailable" }
            database.updateSessionStatus(sessionId, SessionStatus.PAUSED)
            publish(session = imported.copy(status = SessionStatus.PAUSED), observation = null, response = null,
                error = null, phase = RuntimePhase.STOPPED, nextHeartbeatAt = null,
                listeningState = ListeningState.INACTIVE, partialTranscript = null, streamingResponse = null)
        }
    }

    suspend fun resumeSession() = operations.withLock {
        val session = requireSession()
        if (session.status == SessionStatus.DONE) throw IllegalStateException("Completed sessions cannot be resumed")
        publish(phase = RuntimePhase.STARTING)
        startDevices()
        database.updateSessionStatus(session.id, SessionStatus.ACTIVE)
        database.appendEvent(session.id, "session.resumed", JSONObject())
        publish(session = session.copy(status = SessionStatus.ACTIVE), phase = RuntimePhase.READY)
        if (session.contextMode == ContextMode.LIVE) startHeartbeat() else onLiveContextChanged(false)
        executionTasks.list(session.id).filter { it.state == ExecutionState.PAUSED && it.waitReason == "session paused" }.forEach { executionTasks.control(it.id, ExecutionState.READY, "session resumed")?.takeIf { resumed -> resumed.state == ExecutionState.READY }?.let { resumed -> executionTasks.nextTurn(resumed.id) } }
        resumeReadyExecution(session.id)
    }

    suspend fun pauseSession() {
        cancelActiveTurn("session paused")
        compactionJob?.cancel(); compactionJob = null
        operations.withLock {
        val session = requireSession()
        heartbeatJob?.cancel(); heartbeatJob = null
        onLiveContextChanged(false)
        speech.stopSpeaking()
        stopDevices()
        database.updateSessionStatus(session.id, SessionStatus.PAUSED)
        publish(session = session.copy(status = SessionStatus.PAUSED), phase = RuntimePhase.STOPPED)
        }
    }

    suspend fun endSession() {
        cancelActiveTurn("session ended")
        compactionJob?.cancel(); compactionJob = null
        operations.withLock {
        val session = requireSession()
        heartbeatJob?.cancel(); heartbeatJob = null
        onLiveContextChanged(false)
        speech.stopSpeaking()
        stopDevices()
        database.cancelOpenSessionWork(session.id, "session ended")
        database.updateSessionStatus(session.id, SessionStatus.DONE)
        publish(session = session.copy(status = SessionStatus.DONE), phase = RuntimePhase.STOPPED)
        }
    }

    suspend fun deleteCurrentSession() {
        cancelActiveTurn("session deleted")
        compactionJob?.cancel(); compactionJob = null
        operations.withLock {
            val session = requireSession()
            heartbeatJob?.cancel(); heartbeatJob = null
            onLiveContextChanged(false)
            speech.stopSpeaking()
            stopDevices()
            val mediaKeys = database.deleteSession(session.id)
            mediaRepository.deleteStorageKeys(mediaKeys)
            publish(session = null, observation = null, response = null, error = null, phase = RuntimePhase.STOPPED, nextHeartbeatAt = null)
        }
    }

    suspend fun captureNow(reason: String = "user-request"): VisualObservation = operations.withLock {
        idleRuntime?.onUserInteraction()
        val session = requireActiveSession()
        captureLocked(session, reason)
    }

    suspend fun setLiveContextEnabled(enabled: Boolean) {
        if (!enabled) {
            heartbeatJob?.cancel(); heartbeatJob = null
            onLiveContextChanged(false)
        }
        operations.withLock {
            val session = requireSession()
            if (session.status == SessionStatus.DONE) throw IllegalStateException("Completed sessions cannot change context mode")
            val mode = if (enabled) ContextMode.LIVE else ContextMode.MANUAL
            if (session.contextMode == mode) return@withLock
            database.updateContextMode(session.id, mode)
            val updated = session.copy(contextMode = mode, updatedAtMs = System.currentTimeMillis())
            publish(session = updated, nextHeartbeatAt = null, phase = if (session.status == SessionStatus.ACTIVE) RuntimePhase.READY else RuntimePhase.STOPPED)
            if (enabled && session.status == SessionStatus.ACTIVE) startHeartbeat() else {
                heartbeatJob?.cancel(); heartbeatJob = null
                onLiveContextChanged(false)
            }
        }
    }

    suspend fun listenAndAsk() {
        idleRuntime?.onUserInteraction()
        val session = requireActiveSession()
        val priorTurn = activeTurnJob
        if (speech.isSpeaking || priorTurn?.isActive == true) {
            database.appendEvent(session.id, "audio.barge_in_requested", JSONObject().put("activeTurnId", activeTurnId))
            cancelActiveTurn("user started speaking")
            priorTurn?.join()
        }
        publish(phase = RuntimePhase.LISTENING, listeningState = ListeningState.PREPARING, partialTranscript = null, streamingResponse = null)
        database.appendEvent(session.id, "audio.transcription.requested")
        try {
            val result = speech.listenOnce(callbacks = ListeningCallbacks(
                onReady = {
                    database.appendEvent(session.id, "audio.listening_ready", JSONObject().put("cue", "haptic+chirp"))
                    publishTransient(phase = RuntimePhase.LISTENING, listeningState = ListeningState.READY)
                },
                onSpeechStarted = { publishTransient(phase = RuntimePhase.LISTENING, listeningState = ListeningState.HEARING) },
                onSpeechEnded = { publishTransient(phase = RuntimePhase.TRANSCRIBING, listeningState = ListeningState.PROCESSING) },
                onPartial = { partial -> publishTransient(phase = RuntimePhase.LISTENING, listeningState = ListeningState.HEARING, partialTranscript = partial) },
            ))
            database.appendEvent(session.id, "audio.transcription.completed", JSONObject().put("text", result.text).put("confidence", result.confidence))
            publish(phase = RuntimePhase.TRANSCRIBING, listeningState = ListeningState.PROCESSING, partialTranscript = result.text)
            ask(result.text, voice = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            database.appendEvent(session.id, "audio.transcription.failed", JSONObject().put("error", error.safeMessage()))
            fail(error)
        } finally {
            publishTransient(listeningState = ListeningState.INACTIVE, partialTranscript = null)
        }
    }

    suspend fun ask(text: String, voice: Boolean = false) {
        require(text.isNotBlank()) { "Ask Atlas something first" }
        if (activeTurnJob?.isActive == true || lateTurnJob?.isActive == true) cancelActiveTurn("superseded by a new user turn")
        // Activity cancellation only stops waiting; the service owns execution until explicit cancellation.
        scope.async { taskRunner.withLock { askOwned(text, voice) } }.await()
    }

    private suspend fun askOwned(text: String, voice: Boolean) {
        idleRuntime?.onUserInteraction()
        require(text.isNotBlank()) { "Ask Atlas something first" }
        idleRuntime?.observeUserText(text)
        cancelLateTurn("superseded by a new user turn")
        compactionJob?.cancel(); compactionJob = null
        val job = currentCoroutineContext()[Job]
        activeTurnJob = job
        try {
            val taskId = operations.withLock { startUserTurnLocked(text.trim(), voice) }
            taskId?.let { runExecution(it, voice) }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                if (activeTurnJob === job) activeTurnId?.let { turnId -> runCatching { database.updateTurn(turnId, TurnStatus.CANCELLED, error = "Cancelled") } }
                publish(phase = if (mutableState.value.session?.status == SessionStatus.ACTIVE) RuntimePhase.READY else RuntimePhase.STOPPED)
            }
            throw cancelled
        } catch (error: Exception) {
            if (activeTurnJob === job) activeTurnId?.let { turnId -> runCatching { database.updateTurn(turnId, TurnStatus.FAILED, error = error.safeMessage()) } }
            mutableState.value.session?.let { session ->
                database.appendEvent(session.id, "runtime.turn_failed", JSONObject().put("turnId", activeTurnId).put("error", error.safeMessage()))
            }
            fail(error)
        } finally {
            if (activeTurnJob === job) { activeTurnJob = null; activeTurnId = null; activeExecutionTaskId = null; activeExecutionLease = null; mutableState.value.session?.takeIf { it.status == SessionStatus.ACTIVE }?.let { resumeReadyExecution(it.id) } }
        }
    }

    fun cancelActiveTurn(reason: String = "user cancelled") {
        idleRuntime?.onUserInteraction()
        generation.incrementAndGet()
        activeExecutionTaskId?.let { taskId -> executionTasks.pending(taskId).filter { it.status == ToolCallStatus.RUNNING }.forEach { database.updateToolCall(it.id, ToolCallStatus.UNKNOWN, error = "Execution interrupted: $reason") }; executionTasks.control(taskId, if (reason == "session paused" || reason == "superseded by a new user turn" || reason == "user started speaking") ExecutionState.PAUSED else ExecutionState.CANCELLED, reason) }
        speech.stopSpeaking()
        (activeTurnId ?: mutableState.value.activeTurn?.id)?.let { turnId -> runCatching {
            database.markRunningToolsUnknown(turnId, "Execution interrupted: $reason")
            database.updateTurn(turnId, TurnStatus.CANCELLED, error = reason)
        } }
        activeTurnJob?.cancel(CancellationException(reason))
        cancelLateTurn(reason)
        publish()
    }

    private fun cancelLateTurn(reason: String) {
        val job = lateTurnJob ?: return
        if (!job.isActive) return
        val turnId = lateTurnId
        job.cancel(CancellationException(reason))
        turnId?.let { id ->
            runCatching { database.updateTurn(id, TurnStatus.HARD_CANCELLED, error = reason) }
            mutableState.value.session?.let { session ->
                database.appendEvent(session.id, "provider.late_cancelled", JSONObject().put("turnId", id).put("reason", reason))
            }
        }
        lateTurnJob = null
        lateTurnId = null
    }

    suspend fun resolveToolCall(toolCallId: String, approved: Boolean) {
        idleRuntime?.onUserInteraction()
        val taskId = operations.withLock {
            val session = requireActiveSession()
            val call = database.loadToolCall(toolCallId) ?: error("Tool proposal no longer exists")
            require(call.sessionId == session.id && call.status == ToolCallStatus.WAITING_FOR_CONFIRMATION)
            database.updateToolCall(call.id, if (approved) ToolCallStatus.APPROVED else ToolCallStatus.REJECTED, error = if (approved) null else "User declined")
            if (!approved) recordToolResult(call, JSONObject().put("ok", false).put("error", "User declined this tool call").toString())
            val task = executionTasks.forTurn(call.turnId)
            if (task == null) {
                // Imported/legacy proposals do not acquire executable task authority.
                if (approved) database.updateToolCall(call.id, ToolCallStatus.REJECTED, error = "Legacy proposal requires a new user request")
                null
            } else if (executionTasks.pending(task.id).any { it.status == ToolCallStatus.WAITING_FOR_CONFIRMATION }) null
            else executionTasks.control(task.id, ExecutionState.READY, "User resolved tool authorization")?.takeIf { it.state == ExecutionState.READY }?.let { executionTasks.nextTurn(it.id); it.id }
        }
        publish()
        taskId?.let { resumeExecution(it) }
    }

    /** Explicit resume retains cumulative usage and never reconciles an unknown effect by retrying it. */
    suspend fun resumeExecution(taskId: String) = withContext(kotlinx.coroutines.Dispatchers.IO) {
        operations.withLock {
        val task = executionTasks.get(taskId) ?: return@withContext
        require(task.sessionId == requireActiveSession().id)
        if(executionTasks.reconcileDelivered(taskId)?.state==ExecutionState.COMPLETED) { publish();return@withContext }
        require(task.state in setOf(ExecutionState.READY, ExecutionState.PAUSED, ExecutionState.WAITING))
        require(task.waitReason !in setOf("clarification", "authorization", "unknown_operation_outcome")) { "Resolve the recorded blocker first" }
        if (task.waitReason == "provider") require(lateTurnJob?.isActive != true) { "Provider request is still unresolved" }
        if (!executionTasks.available(taskId)) { publish(error = "Task allowance exhausted; remaining usage is preserved"); return@withContext }
        if (task.state != ExecutionState.READY) {
            val resumed=executionTasks.control(taskId, ExecutionState.READY, "User resumed")
            require(resumed?.state == ExecutionState.READY) { "Operation outcome requires reconciliation before resuming" }
            executionTasks.nextTurn(taskId)
        }
        resumeReadyExecution(task.sessionId)
        }
    }

    fun cancelExecution(taskId: String) {
        require(executionTasks.get(taskId)?.sessionId == requireSession().id)
        if (activeExecutionTaskId == taskId) cancelActiveTurn() else executionTasks.control(taskId, ExecutionState.CANCELLED, "user cancelled")
        publish()
    }

    private fun resumeReadyExecution(sessionId: String) {
        scope.launch { taskRunner.withLock {
            if (mutableState.value.session?.id != sessionId || mutableState.value.session?.status != SessionStatus.ACTIVE || activeTurnJob?.isActive == true) return@withLock
            val task = executionTasks.list(sessionId).firstOrNull { it.state == ExecutionState.READY } ?: return@withLock
            val job = currentCoroutineContext()[Job]
            activeTurnJob = job
            try { runExecution(task.id, false) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { fail(error) }
            finally { if (activeTurnJob === job) { activeTurnJob = null; activeTurnId = null; activeExecutionTaskId = null; activeExecutionLease = null; mutableState.value.session?.takeIf { it.status == SessionStatus.ACTIVE }?.let { resumeReadyExecution(it.id) } }; publish() }
        } }
    }

    private suspend fun runExecution(taskId: String, voice: Boolean) {
        activeExecutionTaskId = taskId
        DurableTaskExecutor(executionTasks).run(taskId) { lease, turnId ->
            operations.withLock {
                executionTasks.check(lease)
                activeExecutionLease = lease
                activeTurnId = turnId
                val session = requireActiveSession()
                require(session.id == executionTasks.get(taskId)?.sessionId)
                try {
                    runTurnLoopLocked(session, turnId, usableLatestObservation(session.id), voice)
                    when (database.loadTurn(turnId)?.status) {
                        TurnStatus.YIELDED -> SliceOutcome.YIELDED
                        TurnStatus.COMPLETED -> SliceOutcome.RESPONDED
                        TurnStatus.WAITING_FOR_USER_CLARIFICATION -> SliceOutcome.WAITING_USER
                        TurnStatus.WAITING_FOR_CONFIRMATION -> SliceOutcome.WAITING_PERMISSION
                        TurnStatus.SOFT_TIMED_OUT, TurnStatus.COMPLETED_LATE -> SliceOutcome.WAITING_PROVIDER
                        else -> SliceOutcome.BLOCKED
                    }
                } catch (error: Exception) {
                    if (error !is CancellationException) database.updateTurn(turnId, TurnStatus.FAILED, error = error.safeMessage())
                    throw error
                } finally { activeExecutionLease = null }
            }
        }
        val waiting = executionTasks.get(taskId)
        if (waiting?.state == ExecutionState.WAITING && waiting.waitReason == "provider") {
            val lateJob = lateTurnJob
            scope.launch {
                lateJob?.join()
                val current = executionTasks.get(taskId)
                if (current?.state == ExecutionState.WAITING && current.waitReason == "provider" && database.loadTurn(executionTasks.currentTurn(taskId))?.status == TurnStatus.COMPLETED_LATE) {
                    executionTasks.control(taskId, ExecutionState.READY, "Provider diagnostic result recorded")
                    executionTasks.nextTurn(taskId)
                    resumeReadyExecution(current.sessionId)
                }
            }
        }
        publish(phase = RuntimePhase.READY)
    }

    fun forgetMemory(memoryId: String) {
        database.forgetMemory(memoryId)
        publish()
    }

    private suspend fun startUserTurnLocked(text: String, voice: Boolean): String? {
        val session = requireActiveSession()
        database.expirePendingClarification(session.id)
        val pending = database.loadPendingClarification(session.id)
        val turn = database.createTurn(session.id, if (voice) "voice" else "text")
        activeTurnId = turn.id
        publish(error = null)
        database.insertMessage(AtlasMessage(sessionId = session.id, turnId = turn.id, role = MessageRole.USER, content = text, createdAtMs = System.currentTimeMillis()))
        pending?.let { database.appendEvent(session.id, "clarification.reply_candidate", JSONObject().put("turnId", turn.id).put("clarificationId", it.id)) }

        var observation = usableLatestObservation(session.id)
        val freshness = FreshnessPolicy.assess(text, observation, System.currentTimeMillis())
        database.appendEvent(session.id, "context.assessed", JSONObject().put("turnId", turn.id).put("decision", freshness.decision.name).put("reason", freshness.reason)
            .put("ageMs", freshness.ageMs).put("staleAfterMs", freshness.staleAfterMs))
        if (freshness.decision == FreshnessDecision.REFRESH_REQUIRED) {
            try {
                val purpose = if (freshness.useCase == VisualUseCase.HIGH_RISK || freshness.useCase == VisualUseCase.DETAIL) MediaPurpose.DETAIL_VISION else MediaPurpose.STANDARD_VISION
                observation = captureLocked(session, "user-preflight", purpose)
            } catch (error: Exception) {
                database.appendEvent(session.id, "visual.refresh_failed", JSONObject().put("turnId", turn.id).put("error", error.safeMessage()))
                if (freshness.useCase == VisualUseCase.HIGH_RISK || freshness.useCase == VisualUseCase.NAVIGATION || observation == null) {
                    val refusal = "I couldn't establish fresh visual context, so I can't safely answer that as a current physical-world question."
                    database.insertMessage(AtlasMessage(sessionId = session.id, turnId = turn.id, role = MessageRole.ASSISTANT, content = refusal, createdAtMs = System.currentTimeMillis()))
                    database.updateTurn(turn.id, TurnStatus.FAILED, error = error.safeMessage())
                    publish(response = refusal, error = error.safeMessage(), phase = RuntimePhase.DEGRADED)
                    if (voice || session.permissions.speakResponses) speakLocked(session, refusal)
                    return null
                }
            }
        }
        val priorTask = pending?.let { executionTasks.forTurn(it.sourceTurnId) }
        return if (priorTask?.state == ExecutionState.WAITING && priorTask.waitReason == "clarification") executionTasks.attachReply(priorTask.id, turn.id).id
        else executionTasks.followUpTask(session.id)?.let { executionTasks.attachReply(it.id,turn.id).id }
            ?: executionTasks.create(session, text, turn.id, ExecutionBudget(), toolHarness.atomicLocalTools()).also {
                executionTasks.pinRuntime(it.id, runtimeBundles?.selected() ?: "native")
            }.id
    }

    private suspend fun runTurnLoopLocked(session: AtlasSession, turnId: String, startingObservation: VisualObservation?, voice: Boolean) {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        var observation = startingObservation
        var uiObservation: VisualObservation? = null
        var step = database.loadTurn(turnId)?.stepCount ?: 0
        val lease = activeExecutionLease ?: error("Missing execution lease")
        val runtimeId = executionTasks.pinRuntime(lease.taskId, "native")
        val runtimeBundle = if (runtimeId == "native") null else requireNotNull(runtimeBundles).resolve(runtimeId)
        var sliceTools = 0
        val sliceToolsByName = mutableMapOf<String, Int>()
        suspend fun drainPending(): Boolean {
            val pending = executionTasks.pending(lease.taskId)
            if (pending.any { it.status == ToolCallStatus.WAITING_FOR_CONFIRMATION }) { database.updateTurn(turnId, TurnStatus.WAITING_FOR_CONFIRMATION); return false }
            for (call in pending.filter { it.status in setOf(ToolCallStatus.PROPOSED, ToolCallStatus.APPROVED) }) {
                if (sliceTools >= AgentLoopPolicy.MAX_TOOL_CALLS || android.os.SystemClock.elapsedRealtime() - startedAt >= AgentLoopPolicy.MAX_WALL_TIME_MS) { database.updateTurn(turnId, TurnStatus.YIELDED); return false }
                executionTasks.check(lease)
                val policy = toolHarness.evaluate(requireActiveSession(), ToolCallProposal(call.id, call.name, call.argumentsJson))
                if (policy.requiresConfirmation && call.status != ToolCallStatus.APPROVED) { database.updateToolCall(call.id, ToolCallStatus.WAITING_FOR_CONFIRMATION); database.updateTurn(turnId, TurnStatus.WAITING_FOR_CONFIRMATION); return false }
                if (!policy.allowed) { database.updateToolCall(call.id, ToolCallStatus.REJECTED, error = policy.reason); recordToolResult(call, JSONObject().put("ok", false).put("error", policy.reason).toString()); continue }
                val toolLimit = toolHarness.definition(call.name)?.maxCallsPerTurn ?: 0
                require(toolLimit > 0) { "Installed tool has no execution allowance: ${call.name}" }
                if (sliceToolsByName.getOrDefault(call.name, 0) >= toolLimit) {
                    database.updateTurn(turnId, TurnStatus.YIELDED, error = "Per-tool slice allowance reached: ${call.name}")
                    database.appendEvent(session.id, "execution.slice_yielded", JSONObject().put("turnId", turnId).put("execution_task_id", lease.taskId).put("reason", "per_tool_allowance").put("tool", call.name).put("limit", toolLimit))
                    return false
                }
                if (!executionTasks.reserveTool(lease, turnId, call.id)) { executionTasks.control(lease.taskId, ExecutionState.WAITING, "task_allowance"); database.updateTurn(turnId, TurnStatus.YIELDED); return false }
                sliceTools++
                sliceToolsByName[call.name] = sliceToolsByName.getOrDefault(call.name, 0) + 1
                val captured = executeToolLocked(call)
                if (database.loadToolCall(call.id)?.status == ToolCallStatus.UNKNOWN) { database.updateTurn(turnId, TurnStatus.FAILED, error = "Operation outcome requires reconciliation"); return false }
                if (captured?.media?.purpose == MediaPurpose.WORKSPACE_UI) uiObservation = captured
                else if (call.risk != com.grinningfrog.atlas.model.ToolRisk.READ_ONLY) uiObservation = null
            }
            return true
        }
        val recorded = database.loadMessages(session.id, limit = AgentLoopPolicy.MAX_STORED_MESSAGES_FOR_CONTEXT).mapNotNull { it.toolCallId }.toSet()
        executionTasks.calls(lease.taskId).filter { it.status == ToolCallStatus.COMPLETED && it.id !in recorded && it.resultJson != null && database.loadTurn(it.turnId)?.status == TurnStatus.INTERRUPTED }.takeLast(10).forEach { recordToolResult(it, it.resultJson!!) }
        if (!drainPending()) return
        while (step < AgentLoopPolicy.MAX_STEPS && android.os.SystemClock.elapsedRealtime() - startedAt < AgentLoopPolicy.MAX_WALL_TIME_MS) {
            currentCoroutineContext().ensureActive()
            executionTasks.check(lease)
            if (!executionTasks.available(lease.taskId)) break
            val coreDecision = runtimeBundle?.let { bundle ->
                requireNotNull(runtimeCore).decide(bundle, step, android.os.SystemClock.elapsedRealtime() - startedAt).also { decision ->
                    currentCoroutineContext().ensureActive()
                    executionTasks.check(lease)
                    database.appendEvent(session.id, "runtime.core_decision", JSONObject().put("turnId", turnId)
                        .put("runtime_core_id", runtimeId).put("version", bundle.version).put("step", step)
                        .put("action", decision.action).put("elapsed_us", decision.elapsedUs).put("cold", decision.cold))
                }
            }
            if (coreDecision?.action == "yield") {
                check(step > 0 || sliceTools > 0) { "Runtime yielded an empty slice; task stopped to prevent a busy loop" }
                break
            }
            val turnSession = database.loadSession(session.id) ?: session
            step += 1
            database.updateTurn(turnId, TurnStatus.ASSEMBLING_CONTEXT, stepCount = step)
            val preparationStarted = System.nanoTime()
            val summary = database.loadSummary(session.id)
            val task = executionTasks.get(lease.taskId)!!
            val taskTurnIds = executionTasks.turns(task.id).toSet()
            val messages = database.loadMessages(session.id, afterSequence = summary?.throughMessageSequence ?: 0, limit = AgentLoopPolicy.MAX_STORED_MESSAGES_FOR_CONTEXT).filter { it.createdAtMs <= task.document.getLong("created_at") || it.turnId in taskTurnIds }
            val memories = database.loadActiveMemories(session.id)
            val pendingClarification = database.loadPendingClarification(session.id)
            observation = usableLatestObservation(session.id) ?: observation
            val imageObservation = uiObservation ?: observation
            val routingMessages = if (messages.any { it.role == MessageRole.USER && it.turnId in taskTurnIds }) messages else messages + AtlasMessage(sessionId = session.id, turnId = turnId, role = MessageRole.USER, content = task.objective, createdAtMs = System.currentTimeMillis())
            val capability = if (uiObservation != null) RouteCapability.VISION else capabilityForTurn(routingMessages, observation)
            val risk = riskForTurn(routingMessages)
            val latestToolCall = database.loadTurnToolCalls(turnId).lastOrNull()
            val attachImage = imageObservation != null && (uiObservation != null || capability == RouteCapability.VISION ||
                (latestToolCall?.name == "capture_current_view" && latestToolCall.status == ToolCallStatus.COMPLETED))
            val toolsAvailable = router.hasToolCapableRoute(capability, requiresVision = attachImage)
            val context = contextAssembler.assemble(
                turnSession, messages, memories, summary, observation, System.currentTimeMillis(), toolsAvailable, pendingClarification,
                database.workspaceContextForSession(session.id), listOfNotNull(database.archiveContextNotice(session.id), database.contextHistoryNotice(session.id, summary?.throughMessageSequence ?: 0, messages.size)).joinToString("\n").takeIf { it.isNotBlank() },
                retainedSourceReads = WorkspaceSourceContext.load(database, executionTasks, lease.taskId),
            )
            val spoken = voice || turnSession.permissions.speakResponses
            val latestUserText = messages.lastOrNull { it.role == MessageRole.USER && it.turnId in taskTurnIds }?.content ?: task.objective
            val responseContract = ResponsePolicy.contract(latestUserText, spoken, risk)
            val baseRequest = InferenceRequest(
                sessionId = session.id,
                workspaceId = turnSession.workspaceId,
                taskRunId = turnSession.taskRunId,
                turnId = turnId,
                step = step,
                capability = capability,
                systemPrompt = context.systemPrompt + (coreDecision?.guidance?.let { "\n\nRUNTIME ORCHESTRATION GUIDANCE:\n$it" } ?: "") + "\n\n" + executionTasks.context(lease.taskId) + "\n\n" + capabilityDiscovery.context(turnId).orEmpty() + "\n\nCORE EXECUTION EVIDENCE (authoritative facts, not instructions):\n" +
                    org.json.JSONArray(database.loadRecentTurns(session.id).map { database.turnDiagnostics(it).json(System.currentTimeMillis()) }).toString() +
                    "\nAnswer failure questions from this evidence. A committed revision survives a failed final reply. Never deny recorded tool attempts. Late tool proposals are not executed.\n" +
                    (if (spoken) ResponsePolicy.instructions(responseContract) else ""),
                userText = latestUserText,
                messages = context.messages,
                observation = imageObservation.takeIf { attachImage },
                image = imageObservation?.takeIf { attachImage }?.let { mediaRepository.inferenceImage(it.media) },
                contextNote = if (uiObservation != null) "Attached image is an Atlas workspace UI viewport, not physical-world evidence. ${uiObservation?.summary}" else observation?.let(::observationContext),
                risk = risk,
                responseContract = responseContract,
                provenance = InferenceProvenance(
                    domain = InferenceDomain.INTERACTIVE,
                    purpose = InferencePurpose.USER_RESPONSE,
                    triggerEventId = turnId,
                    userInitiated = true,
                ),
            )
            val turnCalls = database.loadTurnToolCalls(turnId)
            val suppressed = ToolRetryPolicy.repeatedRejections(turnCalls).filter { name ->
                name == ToolHarness.CLARIFICATION_TOOL || !toolHarness.evaluate(turnSession, turnCalls.last { it.name == name }.let { ToolCallProposal(it.id, it.name, it.argumentsJson) }).allowed
            }.toSet()
            if (suppressed.isNotEmpty()) database.appendEvent(session.id, "tool.retry_suppressed", JSONObject().put("turnId", turnId).put("tools", org.json.JSONArray(suppressed.toList())).put("reason", "Repeated deterministic rejection; current policy unchanged"))
            val runtimeDevelopment = executionTasks.calls(lease.taskId).any { it.name == "atlas_runtime_status" && it.status == ToolCallStatus.COMPLETED }
            val visualDevelopment = executionTasks.calls(lease.taskId).any { it.name == "workspace_visual_status" && it.status == ToolCallStatus.COMPLETED }
            val request = baseRequest.copy(
                tools = if (toolsAvailable) toolHarness.definitions.filter { it.name !in suppressed &&
                    (runtimeDevelopment || it.name !in setOf("atlas_runtime_read", "atlas_runtime_stage", "atlas_runtime_activate")) && (visualDevelopment || it.name != "workspace_visual_test") }.map(WorkspaceArgumentBinding::definition) else emptyList(),
                systemPrompt = baseRequest.systemPrompt + if (suppressed.isEmpty()) "" else "\nCORE RETRY LIMIT: ${suppressed.joinToString()} repeatedly rejected. Do not repeat these calls. Correct a different control or report the unresolved blocker honestly; discovery resume_local only restores installed local mutations under ordinary policy.",
            )
            val packet = InferencePacketCompiler.compile(request)
            database.appendEvent(session.id, "context.assembled", JSONObject().apply {
                put("turnId", turnId); put("step", step); put("messageIds", org.json.JSONArray(context.includedMessageIds)); put("projectedMessageIds", org.json.JSONArray(context.projectedMessageIds))
                put("omittedMessages", context.omittedMessageCount); put("approximateCharacters", context.approximateCharacters)
                put("retainedSourceCharacters", context.retainedSourceCharacters)
                put("packet", packet); put("preparation_us", (System.nanoTime() - preparationStarted) / 1000)
                put("memoryCount", memories.size); put("summaryThrough", summary?.throughMessageSequence); put("toolsAvailable", request.tools.size)
            })
            val estimatedInput = (packet.getLong("text_characters") + 3L) / 4 + if (request.image != null) 4096L else 0L
            if (!executionTasks.reserveInference(lease, estimatedInput.coerceAtLeast(1), maxOf(2048L, request.responseContract?.maxOutputTokens?.toLong() ?: 2048L), request.requestId)) { executionTasks.control(lease.taskId, ExecutionState.WAITING, "task_allowance"); break }
            database.updateTurn(turnId, TurnStatus.WAITING_FOR_MODEL, stepCount = step)
            publish(phase = RuntimePhase.THINKING)
            database.appendEvent(session.id, "provider.requested", JSONObject().put("turnId", turnId).put("step", step).put("requestId", request.requestId)
                .put("capability", capability.name).put("risk", request.risk.name).put("latencyClass", request.latencyClass.name)
                .put("toolCount", request.tools.size).put("visionAttached", request.image != null)
                .put("maxOutputTokens", request.responseContract?.maxOutputTokens).put("softTimeoutMs", MODEL_SOFT_TIMEOUT_MS)
                .putProvenance(request.provenance))
            val operationGeneration = generation.get()
            val response = try {
                streamModelStep(session, turnId, request, spoken)
            } catch (_: SoftTimeoutDetached) {
                return
            } catch (error: Exception) {
                database.appendEvent(session.id, "provider.failed", JSONObject().put("turnId", turnId).put("step", step).put("requestId", request.requestId)
                    .put("error", error.safeMessage()).apply { (error as? com.grinningfrog.atlas.provider.InferenceUnavailableException)?.providerCode?.let { put("errorCode", it) } })
                throw error
            }
            if (generation.get() != operationGeneration || mutableState.value.session?.status != SessionStatus.ACTIVE) {
                database.appendEvent(session.id, "provider.result_discarded", JSONObject().put("turnId", turnId).put("requestId", request.requestId).put("reason", "session generation changed"))
                throw CancellationException("Session changed during inference")
            }
            executionTasks.check(lease)
            executionTasks.reconcileInference(lease, response.promptTokens?.toLong(), response.completionTokens?.toLong())
            database.appendEvent(session.id, "provider.responded", JSONObject().apply {
                put("turnId", turnId); put("step", step); put("requestId", request.requestId); put("endpointId", response.endpointId)
                put("model", response.selectedModel); put("routingProfile", response.routingProfile); put("routingReason", response.routingReason)
                put("routingRevision", response.routingRevision); put("latencyMs", response.latencyMs); put("degraded", response.degraded)
                put("firstTokenLatencyMs", response.firstTokenLatencyMs)
                put("promptTokens", response.promptTokens); put("completionTokens", response.completionTokens); put("totalTokens", response.totalTokens)
                put("finishReason", response.finishReason); put("providerContinuationId", response.providerContinuationId)
                put("text", response.text); put("toolCallCount", response.toolCalls.size)
                put("providerToolUses", org.json.JSONArray(response.providerToolUses.map { use -> JSONObject().apply {
                    put("type", use.type); put("action", use.action); put("status", use.status)
                    put("readOnlyResearch", use.readOnlyResearch); put("id", use.id)
                    put("queries", org.json.JSONArray(use.queries))
                    put("urls", org.json.JSONArray(use.urls))
                } }))
                put("citations", org.json.JSONArray(response.citations.map { citation -> JSONObject()
                    .put("title", citation.title).put("url", citation.url) }))
            })
            capabilityDiscovery.hostedResearch(session.id, turnId, response)
            if (!executionTasks.observeResponse(lease, response.finishReason)) {
                database.updateTurn(turnId, TurnStatus.YIELDED, stepCount = step, error = "Incomplete provider output; continuation required")
                return
            }
            require(response.toolCalls.map { it.id }.distinct().size == response.toolCalls.size) {
                "Provider returned duplicate tool-call ids in one response"
            }
            val clarificationCalls = response.toolCalls.filter { it.name == ToolHarness.CLARIFICATION_TOOL }
            if (clarificationCalls.isNotEmpty()) {
                val rejection = if (ToolHarness.CLARIFICATION_TOOL in suppressed) StructuredToolRejection("Repeated invalid clarification control", "clarification_retry_limit", JSONObject().put("hint", "Report the unresolved question; do not repeat the rejected control in this turn."))
                    else ClarificationControl.rejection(response.toolCalls, database.loadPendingClarification(session.id)?.id)
                if (rejection != null) {
                    val prior = database.loadTurnToolCalls(turnId)
                    require(response.toolCalls.size <= 64) { "Oversized provider tool batch" }
                    response.toolCalls.forEachIndexed { index, proposal ->
                        val call = prepareToolCall(turnSession, turnId, step, index, proposal, prior, index).copy(status = ToolCallStatus.REJECTED, requiresConfirmation = false, error = rejection.message)
                        database.insertToolCall(call)
                        database.appendEvent(session.id, "clarification.control_rejected", JSONObject().put("turnId", turnId).put("toolCallId", call.id).put("error", rejection.message).put("recovery", rejection.recovery))
                        recordToolResult(call, ToolFailurePayload.json(rejection, rejection.message.orEmpty()).toString())
                    }
                    continue
                }
                when (handleClarificationControl(session, turnId, clarificationCalls.single(), observation, spoken)) {
                    ClarificationControlOutcome.WAITING -> {
                        database.updateTurn(turnId, TurnStatus.WAITING_FOR_USER_CLARIFICATION, stepCount = step)
                        publish(response = database.loadPendingClarification(session.id)?.question ?: response.text, streamingResponse = null, phase = RuntimePhase.READY)
                        return
                    }
                    ClarificationControlOutcome.DEFERRED -> {
                        database.updateTurn(turnId, TurnStatus.COMPLETED, stepCount = step)
                        publish(response = response.text.takeIf(String::isNotBlank), streamingResponse = null, phase = if (speech.isSpeaking) RuntimePhase.SPEAKING else RuntimePhase.READY)
                        return
                    }
                    ClarificationControlOutcome.CONTINUE -> continue
                }
            }
            if (pendingClarification?.blocking == true && response.toolCalls.isNotEmpty()) {
                database.appendEvent(session.id, "provider.result_constrained", JSONObject().put("turnId", turnId)
                    .put("reason", "physical tools blocked by unresolved clarification").put("clarificationId", pendingClarification.id)
                    .put("blockedToolCount", response.toolCalls.size))
                ensureClarificationDelivered(session, turnId, pendingClarification, spoken)
                database.updateTurn(turnId, TurnStatus.WAITING_FOR_USER_CLARIFICATION, stepCount = step)
                publish(response = pendingClarification.question, streamingResponse = null, phase = RuntimePhase.READY)
                return
            }
            if (response.toolCalls.isEmpty()) {
                database.updateTurn(turnId, TurnStatus.COMPLETED, stepCount = step)
                publish(response = response.text, streamingResponse = null, error = null, phase = if (speech.isSpeaking) RuntimePhase.SPEAKING else RuntimePhase.READY)
                scheduleCompaction(session)
                return
            }

            val priorCalls = database.loadTurnToolCalls(turnId)
            require(response.toolCalls.size <= 64) { "Oversized provider tool batch" }
            val calls = response.toolCalls.mapIndexed { index, proposal ->
                val earlierInBatch = response.toolCalls.take(index).count { it.name == proposal.name }
                prepareToolCall(turnSession, turnId, step, index, proposal, priorCalls, earlierInBatch)
            }
            calls.forEach(database::insertToolCall)
            calls.filter { it.status == ToolCallStatus.REJECTED }.forEach { call -> recordToolResult(call, JSONObject().put("ok", false).put("error", call.error).toString()) }
            if (!drainPending()) return
            if (calls.any { it.status == ToolCallStatus.WAITING_FOR_CONFIRMATION }) {
                database.updateTurn(turnId, TurnStatus.WAITING_FOR_CONFIRMATION, stepCount = step)
                publish(response = response.text.takeIf(String::isNotBlank), streamingResponse = null, phase = if (speech.isSpeaking) RuntimePhase.SPEAKING else RuntimePhase.READY)
                return
            }
        }
        database.updateTurn(turnId, TurnStatus.YIELDED, stepCount = step)
        database.appendEvent(session.id, "execution.slice_yielded", JSONObject().put("turnId", turnId).put("execution_task_id", lease.taskId).put("steps", step).put("tool_calls", sliceTools).put("elapsed_ms", android.os.SystemClock.elapsedRealtime() - startedAt))
        publish(phase = RuntimePhase.THINKING)
    }

    private suspend fun handleClarificationControl(
        session: AtlasSession,
        turnId: String,
        proposal: ToolCallProposal,
        observation: VisualObservation?,
        spoken: Boolean,
    ): ClarificationControlOutcome {
        val arguments = JSONObject(proposal.argumentsJson)
        val action = arguments.getString("action").lowercase()
        val pending = database.loadPendingClarification(session.id)
        return when (action) {
            "request" -> {
                val question = arguments.optString("question").trim().take(300)
                val reason = arguments.optString("reason").trim().take(500)
                require(question.isNotBlank() && reason.isNotBlank()) { "Clarification request requires a question and reason" }
                val ambiguity = runCatching { ClarificationAmbiguity.valueOf(arguments.optString("ambiguity", "other").uppercase()) }
                    .getOrDefault(ClarificationAmbiguity.OTHER)
                val optionsJson = arguments.optJSONArray("options")
                val options = buildList { if (optionsJson != null) for (index in 0 until minOf(optionsJson.length(), 5)) {
                    optionsJson.optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
                } }
                val now = System.currentTimeMillis()
                val clarification = PendingClarification(
                    id = UUID.randomUUID().toString(), sessionId = session.id, sourceTurnId = turnId, question = question, reason = reason,
                    ambiguity = ambiguity, options = options, blocking = arguments.optBoolean("blocking", true),
                    contextObservationIds = observation?.let { listOf(it.id) }.orEmpty(), createdAtMs = now, updatedAtMs = now,
                )
                database.saveClarification(clarification)
                recordClarificationControlResult(session.id, turnId, proposal, "requested", clarification.id)
                ensureClarificationDelivered(session, turnId, clarification, spoken)
                ClarificationControlOutcome.WAITING
            }
            "resolve", "defer", "abandon" -> {
                requireNotNull(pending) { "Provider tried to $action a clarification when none is pending" }
                val id = arguments.optString("clarification_id")
                require(id == pending.id) { "Clarification control does not match the pending question" }
                val status = when (action) {
                    "resolve" -> ClarificationStatus.RESOLVED
                    "defer" -> ClarificationStatus.DEFERRED
                    else -> ClarificationStatus.ABANDONED
                }
                database.updateClarification(id, status, turnId, arguments.optString("normalized_answer").trim().takeIf(String::isNotBlank))
                recordClarificationControlResult(session.id, turnId, proposal, action, id)
                if (status != ClarificationStatus.DEFERRED) runCatching { database.updateTurn(pending.sourceTurnId, TurnStatus.COMPLETED) }
                if (status == ClarificationStatus.DEFERRED) ClarificationControlOutcome.DEFERRED else ClarificationControlOutcome.CONTINUE
            }
            else -> error("Unsupported clarification action: $action")
        }
    }

    private fun recordClarificationControlResult(sessionId: String, turnId: String, proposal: ToolCallProposal, action: String, clarificationId: String) {
        val result = JSONObject().put("ok", true).put("accepted", true).put("action", action).put("clarification_id", clarificationId).toString()
        val now = System.currentTimeMillis()
        database.writeTransaction {
            val receipt = AtlasToolCall(id = proposal.id, sessionId = sessionId, turnId = turnId,
                name = proposal.name, argumentsJson = proposal.argumentsJson, status = ToolCallStatus.COMPLETED,
                risk = ToolRisk.SESSION_WRITE, requiresConfirmation = false, idempotencyKey = "$turnId:control:${proposal.id}",
                createdAtMs = now, updatedAtMs = now)
            database.insertToolCall(receipt)
            database.updateToolCall(receipt.id, ToolCallStatus.COMPLETED, resultJson = result)
            recordToolResult(receipt, result)
        }
    }

    private suspend fun ensureClarificationDelivered(session: AtlasSession, turnId: String, clarification: PendingClarification, spoken: Boolean) {
        val latestAssistant = database.loadMessages(session.id, limit = 8).lastOrNull { it.turnId == turnId && it.role == MessageRole.ASSISTANT }
        latestAssistant?.let { database.updateAssistantMessage(it.id, clarification.question) }
            ?: database.insertMessage(AtlasMessage(sessionId = session.id, turnId = turnId, role = MessageRole.ASSISTANT,
                content = clarification.question, createdAtMs = System.currentTimeMillis(), deliveryStatus = if (spoken) DeliveryStatus.PENDING else DeliveryStatus.TEXT_ONLY))
        if (spoken && latestAssistant?.content?.trim() != clarification.question) {
            speech.stopSpeaking()
            speakLocked(session, clarification.question)
        }
    }

    /**
     * Converts any provider into the same streamed Core contract. The assistant message exists before
     * audio begins so delivery callbacks and crash recovery always have a durable owner.
     */
    private suspend fun streamModelStep(
        session: AtlasSession,
        turnId: String,
        request: InferenceRequest,
        spoken: Boolean,
    ): InferenceResponse {
        val messageId = UUID.randomUUID().toString()
        database.insertMessage(AtlasMessage(
            id = messageId,
            sessionId = session.id,
            turnId = turnId,
            role = MessageRole.ASSISTANT,
            content = "",
            createdAtMs = System.currentTimeMillis(),
            kind = MessageKind.INTERNAL,
            deliveryStatus = if (spoken) DeliveryStatus.PENDING else DeliveryStatus.TEXT_ONLY,
        ))
        val generated = StringBuilder()
        val segmenter = SentenceSegmenter()
        var sentenceIndex = 0
        var completed: InferenceResponse? = null
        var lastCheckpointAt = 0L
        val softTimedOut = AtomicBoolean(false)
        val result = CompletableDeferred<Result<InferenceResponse>>()
        val collector = scope.launch {
            try {
                router.stream(request).collect { event ->
                    when (event) {
                        is InferenceStreamEvent.TextDelta -> {
                            generated.append(event.text)
                            val now = System.currentTimeMillis()
                            if (now - lastCheckpointAt >= STREAM_CHECKPOINT_INTERVAL_MS) {
                                database.updateAssistantMessage(messageId, generated.toString())
                                lastCheckpointAt = now
                            }
                            if (!softTimedOut.get()) {
                                publishTransient(response = generated.toString(), streamingResponse = generated.toString(),
                                    phase = if (speech.isSpeaking) RuntimePhase.SPEAKING else RuntimePhase.THINKING)
                                if (spoken) segmenter.append(event.text).forEach { sentence ->
                                    enqueueSentence(session, turnId, messageId, sentenceIndex++, sentence)
                                }
                            }
                        }
                        is InferenceStreamEvent.ToolCallDelta -> Unit
                        is InferenceStreamEvent.Completed -> completed = event.response
                    }
                }
                if (spoken && !softTimedOut.get()) segmenter.finish().forEach { sentence ->
                    enqueueSentence(session, turnId, messageId, sentenceIndex++, sentence)
                }
                val response = checkNotNull(completed) { "Provider stream ended without a completed response" }
                database.updateAssistantMessage(
                    messageId,
                    response.text,
                    response.toolCalls.takeIf { it.isNotEmpty() }?.let(::toolCallsJson),
                    providerContextJson(response),
                )
                if (softTimedOut.get()) {
                    database.refreshMessageDelivery(messageId, DeliveryStatus.TEXT_ONLY)
                    database.updateTurn(turnId, TurnStatus.COMPLETED_LATE, stepCount = request.step)
                    executionTasks.reconcileLateInference(turnId,request.requestId,response.promptTokens?.toLong(),response.completionTokens?.toLong())
                    database.appendEvent(session.id, "provider.completed_late", lateResultEvent(turnId, messageId, request, response))
                    publish(error = "Response timed out at ${MODEL_SOFT_TIMEOUT_MS / 1_000}s and completed later. The diagnostic result is available below.", phase = RuntimePhase.READY)
                } else {
                    database.promoteAssistantMessage(messageId)
                    database.refreshMessageDelivery(messageId, when {
                        !spoken || response.text.isBlank() -> DeliveryStatus.TEXT_ONLY
                        sentenceIndex > 0 -> DeliveryStatus.PENDING
                        else -> DeliveryStatus.FAILED
                    })
                    publish(response = response.text, streamingResponse = null)
                }
                result.complete(Result.success(response))
            } catch (cancelled: CancellationException) {
                speech.stopSpeaking()
                database.updateAssistantMessage(messageId, generated.toString().trim())
                database.refreshMessageDelivery(messageId, DeliveryStatus.INTERRUPTED)
                database.appendEvent(session.id, "provider.stream_interrupted", JSONObject().put("turnId", turnId).put("requestId", request.requestId)
                    .put("messageId", messageId).put("receivedCharacters", generated.length).put("afterSoftTimeout", softTimedOut.get()))
                result.cancel(cancelled)
            } catch (error: Exception) {
                speech.stopSpeaking()
                database.updateAssistantMessage(messageId, generated.toString().trim())
                database.refreshMessageDelivery(messageId, DeliveryStatus.FAILED)
                if (softTimedOut.get()) {
                    database.updateTurn(turnId, TurnStatus.HARD_CANCELLED, stepCount = request.step, error = error.safeMessage())
                    database.appendEvent(session.id, "provider.late_failed", JSONObject().put("turnId", turnId).put("requestId", request.requestId)
                        .put("messageId", messageId).put("error", error.safeMessage()).put("receivedCharacters", generated.length))
                    publish(error = "Late generation stopped: ${error.safeMessage()}", phase = RuntimePhase.READY)
                }
                result.complete(Result.failure(error))
            } finally {
                if (lateTurnId == turnId) {
                    lateTurnJob = null
                    lateTurnId = null
                }
                publish()
            }
        }
        lateTurnJob = collector
        lateTurnId = turnId
        result.invokeOnCompletion {if(result.isCancelled)collector.cancel()}
        val durableAuthoring=activeExecutionLease?.let {executionTasks.get(it.taskId)?.document?.optJSONObject("completion_requirement")?.optString("delivery") in setOf("activation","draft")}==true
        val timely = awaitModelResponse(result,durableAuthoring,{
            database.appendEvent(session.id,"provider.authoring_still_running",JSONObject().put("turnId",turnId).put("requestId",request.requestId).put("interaction_deadline_ms",MODEL_SOFT_TIMEOUT_MS))
            publish(error=null)
        },{activeExecutionLease?.let(executionTasks::check) ?: throw CancellationException("Task lease unavailable")})
        if (timely != null) return timely.getOrThrow()

        softTimedOut.set(true)
        speech.stopSpeaking()
        database.updateAssistantMessage(messageId, generated.toString().trim())
        database.refreshMessageDelivery(messageId, DeliveryStatus.INTERRUPTED)
        database.updateTurn(turnId, TurnStatus.SOFT_TIMED_OUT, stepCount = request.step, error = "Provider exceeded the ${MODEL_SOFT_TIMEOUT_MS / 1_000}s interaction deadline")
        database.appendEvent(session.id, "provider.soft_timed_out", JSONObject().put("turnId", turnId).put("requestId", request.requestId)
            .put("messageId", messageId).put("softTimeoutMs", MODEL_SOFT_TIMEOUT_MS).put("receivedCharacters", generated.length)
            .put("partialText", generated.toString()).put("generationContinues", true))
        publish(response = null, streamingResponse = null,
            error = "Response timed out after ${MODEL_SOFT_TIMEOUT_MS / 1_000}s. Diagnostic generation is still running.", phase = RuntimePhase.READY)
        throw SoftTimeoutDetached()
    }

    private fun lateResultEvent(turnId: String, messageId: String, request: InferenceRequest, response: InferenceResponse) = JSONObject().apply {
        put("turnId", turnId); put("messageId", messageId); put("requestId", request.requestId); put("endpointId", response.endpointId)
        put("text", response.text); put("latencyMs", response.latencyMs); put("firstTokenLatencyMs", response.firstTokenLatencyMs)
        put("promptTokens", response.promptTokens); put("completionTokens", response.completionTokens); put("totalTokens", response.totalTokens)
        put("tokensPerSecond", response.completionTokens?.takeIf { response.latencyMs > 0 }?.let { it * 1_000.0 / response.latencyMs })
        put("finishReason", response.finishReason); put("toolCallCount", response.toolCalls.size)
        put("visionAttached", request.image != null); put("maxOutputTokens", request.responseContract?.maxOutputTokens)
        put("completedAtMs", System.currentTimeMillis())
    }

    private suspend fun enqueueSentence(
        session: AtlasSession,
        turnId: String,
        messageId: String,
        sentenceIndex: Int,
        sentence: String,
    ) {
        if (sentence.isBlank()) return
        val segment = SpeechSegment(
            messageId = messageId,
            sessionId = session.id,
            turnId = turnId,
            sentenceIndex = sentenceIndex,
            text = sentence,
            status = SpeechSegmentStatus.QUEUED,
            queuedAtMs = System.currentTimeMillis(),
        )
        database.saveSpeechSegment(segment)
        val started = AtomicBoolean(false)
        try {
            speech.enqueueSpeech(segment.id, sentence, flushQueue = false, callbacks = SpeechCallbacks(
                onStarted = {
                    started.set(true)
                    database.updateSpeechSegment(segment.id, SpeechSegmentStatus.STARTED)
                    publish(phase = RuntimePhase.SPEAKING)
                },
                onCompleted = {
                    database.updateSpeechSegment(segment.id, SpeechSegmentStatus.COMPLETED)
                    publish(phase = if (speech.isSpeaking) RuntimePhase.SPEAKING else if (activeTurnJob?.isActive == true) RuntimePhase.THINKING else RuntimePhase.READY)
                },
                onInterrupted = {
                    database.updateSpeechSegment(segment.id, if (started.get()) SpeechSegmentStatus.INTERRUPTED else SpeechSegmentStatus.SKIPPED)
                    publish(phase = if (activeTurnJob?.isActive == true) RuntimePhase.THINKING else RuntimePhase.READY)
                },
                onFailed = { error ->
                    database.updateSpeechSegment(segment.id, SpeechSegmentStatus.FAILED, error = error.safeMessage())
                    database.appendEvent(session.id, "audio.speech_failed", JSONObject().put("messageId", messageId).put("segmentId", segment.id)
                        .put("error", error.safeMessage()).put("textFallbackPreserved", true))
                    publish(phase = if (activeTurnJob?.isActive == true) RuntimePhase.THINKING else RuntimePhase.READY)
                },
            ))
        } catch (error: Exception) {
            database.updateSpeechSegment(segment.id, SpeechSegmentStatus.FAILED, error = error.safeMessage())
            throw error
        }
    }

    private fun prepareToolCall(
        session: AtlasSession,
        turnId: String,
        step: Int,
        index: Int,
        proposal: ToolCallProposal,
        priorCalls: List<AtlasToolCall>,
        earlierInBatch: Int,
    ): AtlasToolCall {
        require(proposal.id.isNotBlank()) { "Provider returned a tool call without an id" }
        require(priorCalls.none { it.id == proposal.id }) { "Provider reused tool-call id ${proposal.id}" }
        val bound = executionTasks.bindWorkspaceArguments(turnId, proposal)
        if (bound.argumentsJson != proposal.argumentsJson) database.appendEvent(session.id, "tool.arguments_bound",
            JSONObject().put("toolCallId", proposal.id).put("tool", proposal.name)
                .put("notice", "Missing identifiers and user evidence resolved from the durable task; canonical arguments are in the tool call audit."))
        val definition = toolHarness.definition(bound.name)
        val policy = toolHarness.evaluate(session, bound)
        val withinBudget = definition != null
        val accepted = policy.allowed && withinBudget
        val timestamp = System.currentTimeMillis()
        return AtlasToolCall(
            id = proposal.id,
            sessionId = session.id,
            turnId = turnId,
            name = proposal.name,
            argumentsJson = bound.argumentsJson,
            status = when {
                !accepted -> ToolCallStatus.REJECTED
                policy.requiresConfirmation -> ToolCallStatus.WAITING_FOR_CONFIRMATION
                else -> ToolCallStatus.PROPOSED
            },
            risk = policy.risk,
            requiresConfirmation = accepted && policy.requiresConfirmation,
            idempotencyKey = "$turnId:$step:$index:${proposal.name}",
            reason = if(proposal.name=="workspace_accept_unverified") policy.reason else proposal.reason ?: policy.reason,
            error = when {
                !policy.allowed -> policy.reason
                !withinBudget -> "Tool is not installed: ${proposal.name}"
                else -> null
            },
            createdAtMs = timestamp,
            updatedAtMs = timestamp,
        )
    }

    private suspend fun executeToolLocked(call: AtlasToolCall): VisualObservation? {
        val current = database.loadToolCall(call.id) ?: call
        if (current.status == ToolCallStatus.COMPLETED || current.status == ToolCallStatus.REJECTED || current.status == ToolCallStatus.FAILED) return null
        if (current.status == ToolCallStatus.UNKNOWN) error("Tool outcome is unknown and cannot be retried automatically")
        activeExecutionLease?.let(executionTasks::check)
        database.updateTurn(activeTurnId ?: call.turnId, TurnStatus.EXECUTING_TOOL)
        database.updateToolCall(call.id, ToolCallStatus.RUNNING)
        publish()
        try {
            val dispatchLease = activeExecutionLease
            val result = if (dispatchLease == null) toolHarness.execute(call) else withContext(
                ToolExecutionContext(dispatchLease, requireNotNull(activeTurnId), call.id, call.turnId)
            ) { toolHarness.execute(call) }
            activeExecutionLease?.let(executionTasks::check)
            database.updateToolCall(call.id, ToolCallStatus.COMPLETED, resultJson = result.resultJson)
            recordToolResult(call, result.resultJson)
            return result.observation
        } catch (cancelled: CancellationException) {
            val result=JSONObject().put("ok",false).put("cancelled",true)
                .put("error","Execution cancelled; no successful completion was recorded").toString()
            if(database.loadToolCall(call.id)?.status == ToolCallStatus.RUNNING) {
                database.updateToolCall(call.id, if(call.risk == com.grinningfrog.atlas.model.ToolRisk.EXTERNAL_EFFECT) ToolCallStatus.UNKNOWN else ToolCallStatus.FAILED,
                    resultJson=result,error="Execution cancelled")
            }
            database.appendEvent(call.sessionId,"tool.cancelled",JSONObject().put("tool",call.name).put("toolCallId",call.id))
            throw cancelled
        } catch (error: Exception) {
            val result = ToolFailurePayload.json(error, error.safeMessage()).toString()
            database.updateToolCall(call.id, if (call.risk == com.grinningfrog.atlas.model.ToolRisk.EXTERNAL_EFFECT) ToolCallStatus.UNKNOWN else ToolCallStatus.FAILED, resultJson = result, error = error.safeMessage())
            database.appendEvent(call.sessionId, "tool.failed", JSONObject().put("tool", call.name).put("error", error.safeMessage()))
            val count = database.loadRecentEvents(call.sessionId, 100).count { event ->
                event.type == "tool.failed" && runCatching { JSONObject(event.dataJson).optString("tool") == call.name }.getOrDefault(false)
            }
            idleRuntime?.observeRepeatedFailure("${call.name} repeatedly failed", error.safeMessage(), count)
            recordToolResult(call, result)
            return null
        } finally {
            publish()
        }
    }

    private fun recordToolResult(call: AtlasToolCall, resultJson: String) {
        database.insertMessage(AtlasMessage(
            sessionId = call.sessionId,
            turnId = call.turnId,
            role = MessageRole.TOOL,
            content = resultJson,
            createdAtMs = System.currentTimeMillis(),
            kind = MessageKind.TOOL_RESULT,
            toolCallId = call.id,
        ))
    }

    private fun toolCallsJson(calls: List<ToolCallProposal>) = org.json.JSONArray().apply {
        calls.forEach { call -> put(JSONObject().put("id", call.id).put("name", call.name).put("arguments", call.argumentsJson).apply {
            call.reason?.let { put("reason", it) }
        }) }
    }.toString()

    private fun providerContextJson(response: InferenceResponse): String? {
        if (response.providerToolUses.isEmpty() && response.citations.isEmpty()) return null
        return JSONObject().apply {
            put("providerToolUses", org.json.JSONArray(response.providerToolUses.map { use -> JSONObject().apply {
                put("type", use.type); put("action", use.action); put("status", use.status)
                    put("readOnlyResearch", use.readOnlyResearch); put("id", use.id)
                put("queries", org.json.JSONArray(use.queries)); put("urls", org.json.JSONArray(use.urls))
            } }))
            put("citations", org.json.JSONArray(response.citations.map { citation -> JSONObject()
                .put("title", citation.title).put("url", citation.url) }))
        }.toString()
    }

    private fun usableLatestObservation(sessionId: String) = database.loadLatestObservation(sessionId)?.takeIf {
        it.media.byteSize > 0 && it.media.sha256.isNotBlank()
    }

    private fun capabilityForTurn(messages: List<AtlasMessage>, observation: VisualObservation?): RouteCapability {
        val text = messages.lastOrNull { it.role == MessageRole.USER }?.content.orEmpty()
        val freshness = FreshnessPolicy.assess(text, observation, System.currentTimeMillis())
        return InferenceIntentPolicy.capability(text, freshness.useCase)
    }

    private fun riskForTurn(messages: List<AtlasMessage>): com.grinningfrog.atlas.model.InferenceRisk {
        val text = messages.lastOrNull { it.role == MessageRole.USER }?.content.orEmpty()
        return when (FreshnessPolicy.assess(text, null, System.currentTimeMillis()).useCase) {
            VisualUseCase.HIGH_RISK -> com.grinningfrog.atlas.model.InferenceRisk.SAFETY_CRITICAL
            VisualUseCase.NAVIGATION -> com.grinningfrog.atlas.model.InferenceRisk.ELEVATED
            else -> com.grinningfrog.atlas.model.InferenceRisk.NORMAL
        }
    }

    private suspend fun compactConversationIfNeeded(session: AtlasSession) {
        val prior = database.loadSummary(session.id)
        if (database.messageCountAfter(session.id, prior?.throughMessageSequence ?: 0) < AgentLoopPolicy.COMPACTION_THRESHOLD) return
        val pending = database.loadMessagesForCompaction(session.id, afterSequence = prior?.throughMessageSequence ?: 0, limit = AgentLoopPolicy.MAX_COMPACTION_MESSAGES)
        val turns = pending.fold(mutableListOf<MutableList<AtlasMessage>>()) { groups, message ->
            if (groups.lastOrNull()?.lastOrNull()?.turnId != message.turnId) groups += mutableListOf<AtlasMessage>()
            groups.last() += message
            groups
        }
        val candidates = turns.dropLast(AgentLoopPolicy.RECENT_TURNS_AFTER_COMPACTION).flatten()
        if (candidates.isEmpty()) return
        val batch = try { CheckpointPlanner.select(candidates) } catch (error: IllegalArgumentException) {
            database.appendEvent(session.id, "memory.compaction_failed", JSONObject().put("stage", "context_budget").put("error", error.safeMessage()))
            return
        }
        val transcript = batch.transcript
        val prompt = buildString {
            appendLine("Create a compact, factual checkpoint for a continuing conversation. Preserve user preferences, corrections, decisions, named entities, completed work, pending tasks, safety constraints, and unresolved references. Do not add facts. Do not describe the summarization process.")
            prior?.let { appendLine("Previous checkpoint:\n${it.summary}") }
            appendLine("Transcript segment:\n$transcript")
        }
        val request = InferenceRequest(
            sessionId = session.id,
            workspaceId = session.workspaceId,
            taskRunId = session.taskRunId,
            capability = RouteCapability.FAST,
            systemPrompt = "You produce loss-minimizing conversation checkpoints for Atlas Core.",
            userText = prompt,
            latencyClass = com.grinningfrog.atlas.model.LatencyClass.BACKGROUND,
            provenance = InferenceProvenance(
                domain = InferenceDomain.MEMORY,
                purpose = InferencePurpose.MEMORY_COMPACTION,
                triggerEventId = batch.messages.last().id,
                userInitiated = false,
            ),
        )
        database.appendEvent(session.id, "provider.requested", JSONObject().put("source", "memory_compaction").put("requestId", request.requestId).putProvenance(request.provenance))
        try {
            val response = router.route(request)
            val through = batch.messages.last().sequence
            database.appendEvent(session.id, "provider.responded", JSONObject().put("source", "memory_compaction").put("requestId", request.requestId)
                .put("endpointId", response.endpointId).put("model", response.selectedModel).put("routingProfile", response.routingProfile)
                .put("routingReason", response.routingReason).put("routingRevision", response.routingRevision)
                .put("latencyMs", response.latencyMs).put("firstTokenLatencyMs", response.firstTokenLatencyMs)
                .put("promptTokens", response.promptTokens).put("completionTokens", response.completionTokens)
                .put("totalTokens", response.totalTokens).put("finishReason", response.finishReason)
                .put("providerContinuationId", response.providerContinuationId).put("candidateThroughMessageSequence", through))
            CheckpointPlanner.validateResponse(response.text, response.finishReason)
            database.saveSummary(SessionSummary(session.id, response.text, through, System.currentTimeMillis()))
            database.appendEvent(session.id, "memory.compacted", JSONObject().put("throughMessageSequence", through).put("requestId", request.requestId))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            database.appendEvent(session.id, "memory.compaction_failed", JSONObject().put("requestId", request.requestId).put("error", error.safeMessage()))
        }
    }

    private fun scheduleCompaction(session: AtlasSession) {
        if (compactionJob?.isActive == true) return
        compactionJob = scope.launch {
            if (mutableState.value.session?.id == session.id && mutableState.value.session?.status == SessionStatus.ACTIVE) {
                compactConversationIfNeeded(session)
                publish()
            }
        }
    }

    fun stopSpeaking() {
        idleRuntime?.onUserInteraction()
        val turnStatus = activeTurnId?.let(database::loadTurn)?.status
        val turnStillGenerating = activeTurnJob?.isActive == true && turnStatus !in setOf(
            TurnStatus.COMPLETED, TurnStatus.COMPLETED_LATE, TurnStatus.FAILED, TurnStatus.CANCELLED,
            TurnStatus.HARD_CANCELLED, TurnStatus.INTERRUPTED,
        )
        if (turnStillGenerating) cancelActiveTurn("user stopped speech") else speech.stopSpeaking()
        mutableState.value.session?.let { database.appendEvent(it.id, "audio.speech_interrupted", JSONObject().put("generationCancelled", turnStillGenerating)) }
        publish(phase = RuntimePhase.READY)
    }

    fun close() {
        idleRuntime?.close()
        heartbeatJob?.cancel()
        compactionJob?.cancel()
        lateTurnJob?.cancel()
        onLiveContextChanged(false)
        stopDevices()
    }

    fun setIntentAutonomyMode(mode: IntentAutonomyMode) = idleRuntime?.setMode(mode)
    fun pauseIdleRuntime() = idleRuntime?.pause()
    fun resumeIdleRuntime() = idleRuntime?.resume()
    fun forceIdleEvaluation() = idleRuntime?.forceEvaluation()
    fun changeIntentState(intentId: String, state: IntentState) = idleRuntime?.changeIntentState(intentId, state)

    private suspend fun startDevices() {
        if (devicesStarted) return
        motion.start()
        try {
            camera.start()
            speech.start()
            devicesStarted = true
        } catch (error: Exception) {
            camera.stop(); motion.stop(); speech.close()
            throw error
        }
    }

    private fun stopDevices() {
        camera.stop(); motion.stop(); speech.close()
        devicesStarted = false
    }

    private fun startHeartbeat() {
        if (heartbeatJob?.isActive == true) return
        onLiveContextChanged(true)
        heartbeatJob = scope.launch {
            while (isActive && mutableState.value.session?.status == SessionStatus.ACTIVE && mutableState.value.session?.contextMode == ContextMode.LIVE) {
                try { heartbeatOnce() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { fail(error) }
                val delayMs = heartbeatDelay()
                publish(nextHeartbeatAt = System.currentTimeMillis() + delayMs)
                delay(delayMs)
            }
        }
    }

    private suspend fun heartbeatOnce() = operations.withLock {
        val session = requireActiveSession()
        val deviceHealth = health.snapshot()
        idleRuntime?.observeEnvironment(deviceHealth)
        val latest = database.loadLatestObservation(session.id)
        val staleWindow = FreshnessPolicy.heartbeatWindow(deviceHealth.motion, batteryConstrained(deviceHealth))
        val expectedLatency = latest?.timing?.totalMs ?: 4_000
        val refreshDueAt = latest?.observedAtMs?.plus(staleWindow)?.minus(expectedLatency.coerceAtMost(staleWindow))
        val shouldCapture = session.permissions.observe && session.permissions.captureImage == com.grinningfrog.atlas.model.PermissionPolicy.ACTIVE_SESSION &&
            (latest == null || refreshDueAt == null || System.currentTimeMillis() >= refreshDueAt)
        database.appendEvent(session.id, "heartbeat.tick", JSONObject().put("shouldCapture", shouldCapture).put("staleWindowMs", staleWindow)
            .put("refreshDueAtMs", refreshDueAt).put("battery", deviceHealth.batteryPercent).put("thermal", deviceHealth.thermalStatus).put("motion", deviceHealth.motion.name))
        if (!shouldCapture) { publish(deviceHealth = deviceHealth, phase = RuntimePhase.READY); return@withLock }

        val current = captureLocked(session, "heartbeat", MediaPurpose.HEARTBEAT)
        val delta = SceneDifference.score(latest?.sceneFingerprint, current.sceneFingerprint)
        val meaningful = latest == null || (delta != null && delta >= 0.18)
        database.appendEvent(session.id, "perception.significance", JSONObject().put("observationId", current.id).put("sceneDelta", delta).put("meaningful", meaningful))
        if (meaningful) {
            val now = System.currentTimeMillis()
            val recent = database.loadHeartbeatInferenceTimes(session.id, now - ProactiveInferencePolicy.WINDOW_MS)
            val assessment = ProactiveInferencePolicy.assess(now, recent)
            database.appendEvent(session.id, "heartbeat.inference_assessed", JSONObject().put("observationId", current.id)
                .put("allowed", assessment.allowed).put("reason", assessment.reason).put("attemptsInWindow", recent.size)
                .put("windowMs", ProactiveInferencePolicy.WINDOW_MS).put("cooldownMs", ProactiveInferencePolicy.COOLDOWN_MS))
            if (assessment.allowed) reviewHeartbeatLocked(session, current, delta)
        }
        publish(deviceHealth = deviceHealth, phase = RuntimePhase.READY)
    }

    private suspend fun reviewHeartbeatLocked(session: AtlasSession, observation: VisualObservation, delta: Double?) {
        val request = InferenceRequest(
            sessionId = session.id, workspaceId = session.workspaceId, taskRunId = session.taskRunId,
            capability = RouteCapability.FAST, systemPrompt = systemPrompt(session),
            userText = "In at most 60 words, summarize what is visibly present and the meaningful change. End with ACTION: NONE, or ACTION: followed by one immediately useful or safety-relevant message.",
            observation = observation, contextNote = "Deterministic scene delta=$delta",
            image = mediaRepository.inferenceImage(observation.media),
            risk = com.grinningfrog.atlas.model.InferenceRisk.ELEVATED,
            latencyClass = com.grinningfrog.atlas.model.LatencyClass.BACKGROUND,
            responseContract = com.grinningfrog.atlas.model.ResponseContract(
                mode = com.grinningfrog.atlas.model.ResponseMode.DEFAULT, targetWords = 60, hardMaxWords = 80,
                maxSentences = 4, maxOutputTokens = 200,
            ),
            provenance = InferenceProvenance(
                domain = InferenceDomain.PERCEPTION,
                purpose = InferencePurpose.HEARTBEAT_SCENE_REVIEW,
                triggerEventId = observation.id,
                userInitiated = false,
            ),
        )
        database.appendEvent(session.id, "provider.requested", JSONObject().put("source", "heartbeat").put("requestId", request.requestId)
            .put("capability", "FAST").put("risk", request.risk.name).put("latencyClass", request.latencyClass.name).putProvenance(request.provenance))
        try {
            val response = router.route(request)
            val interpretedAt = System.currentTimeMillis()
            database.updateObservationInterpretation(observation.id, response.text, interpretedAt)
            publish(observation = observation.copy(summary = response.text, interpretedAtMs = interpretedAt))
            database.appendEvent(session.id, "provider.responded", JSONObject().put("source", "heartbeat").put("requestId", request.requestId)
                .put("endpointId", response.endpointId).put("model", response.selectedModel).put("routingProfile", response.routingProfile)
                .put("routingReason", response.routingReason).put("routingRevision", response.routingRevision)
                .put("latencyMs", response.latencyMs).put("firstTokenLatencyMs", response.firstTokenLatencyMs)
                .put("promptTokens", response.promptTokens).put("completionTokens", response.completionTokens)
                .put("totalTokens", response.totalTokens).put("finishReason", response.finishReason)
                .put("providerContinuationId", response.providerContinuationId).put("text", response.text))
            val action = heartbeatAction(response.text)
            if (action != null && session.permissions.proactiveSpeech) {
                scope.launch { operations.withLock { speakLocked(session, action) } }
            } else database.appendEvent(session.id, "agent.speech_suppressed", JSONObject().put("source", "heartbeat").put("action", action))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            database.appendEvent(session.id, "provider.failed", JSONObject().put("source", "heartbeat").put("requestId", request.requestId)
                .put("error", error.safeMessage()).apply { (error as? com.grinningfrog.atlas.provider.InferenceUnavailableException)?.providerCode?.let { put("errorCode", it) } })
        }
    }

    private suspend fun captureLocked(session: AtlasSession, reason: String, purpose: MediaPurpose = MediaPurpose.STANDARD_VISION): VisualObservation {
        if (!session.permissions.observe || session.permissions.captureImage == com.grinningfrog.atlas.model.PermissionPolicy.NEVER) {
            throw SecurityException("Session policy does not allow camera observation")
        }
        publish(phase = RuntimePhase.CAPTURING)
        database.appendEvent(session.id, "tool.requested", JSONObject().put("tool", "capture_current_view").put("reason", reason))
        return try {
            val observation = camera.capture(session.id, reason, purpose)
            database.saveObservation(observation)
            database.appendEvent(session.id, "tool.completed", JSONObject().put("tool", "capture_current_view").put("observationId", observation.id)
                .put("mediaId", observation.media.id).put("purpose", observation.media.purpose.name).put("rawBytes", observation.media.rawByteSize)
                .put("finalBytes", observation.media.byteSize).put("width", observation.media.width).put("height", observation.media.height)
                .put("mediaProcessingMs", observation.media.processingMs))
            publish(observation = observation, error = null, phase = RuntimePhase.READY)
            observation
        } catch (error: Exception) {
            database.appendEvent(session.id, "tool.failed", JSONObject().put("tool", "capture_current_view").put("error", error.safeMessage()))
            val count = database.loadRecentEvents(session.id, 100).count { event ->
                event.type == "tool.failed" && runCatching { JSONObject(event.dataJson).optString("tool") == "capture_current_view" }.getOrDefault(false)
            }
            idleRuntime?.observeRepeatedFailure("Camera capture repeatedly failed", error.safeMessage(), count)
            throw error
        }
    }

    private suspend fun speakLocked(session: AtlasSession, text: String) {
        publish(phase = RuntimePhase.SPEAKING)
        database.appendEvent(session.id, "audio.speech_requested", JSONObject().put("text", text))
        runCatching { speech.speak(text) }.onSuccess {
            database.appendEvent(session.id, "audio.speech_completed")
        }.onFailure { error ->
            database.appendEvent(session.id, "audio.speech_failed", JSONObject().put("error", error.safeMessage()).put("textFallbackPreserved", true))
        }
        publish(phase = RuntimePhase.READY)
    }

    private fun heartbeatDelay(): Long {
        val snapshot = health.snapshot()
        return FreshnessPolicy.heartbeatWindow(snapshot.motion, batteryConstrained(snapshot)).coerceIn(5_000, 120_000)
    }

    private fun batteryConstrained(health: com.grinningfrog.atlas.model.DeviceHealth) =
        (health.batteryPercent != null && health.batteryPercent < 20 && !health.charging) || health.thermalStatus in setOf("hot", "throttled")

    private fun observationContext(observation: VisualObservation) = buildString {
        append("Observed ${System.currentTimeMillis() - observation.observedAtMs}ms ago; stability=${observation.stability}; motion=${observation.motionState}.")
        observation.summary?.let {
            append(" Background interpretation from ${observation.interpretedAtMs?.let { at -> System.currentTimeMillis() - at } ?: 0}ms ago: ")
            append(it)
        }
    }

    private fun heartbeatAction(text: String): String? {
        val marker = Regex("(?im)^ACTION:\\s*(.+)$").find(text)?.groupValues?.get(1)?.trim() ?: return null
        return marker.takeUnless { it.equals("NONE", ignoreCase = true) || it.equals("NO_ACTION", ignoreCase = true) }
    }

    private fun systemPrompt(session: AtlasSession) = """
        You are the replaceable reasoning backend inside Atlas, a physical-agent runtime.
        Atlas owns session truth, device permissions, freshness, tool execution and audit history.
        Goal: ${session.goal.ifBlank { "Help the user with their present physical context." }}
        Be concise for speech. Never claim an observation is current beyond the supplied timing metadata.
        Do not invent tool results or imply an external action occurred.
        When describing capabilities, name only tools actually supplied on this request. Do not invent
        parallel-call wrappers, orchestration helpers, browsers, or provider tools that were not declared.
    """.trimIndent()

    private fun requireSession() = mutableState.value.session ?: throw IllegalStateException("Create a session first")
    private fun requireActiveSession() = requireSession().also { if (it.status != SessionStatus.ACTIVE) throw IllegalStateException("Session is not active") }

    private fun fail(error: Throwable) {
        publish(error = error.safeMessage(), phase = RuntimePhase.ERROR)
    }

    /** Fast UI-only state update. Token and recognition deltas must not re-query every durable projection. */
    private fun publishTransient(
        phase: RuntimePhase = mutableState.value.phase,
        listeningState: ListeningState = mutableState.value.listeningState,
        partialTranscript: String? = mutableState.value.partialTranscript,
        streamingResponse: String? = mutableState.value.streamingResponse,
        response: String? = mutableState.value.latestResponse,
    ) {
        mutableState.value = mutableState.value.copy(
            phase = phase,
            listeningState = listeningState,
            partialTranscript = partialTranscript,
            streamingResponse = streamingResponse,
            latestResponse = response,
        )
    }

    private fun publish(
        session: AtlasSession? = mutableState.value.session,
        observation: VisualObservation? = mutableState.value.latestObservation,
        response: String? = mutableState.value.latestResponse,
        error: String? = mutableState.value.latestError,
        phase: RuntimePhase = mutableState.value.phase,
        nextHeartbeatAt: Long? = mutableState.value.nextHeartbeatAtMs,
        deviceHealth: com.grinningfrog.atlas.model.DeviceHealth = health.snapshot(),
        listeningState: ListeningState = mutableState.value.listeningState,
        partialTranscript: String? = mutableState.value.partialTranscript,
        streamingResponse: String? = mutableState.value.streamingResponse,
    ) {
        val durableSession = session?.let { database.loadSession(it.id) } ?: session
        val age = observation?.let { (System.currentTimeMillis() - it.observedAtMs).coerceAtLeast(0) }
        val events = durableSession?.let { database.loadRecentEvents(it.id, 80) }.orEmpty()
        val messages = durableSession?.let { database.loadMessages(it.id, limit = 80) }.orEmpty()
        val activeTurn = durableSession?.let { database.loadActiveTurn(it.id) }
        val pendingTools = durableSession?.let { database.loadPendingToolCalls(it.id) }.orEmpty()
        val memories = durableSession?.let { database.loadActiveMemories(it.id) }.orEmpty()
        val summary = durableSession?.let { database.loadSummary(it.id) }
        val pendingClarification = durableSession?.let { database.loadPendingClarification(it.id) }
        mutableState.value = RuntimeSnapshot(
            session = durableSession,
            phase = phase,
            latestObservation = observation,
            latestResponse = response,
            latestError = error,
            contextAgeMs = age,
            nextHeartbeatAtMs = nextHeartbeatAt,
            deviceHealth = deviceHealth,
            recentEvents = events,
            messages = messages,
            activeTurn = activeTurn,
            executionTasks = durableSession?.let { executionTasks.list(it.id) }.orEmpty(),
            turnDiagnostics = durableSession?.let { database.loadRecentTurns(it.id).map(database::turnDiagnostics) }.orEmpty(),
            pendingToolCalls = pendingTools,
            memories = memories,
            sessionSummary = summary,
            pendingClarification = pendingClarification,
            listeningState = listeningState,
            partialTranscript = partialTranscript,
            streamingResponse = streamingResponse,
        )
    }
}

private fun Throwable.safeMessage(): String = message?.take(500) ?: javaClass.simpleName

private fun JSONObject.putProvenance(provenance: InferenceProvenance): JSONObject = apply {
    put("inferenceDomain", provenance.domain.name)
    put("inferencePurpose", provenance.purpose.name)
    put("userInitiated", provenance.userInitiated)
    provenance.triggerEventId?.let { put("triggerEventId", it) }
    provenance.intentId?.let { put("intentId", it) }
    provenance.workAttemptId?.let { put("workAttemptId", it) }
    provenance.autonomyMode?.let { put("autonomyMode", it) }
}

object AgentLoopPolicy {
    const val MAX_STEPS = 8
    const val MAX_TOOL_CALLS = 12
    const val MAX_WALL_TIME_MS = 180_000L
    const val MAX_STORED_MESSAGES_FOR_CONTEXT = 96
    const val COMPACTION_THRESHOLD = 28
    const val MAX_COMPACTION_MESSAGES = 96
    const val RECENT_TURNS_AFTER_COMPACTION = 4
}

private enum class ClarificationControlOutcome { WAITING, DEFERRED, CONTINUE }

private class SoftTimeoutDetached : Exception()

private const val STREAM_CHECKPOINT_INTERVAL_MS = 250L
private const val MODEL_SOFT_TIMEOUT_MS = 60_000L

/** An authoring request keeps its lease and original result beyond the interactive notification deadline. */
internal suspend fun <T> awaitModelResponse(pending: kotlinx.coroutines.Deferred<T>, keepWorking: Boolean, onSlow: () -> Unit, check: () -> Unit): T? {
    val timely=kotlinx.coroutines.withTimeoutOrNull(60_000L) {pending.await()}
    if(timely!=null || !keepWorking)return timely
    onSlow()
    try {while(true) {
        check()
        val next=kotlinx.coroutines.withTimeoutOrNull(60_000L) {pending.await()}
        if(next!=null)return next
    }} catch(cancelled: CancellationException) {pending.cancel(cancelled);throw cancelled}
}
