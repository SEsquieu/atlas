package com.grinningfrog.atlas.runtime

import com.grinningfrog.atlas.data.AtlasDatabase
import com.grinningfrog.atlas.device.CameraController
import com.grinningfrog.atlas.device.DeviceHealthMonitor
import com.grinningfrog.atlas.device.MotionMonitor
import com.grinningfrog.atlas.device.SpeechController
import com.grinningfrog.atlas.model.AtlasSession
import com.grinningfrog.atlas.model.InferenceRequest
import com.grinningfrog.atlas.model.RouteCapability
import com.grinningfrog.atlas.model.RuntimePhase
import com.grinningfrog.atlas.model.RuntimeSnapshot
import com.grinningfrog.atlas.model.SessionStatus
import com.grinningfrog.atlas.model.VisualObservation
import com.grinningfrog.atlas.provider.CapabilityRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

class AtlasMobileRuntime(
    private val database: AtlasDatabase,
    private val camera: CameraController,
    private val speech: SpeechController,
    private val motion: MotionMonitor,
    private val health: DeviceHealthMonitor,
    private val router: CapabilityRouter,
    private val scope: CoroutineScope,
) {
    private val operations = Mutex()
    private val generation = AtomicLong(0)
    private val mutableState = MutableStateFlow(RuntimeSnapshot())
    val state: StateFlow<RuntimeSnapshot> = mutableState.asStateFlow()
    private var heartbeatJob: Job? = null

    suspend fun initialize() {
        motion.start()
        camera.start()
        speech.start()
        val session = database.loadLatestSession()
        val observation = session?.let { database.loadLatestObservation(it.id) }
        publish(session = session, observation = observation, phase = if (session?.status == SessionStatus.ACTIVE) RuntimePhase.READY else RuntimePhase.STOPPED)
        if (session?.status == SessionStatus.ACTIVE) startHeartbeat()
    }

    suspend fun createAndStartSession(name: String, goal: String): AtlasSession = operations.withLock {
        val session = database.createSession(name.ifBlank { "Atlas session" }, goal)
        database.updateSessionStatus(session.id, SessionStatus.ACTIVE)
        val active = session.copy(status = SessionStatus.ACTIVE, updatedAtMs = System.currentTimeMillis())
        publish(session = active, observation = null, phase = RuntimePhase.READY)
        startHeartbeat()
        active
    }

    suspend fun resumeSession() = operations.withLock {
        val session = requireSession()
        if (session.status == SessionStatus.DONE) throw IllegalStateException("Completed sessions cannot be resumed")
        database.updateSessionStatus(session.id, SessionStatus.ACTIVE)
        publish(session = session.copy(status = SessionStatus.ACTIVE), phase = RuntimePhase.READY)
        startHeartbeat()
    }

    suspend fun pauseSession() = operations.withLock {
        val session = requireSession()
        generation.incrementAndGet()
        heartbeatJob?.cancel(); heartbeatJob = null
        speech.stopSpeaking()
        database.updateSessionStatus(session.id, SessionStatus.PAUSED)
        publish(session = session.copy(status = SessionStatus.PAUSED), phase = RuntimePhase.STOPPED)
    }

    suspend fun endSession() = operations.withLock {
        val session = requireSession()
        generation.incrementAndGet()
        heartbeatJob?.cancel(); heartbeatJob = null
        speech.stopSpeaking()
        database.updateSessionStatus(session.id, SessionStatus.DONE)
        publish(session = session.copy(status = SessionStatus.DONE), phase = RuntimePhase.STOPPED)
    }

    suspend fun captureNow(reason: String = "user-request"): VisualObservation = operations.withLock {
        val session = requireActiveSession()
        captureLocked(session, reason)
    }

    suspend fun listenAndAsk() {
        val session = requireActiveSession()
        publish(phase = RuntimePhase.LISTENING)
        database.appendEvent(session.id, "audio.transcription.requested")
        try {
            val text = speech.listenOnce()
            database.appendEvent(session.id, "audio.transcription.completed", JSONObject().put("text", text))
            ask(text, voice = true)
        } catch (error: Exception) {
            database.appendEvent(session.id, "audio.transcription.failed", JSONObject().put("error", error.safeMessage()))
            fail(error)
        }
    }

    suspend fun ask(text: String, voice: Boolean = false) = operations.withLock {
        val session = requireActiveSession()
        val turnId = UUID.randomUUID().toString()
        val operationGeneration = generation.get()
        database.appendEvent(session.id, "user.utterance", JSONObject().put("turnId", turnId).put("mode", if (voice) "voice" else "text").put("text", text))

        var observation = database.loadLatestObservation(session.id)
        val freshness = FreshnessPolicy.assess(text, observation, System.currentTimeMillis())
        database.appendEvent(session.id, "context.assessed", JSONObject().put("turnId", turnId).put("decision", freshness.decision.name).put("reason", freshness.reason)
            .put("ageMs", freshness.ageMs).put("staleAfterMs", freshness.staleAfterMs))
        if (freshness.decision == FreshnessDecision.REFRESH_REQUIRED) {
            try { observation = captureLocked(session, "user-preflight") }
            catch (error: Exception) {
                database.appendEvent(session.id, "visual.refresh_failed", JSONObject().put("turnId", turnId).put("error", error.safeMessage()))
                if (freshness.useCase == VisualUseCase.HIGH_RISK || freshness.useCase == VisualUseCase.NAVIGATION || observation == null) {
                    val refusal = "I couldn't establish fresh visual context, so I can't safely answer that as a current physical-world question."
                    publish(response = refusal, error = error.safeMessage(), phase = RuntimePhase.DEGRADED)
                    if (voice) speakLocked(session, refusal)
                    return@withLock
                }
            }
        }

        publish(phase = RuntimePhase.THINKING)
        val capability = if (freshness.useCase != VisualUseCase.NONE) RouteCapability.VISION else RouteCapability.REASONING
        val request = InferenceRequest(
            sessionId = session.id, capability = capability,
            systemPrompt = systemPrompt(session), userText = text,
            observation = if (capability == RouteCapability.VISION) observation else null,
            contextNote = observation?.let { "Observed ${System.currentTimeMillis() - it.observedAtMs}ms ago; stability=${it.stability}; motion=${it.motionState}." },
        )
        database.appendEvent(session.id, "provider.requested", JSONObject().put("turnId", turnId).put("requestId", request.requestId).put("capability", capability.name))
        try {
            val response = router.route(request)
            if (generation.get() != operationGeneration || mutableState.value.session?.status != SessionStatus.ACTIVE) {
                database.appendEvent(session.id, "provider.result_discarded", JSONObject().put("requestId", request.requestId).put("reason", "session generation changed"))
                return@withLock
            }
            database.appendEvent(session.id, "provider.responded", JSONObject().put("turnId", turnId).put("requestId", request.requestId)
                .put("endpointId", response.endpointId).put("latencyMs", response.latencyMs).put("degraded", response.degraded).put("text", response.text))
            publish(response = response.text, error = null, phase = RuntimePhase.READY)
            if (voice || session.permissions.speakResponses) speakLocked(session, response.text)
        } catch (error: Exception) {
            database.appendEvent(session.id, "provider.failed", JSONObject().put("turnId", turnId).put("requestId", request.requestId).put("error", error.safeMessage()))
            fail(error)
        }
    }

    fun stopSpeaking() {
        speech.stopSpeaking()
        mutableState.value.session?.let { database.appendEvent(it.id, "audio.speech_interrupted") }
        publish(phase = RuntimePhase.READY)
    }

    fun close() {
        heartbeatJob?.cancel()
        camera.stop(); motion.stop(); speech.close()
    }

    private fun startHeartbeat() {
        if (heartbeatJob?.isActive == true) return
        heartbeatJob = scope.launch {
            while (isActive && mutableState.value.session?.status == SessionStatus.ACTIVE) {
                val delayMs = heartbeatDelay()
                publish(nextHeartbeatAt = System.currentTimeMillis() + delayMs)
                delay(delayMs)
                runCatching { heartbeatOnce() }.onFailure { fail(it) }
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

        val current = captureLocked(session, "heartbeat")
        val delta = SceneDifference.score(latest?.sceneFingerprint, current.sceneFingerprint)
        val meaningful = delta != null && delta >= 0.18
        database.appendEvent(session.id, "perception.significance", JSONObject().put("observationId", current.id).put("sceneDelta", delta).put("meaningful", meaningful))
        if (meaningful) reviewHeartbeatLocked(session, current, delta)
        publish(deviceHealth = deviceHealth, phase = RuntimePhase.READY)
    }

    private suspend fun reviewHeartbeatLocked(session: AtlasSession, observation: VisualObservation, delta: Double) {
        val request = InferenceRequest(
            sessionId = session.id, capability = RouteCapability.FAST, systemPrompt = systemPrompt(session),
            userText = "Briefly assess this changed scene. Mention only an immediately useful or safety-relevant change; otherwise reply NO_ACTION.",
            observation = observation, contextNote = "Deterministic scene delta=$delta",
        )
        database.appendEvent(session.id, "provider.requested", JSONObject().put("source", "heartbeat").put("requestId", request.requestId).put("capability", "FAST"))
        runCatching { router.route(request) }.onSuccess { response ->
            database.appendEvent(session.id, "provider.responded", JSONObject().put("source", "heartbeat").put("requestId", request.requestId)
                .put("endpointId", response.endpointId).put("latencyMs", response.latencyMs).put("text", response.text))
            if (!response.text.equals("NO_ACTION", ignoreCase = true) && session.permissions.proactiveSpeech) {
                scope.launch { operations.withLock { speakLocked(session, response.text) } }
            } else database.appendEvent(session.id, "agent.speech_suppressed", JSONObject().put("source", "heartbeat").put("text", response.text))
        }.onFailure { error ->
            database.appendEvent(session.id, "provider.failed", JSONObject().put("source", "heartbeat").put("requestId", request.requestId).put("error", error.safeMessage()))
        }
    }

    private suspend fun captureLocked(session: AtlasSession, reason: String): VisualObservation {
        if (!session.permissions.observe || session.permissions.captureImage == com.grinningfrog.atlas.model.PermissionPolicy.NEVER) {
            throw SecurityException("Session policy does not allow camera observation")
        }
        publish(phase = RuntimePhase.CAPTURING)
        database.appendEvent(session.id, "tool.requested", JSONObject().put("tool", "capture_current_view").put("reason", reason))
        return try {
            val observation = camera.capture(session.id, reason)
            database.saveObservation(observation)
            database.appendEvent(session.id, "tool.completed", JSONObject().put("tool", "capture_current_view").put("observationId", observation.id))
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

    private fun publish(
        session: AtlasSession? = mutableState.value.session,
        observation: VisualObservation? = mutableState.value.latestObservation,
        response: String? = mutableState.value.latestResponse,
        error: String? = mutableState.value.latestError,
        phase: RuntimePhase = mutableState.value.phase,
        nextHeartbeatAt: Long? = mutableState.value.nextHeartbeatAtMs,
        deviceHealth: com.grinningfrog.atlas.model.DeviceHealth = health.snapshot(),
    ) {
        val age = observation?.let { (System.currentTimeMillis() - it.observedAtMs).coerceAtLeast(0) }
        val events = session?.let { database.loadRecentEvents(it.id, 80) }.orEmpty()
        mutableState.value = RuntimeSnapshot(session, phase, observation, response, error, age, nextHeartbeatAt, deviceHealth, events)
    }
}

private fun Throwable.safeMessage(): String = message?.take(500) ?: javaClass.simpleName
