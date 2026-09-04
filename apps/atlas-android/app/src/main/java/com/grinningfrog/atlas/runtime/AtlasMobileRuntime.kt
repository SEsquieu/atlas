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
import com.grinningfrog.atlas.model.AtlasToolCall
import com.grinningfrog.atlas.model.ContextMode
import com.grinningfrog.atlas.model.ClarificationAmbiguity
import com.grinningfrog.atlas.model.ClarificationStatus
import com.grinningfrog.atlas.model.DeliveryStatus
import com.grinningfrog.atlas.model.InferenceRequest
import com.grinningfrog.atlas.model.InferenceResponse
import com.grinningfrog.atlas.model.InferenceStreamEvent
import com.grinningfrog.atlas.model.InferenceMessage
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
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
    private val onLiveContextChanged: (Boolean) -> Unit = {},
) {
    private val operations = Mutex()
    private val generation = AtomicLong(0)
    private val mutableState = MutableStateFlow(RuntimeSnapshot())
    val state: StateFlow<RuntimeSnapshot> = mutableState.asStateFlow()
    private var heartbeatJob: Job? = null
    private var compactionJob: Job? = null
    private var activeTurnJob: Job? = null
    private var activeTurnId: String? = null
    private var devicesStarted = false
    private val contextAssembler = ContextAssembler()
    private val toolHarness by lazy {
        ToolHarness(
            database = database,
            capture = { reason, purpose -> captureLocked(requireActiveSession(), reason, purpose) },
            deviceHealth = health::snapshot,
        )
    }

    suspend fun initialize() {
        val session = database.loadLatestSession()
        session?.let { database.recoverInterruptedRuntime(it.id) }
        session?.let { database.expirePendingClarification(it.id) }
        val observation = session?.let { database.loadLatestObservation(it.id) }
        val latestResponse = session?.let { database.loadMessages(it.id, limit = 80).lastOrNull { message -> message.role == MessageRole.ASSISTANT && message.content.isNotBlank() }?.content }
        if (session?.status == SessionStatus.ACTIVE) startDevices()
        publish(session = session, observation = observation, response = latestResponse, phase = if (session?.status == SessionStatus.ACTIVE) RuntimePhase.READY else RuntimePhase.STOPPED)
        val live = session?.let { it.status == SessionStatus.ACTIVE && it.contextMode == ContextMode.LIVE } == true
        onLiveContextChanged(live)
        if (live) startHeartbeat()
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

    suspend fun resumeSession() = operations.withLock {
        val session = requireSession()
        if (session.status == SessionStatus.DONE) throw IllegalStateException("Completed sessions cannot be resumed")
        publish(phase = RuntimePhase.STARTING)
        startDevices()
        database.updateSessionStatus(session.id, SessionStatus.ACTIVE)
        publish(session = session.copy(status = SessionStatus.ACTIVE), phase = RuntimePhase.READY)
        if (session.contextMode == ContextMode.LIVE) startHeartbeat() else onLiveContextChanged(false)
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

    suspend fun captureNow(reason: String = "user-request"): VisualObservation = operations.withLock {
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
        compactionJob?.cancel(); compactionJob = null
        val job = currentCoroutineContext()[Job]
        activeTurnJob = job
        try {
            operations.withLock { startUserTurnLocked(text.trim(), voice) }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                activeTurnId?.let { turnId -> runCatching { database.updateTurn(turnId, TurnStatus.CANCELLED, error = "Cancelled") } }
                publish(phase = if (mutableState.value.session?.status == SessionStatus.ACTIVE) RuntimePhase.READY else RuntimePhase.STOPPED)
            }
            throw cancelled
        } catch (error: Exception) {
            activeTurnId?.let { turnId -> runCatching { database.updateTurn(turnId, TurnStatus.FAILED, error = error.safeMessage()) } }
            mutableState.value.session?.let { session ->
                database.appendEvent(session.id, "runtime.turn_failed", JSONObject().put("turnId", activeTurnId).put("error", error.safeMessage()))
            }
            fail(error)
        } finally {
            if (activeTurnJob === job) activeTurnJob = null
            activeTurnId = null
        }
    }

    fun cancelActiveTurn(reason: String = "user cancelled") {
        generation.incrementAndGet()
        speech.stopSpeaking()
        (activeTurnId ?: mutableState.value.activeTurn?.id)?.let { turnId -> runCatching {
            database.markRunningToolsUnknown(turnId, "Execution interrupted: $reason")
            database.updateTurn(turnId, TurnStatus.CANCELLED, error = reason)
        } }
        activeTurnJob?.cancel(CancellationException(reason))
        publish()
    }

    suspend fun resolveToolCall(toolCallId: String, approved: Boolean) {
        val job = currentCoroutineContext()[Job]
        activeTurnJob = job
        try {
            operations.withLock {
                val session = requireActiveSession()
                val call = database.loadToolCall(toolCallId) ?: error("Tool proposal no longer exists")
                activeTurnId = call.turnId
                require(call.sessionId == session.id) { "Tool proposal belongs to another session" }
                require(call.status == ToolCallStatus.WAITING_FOR_CONFIRMATION) { "Tool proposal is not awaiting confirmation" }
                if (approved) {
                    database.updateToolCall(call.id, ToolCallStatus.APPROVED)
                    executeToolLocked(call.copy(status = ToolCallStatus.APPROVED))
                } else {
                    database.updateToolCall(call.id, ToolCallStatus.REJECTED, error = "User declined")
                    recordToolResult(call, JSONObject().put("ok", false).put("error", "User declined this tool call").toString())
                }
                val remaining = database.loadTurnToolCalls(call.turnId).filter { it.status == ToolCallStatus.WAITING_FOR_CONFIRMATION }
                if (remaining.isNotEmpty()) {
                    database.updateTurn(call.turnId, TurnStatus.WAITING_FOR_CONFIRMATION)
                    publish(phase = RuntimePhase.READY)
                    return@withLock
                }
                runTurnLoopLocked(session, call.turnId, database.loadLatestObservation(session.id), voice = false)
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { activeTurnId?.let { runCatching { database.updateTurn(it, TurnStatus.CANCELLED, error = "Cancelled") } } }
            throw cancelled
        } catch (error: Exception) {
            activeTurnId?.let { runCatching { database.updateTurn(it, TurnStatus.FAILED, error = error.safeMessage()) } }
            fail(error)
        } finally {
            if (activeTurnJob === job) activeTurnJob = null
            activeTurnId = null
            publish()
        }
    }

    fun forgetMemory(memoryId: String) {
        database.forgetMemory(memoryId)
        publish()
    }

    private suspend fun startUserTurnLocked(text: String, voice: Boolean) {
        val session = requireActiveSession()
        database.expirePendingClarification(session.id)
        val pending = database.loadPendingClarification(session.id)
        val turn = database.createTurn(session.id, if (voice) "voice" else "text")
        activeTurnId = turn.id
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
                    return
                }
            }
        }
        runTurnLoopLocked(session, turn.id, observation, voice)
    }

    private suspend fun runTurnLoopLocked(session: AtlasSession, turnId: String, startingObservation: VisualObservation?, voice: Boolean) {
        val startedAt = System.currentTimeMillis()
        var observation = startingObservation
        var step = database.loadTurn(turnId)?.stepCount ?: 0
        while (step < AgentLoopPolicy.MAX_STEPS && System.currentTimeMillis() - startedAt < AgentLoopPolicy.MAX_WALL_TIME_MS) {
            currentCoroutineContext().ensureActive()
            step += 1
            database.updateTurn(turnId, TurnStatus.ASSEMBLING_CONTEXT, stepCount = step)
            val summary = database.loadSummary(session.id)
            val messages = database.loadMessages(session.id, afterSequence = summary?.throughMessageSequence ?: 0, limit = AgentLoopPolicy.MAX_STORED_MESSAGES_FOR_CONTEXT)
            val memories = database.loadActiveMemories(session.id)
            val pendingClarification = database.loadPendingClarification(session.id)
            observation = usableLatestObservation(session.id) ?: observation
            val capability = capabilityForTurn(messages, observation)
            val risk = riskForTurn(messages)
            val latestToolCall = database.loadTurnToolCalls(turnId).lastOrNull()
            val attachImage = observation != null && (capability == RouteCapability.VISION ||
                (latestToolCall?.name == "capture_current_view" && latestToolCall.status == ToolCallStatus.COMPLETED))
            val toolsAvailable = router.hasToolCapableRoute(capability, requiresVision = attachImage)
            val context = contextAssembler.assemble(session, messages, memories, summary, observation, System.currentTimeMillis(), toolsAvailable, pendingClarification)
            val spoken = voice || session.permissions.speakResponses
            val latestUserText = messages.lastOrNull { it.role == MessageRole.USER }?.content.orEmpty()
            val responseContract = ResponsePolicy.contract(latestUserText, spoken, risk)
            val baseRequest = InferenceRequest(
                sessionId = session.id,
                workspaceId = session.workspaceId,
                taskRunId = session.taskRunId,
                turnId = turnId,
                step = step,
                capability = capability,
                systemPrompt = if (spoken) context.systemPrompt + "\n\n" + ResponsePolicy.instructions(responseContract) else context.systemPrompt,
                userText = latestUserText,
                messages = context.messages,
                observation = observation.takeIf { attachImage },
                image = observation?.takeIf { attachImage }?.let { mediaRepository.inferenceImage(it.media) },
                contextNote = observation?.let(::observationContext),
                risk = risk,
                responseContract = responseContract,
            )
            val request = baseRequest.copy(tools = if (toolsAvailable) toolHarness.definitions else emptyList())
            database.appendEvent(session.id, "context.assembled", JSONObject().apply {
                put("turnId", turnId); put("step", step); put("messageIds", org.json.JSONArray(context.includedMessageIds))
                put("omittedMessages", context.omittedMessageCount); put("approximateCharacters", context.approximateCharacters)
                put("memoryCount", memories.size); put("summaryThrough", summary?.throughMessageSequence); put("toolsAvailable", request.tools.size)
            })
            database.updateTurn(turnId, TurnStatus.WAITING_FOR_MODEL, stepCount = step)
            publish(phase = RuntimePhase.THINKING)
            database.appendEvent(session.id, "provider.requested", JSONObject().put("turnId", turnId).put("step", step).put("requestId", request.requestId)
                .put("capability", capability.name).put("risk", request.risk.name).put("latencyClass", request.latencyClass.name).put("toolCount", request.tools.size))
            val operationGeneration = generation.get()
            val response = try {
                streamModelStep(session, turnId, request, spoken)
            } catch (error: Exception) {
                database.appendEvent(session.id, "provider.failed", JSONObject().put("turnId", turnId).put("step", step).put("requestId", request.requestId).put("error", error.safeMessage()))
                throw error
            }
            if (generation.get() != operationGeneration || mutableState.value.session?.status != SessionStatus.ACTIVE) {
                database.appendEvent(session.id, "provider.result_discarded", JSONObject().put("turnId", turnId).put("requestId", request.requestId).put("reason", "session generation changed"))
                throw CancellationException("Session changed during inference")
            }
            database.appendEvent(session.id, "provider.responded", JSONObject().apply {
                put("turnId", turnId); put("step", step); put("requestId", request.requestId); put("endpointId", response.endpointId)
                put("model", response.selectedModel); put("routingProfile", response.routingProfile); put("routingReason", response.routingReason)
                put("routingRevision", response.routingRevision); put("latencyMs", response.latencyMs); put("degraded", response.degraded)
                put("firstTokenLatencyMs", response.firstTokenLatencyMs)
                put("finishReason", response.finishReason); put("providerContinuationId", response.providerContinuationId)
                put("text", response.text); put("toolCallCount", response.toolCalls.size)
            })
            require(response.toolCalls.map { it.id }.distinct().size == response.toolCalls.size) {
                "Provider returned duplicate tool-call ids in one response"
            }
            val clarificationCalls = response.toolCalls.filter { it.name == ToolHarness.CLARIFICATION_TOOL }
            require(clarificationCalls.size <= 1) { "Provider returned multiple clarification controls in one step" }
            if (clarificationCalls.isNotEmpty()) {
                require(response.toolCalls.size == 1) { "Clarification control cannot be mixed with physical tool proposals" }
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
            if (priorCalls.size + response.toolCalls.size > AgentLoopPolicy.MAX_TOOL_CALLS) {
                throw IllegalStateException("Agent exceeded the ${AgentLoopPolicy.MAX_TOOL_CALLS}-tool budget")
            }
            val calls = response.toolCalls.mapIndexed { index, proposal ->
                val earlierInBatch = response.toolCalls.take(index).count { it.name == proposal.name }
                prepareToolCall(session, turnId, step, index, proposal, priorCalls, earlierInBatch)
            }
            calls.forEach(database::insertToolCall)
            calls.forEach { call ->
                if (call.status == ToolCallStatus.REJECTED) {
                    recordToolResult(call, JSONObject().put("ok", false).put("error", call.error).toString())
                } else if (!call.requiresConfirmation) {
                    executeToolLocked(call)
                    database.loadLatestObservation(session.id)?.let { observation = it }
                }
            }
            if (calls.any { it.status == ToolCallStatus.WAITING_FOR_CONFIRMATION }) {
                database.updateTurn(turnId, TurnStatus.WAITING_FOR_CONFIRMATION, stepCount = step)
                publish(response = response.text.takeIf(String::isNotBlank), streamingResponse = null, phase = if (speech.isSpeaking) RuntimePhase.SPEAKING else RuntimePhase.READY)
                return
            }
        }
        throw IllegalStateException("Agent loop exceeded its step or wall-time budget")
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
        database.insertMessage(AtlasMessage(sessionId = sessionId, turnId = turnId, role = MessageRole.TOOL,
            content = JSONObject().put("ok", true).put("action", action).put("clarification_id", clarificationId).toString(),
            createdAtMs = System.currentTimeMillis(), kind = MessageKind.TOOL_RESULT, toolCallId = proposal.id))
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
            deliveryStatus = if (spoken) DeliveryStatus.PENDING else DeliveryStatus.TEXT_ONLY,
        ))
        val generated = StringBuilder()
        val segmenter = SentenceSegmenter()
        var sentenceIndex = 0
        var completed: InferenceResponse? = null
        var lastCheckpointAt = 0L
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
                        publishTransient(
                            response = generated.toString(),
                            streamingResponse = generated.toString(),
                            phase = if (speech.isSpeaking) RuntimePhase.SPEAKING else RuntimePhase.THINKING,
                        )
                        if (spoken) segmenter.append(event.text).forEach { sentence ->
                            enqueueSentence(session, turnId, messageId, sentenceIndex++, sentence)
                        }
                    }
                    is InferenceStreamEvent.ToolCallDelta -> Unit
                    is InferenceStreamEvent.Completed -> completed = event.response
                }
            }
            if (spoken) segmenter.finish().forEach { sentence ->
                enqueueSentence(session, turnId, messageId, sentenceIndex++, sentence)
            }
            val response = checkNotNull(completed) { "Provider stream ended without a completed response" }
            database.updateAssistantMessage(
                messageId,
                response.text,
                response.toolCalls.takeIf { it.isNotEmpty() }?.let(::toolCallsJson),
            )
            database.refreshMessageDelivery(messageId, when {
                !spoken || response.text.isBlank() -> DeliveryStatus.TEXT_ONLY
                sentenceIndex > 0 -> DeliveryStatus.PENDING
                else -> DeliveryStatus.FAILED
            })
            publish(response = response.text, streamingResponse = null)
            return response
        } catch (cancelled: CancellationException) {
            speech.stopSpeaking()
            database.updateAssistantMessage(messageId, generated.toString().trim())
            database.refreshMessageDelivery(messageId, DeliveryStatus.INTERRUPTED)
            database.appendEvent(session.id, "provider.stream_interrupted", JSONObject().put("turnId", turnId).put("requestId", request.requestId)
                .put("messageId", messageId).put("receivedCharacters", generated.length))
            throw cancelled
        } catch (error: Exception) {
            speech.stopSpeaking()
            database.updateAssistantMessage(messageId, generated.toString().trim())
            database.refreshMessageDelivery(messageId, DeliveryStatus.FAILED)
            throw error
        }
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
        val definition = toolHarness.definition(proposal.name)
        val sameToolCount = priorCalls.count { it.name == proposal.name } + earlierInBatch
        val policy = toolHarness.evaluate(session, proposal)
        val withinBudget = definition != null && sameToolCount < definition.maxCallsPerTurn
        val accepted = policy.allowed && withinBudget
        val timestamp = System.currentTimeMillis()
        return AtlasToolCall(
            id = proposal.id,
            sessionId = session.id,
            turnId = turnId,
            name = proposal.name,
            argumentsJson = proposal.argumentsJson,
            status = when {
                !accepted -> ToolCallStatus.REJECTED
                policy.requiresConfirmation -> ToolCallStatus.WAITING_FOR_CONFIRMATION
                else -> ToolCallStatus.PROPOSED
            },
            risk = policy.risk,
            requiresConfirmation = accepted && policy.requiresConfirmation,
            idempotencyKey = "$turnId:$step:$index:${proposal.name}",
            reason = proposal.reason ?: policy.reason,
            error = when {
                !policy.allowed -> policy.reason
                !withinBudget -> "Per-turn limit reached for ${proposal.name}"
                else -> null
            },
            createdAtMs = timestamp,
            updatedAtMs = timestamp,
        )
    }

    private suspend fun executeToolLocked(call: AtlasToolCall) {
        val current = database.loadToolCall(call.id) ?: call
        if (current.status == ToolCallStatus.COMPLETED || current.status == ToolCallStatus.REJECTED || current.status == ToolCallStatus.FAILED) return
        if (current.status == ToolCallStatus.UNKNOWN) error("Tool outcome is unknown and cannot be retried automatically")
        database.updateTurn(call.turnId, TurnStatus.EXECUTING_TOOL)
        database.updateToolCall(call.id, ToolCallStatus.RUNNING)
        try {
            val result = toolHarness.execute(call)
            database.updateToolCall(call.id, ToolCallStatus.COMPLETED, resultJson = result.resultJson)
            recordToolResult(call, result.resultJson)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val result = JSONObject().put("ok", false).put("error", error.safeMessage()).toString()
            database.updateToolCall(call.id, ToolCallStatus.FAILED, resultJson = result, error = error.safeMessage())
            recordToolResult(call, result)
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
        val transcript = candidates.joinToString("\n") { message ->
            "${message.role.name.lowercase()}: ${message.content.ifBlank { "[tool proposal]" }}"
        }.take(24_000)
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
        )
        database.appendEvent(session.id, "provider.requested", JSONObject().put("source", "memory_compaction").put("requestId", request.requestId))
        try {
            val response = router.route(request)
            val through = candidates.last().sequence
            database.saveSummary(SessionSummary(session.id, response.text.take(8_000), through, System.currentTimeMillis()))
            database.appendEvent(session.id, "provider.responded", JSONObject().put("source", "memory_compaction").put("requestId", request.requestId)
                .put("endpointId", response.endpointId).put("latencyMs", response.latencyMs).put("throughMessageSequence", through))
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
        val turnStatus = activeTurnId?.let(database::loadTurn)?.status
        val turnStillGenerating = activeTurnJob?.isActive == true && turnStatus !in setOf(
            TurnStatus.COMPLETED, TurnStatus.FAILED, TurnStatus.CANCELLED, TurnStatus.INTERRUPTED,
        )
        if (turnStillGenerating) cancelActiveTurn("user stopped speech") else speech.stopSpeaking()
        mutableState.value.session?.let { database.appendEvent(it.id, "audio.speech_interrupted", JSONObject().put("generationCancelled", turnStillGenerating)) }
        publish(phase = RuntimePhase.READY)
    }

    fun close() {
        heartbeatJob?.cancel()
        compactionJob?.cancel()
        onLiveContextChanged(false)
        stopDevices()
    }

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
        )
        database.appendEvent(session.id, "provider.requested", JSONObject().put("source", "heartbeat").put("requestId", request.requestId)
            .put("capability", "FAST").put("risk", request.risk.name).put("latencyClass", request.latencyClass.name))
        try {
            val response = router.route(request)
            val interpretedAt = System.currentTimeMillis()
            database.updateObservationInterpretation(observation.id, response.text, interpretedAt)
            publish(observation = observation.copy(summary = response.text, interpretedAtMs = interpretedAt))
            database.appendEvent(session.id, "provider.responded", JSONObject().put("source", "heartbeat").put("requestId", request.requestId)
                .put("endpointId", response.endpointId).put("model", response.selectedModel).put("routingProfile", response.routingProfile)
                .put("routingReason", response.routingReason).put("routingRevision", response.routingRevision)
                .put("latencyMs", response.latencyMs).put("text", response.text))
            val action = heartbeatAction(response.text)
            if (action != null && session.permissions.proactiveSpeech) {
                scope.launch { operations.withLock { speakLocked(session, action) } }
            } else database.appendEvent(session.id, "agent.speech_suppressed", JSONObject().put("source", "heartbeat").put("action", action))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            database.appendEvent(session.id, "provider.failed", JSONObject().put("source", "heartbeat").put("requestId", request.requestId).put("error", error.safeMessage()))
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
        val age = observation?.let { (System.currentTimeMillis() - it.observedAtMs).coerceAtLeast(0) }
        val events = session?.let { database.loadRecentEvents(it.id, 80) }.orEmpty()
        val messages = session?.let { database.loadMessages(it.id, limit = 80) }.orEmpty()
        val activeTurn = session?.let { database.loadActiveTurn(it.id) }
        val pendingTools = session?.let { database.loadPendingToolCalls(it.id) }.orEmpty()
        val memories = session?.let { database.loadActiveMemories(it.id) }.orEmpty()
        val summary = session?.let { database.loadSummary(it.id) }
        val pendingClarification = session?.let { database.loadPendingClarification(it.id) }
        mutableState.value = RuntimeSnapshot(
            session = session,
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

private const val STREAM_CHECKPOINT_INTERVAL_MS = 250L
