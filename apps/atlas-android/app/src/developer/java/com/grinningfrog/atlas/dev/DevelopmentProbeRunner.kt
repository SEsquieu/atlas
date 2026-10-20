package com.grinningfrog.atlas.dev

import com.grinningfrog.atlas.AtlasApplication
import com.grinningfrog.atlas.model.*
import com.grinningfrog.atlas.provider.*
import com.grinningfrog.atlas.runtime.*
import com.grinningfrog.atlas.workspace.WorkspaceTools
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class ProbeOptions(
    val instructions: Boolean = false,
    val history: Boolean = false,
    val workspace: Boolean = false,
    val hostedSearch: Boolean = false,
    val tools: Set<String> = emptySet(),
    val prompt: String = "Reply exactly OK",
    val instructionOverride: String = "",
)
internal object ProbeRequestBuilder {
    fun build(sessionId: String, options: ProbeOptions, context: AssembledContext?, definitions: List<ToolDefinition>): InferenceRequest {
        require(options.prompt.isNotBlank() && options.prompt.length <= 4000) { "Prompt must contain 1–4000 characters" }
        require(options.instructionOverride.length <= 16000) { "Instruction override exceeds 16000 characters" }
        require(!options.history && !options.workspace || context != null) { "History/workspace requires an Atlas session in this DEV app" }
        val instructions = if (options.instructionOverride.isNotBlank()) options.instructionOverride else if (options.instructions || options.workspace) context?.systemPrompt.orEmpty() else ""
        val messages = if (options.history) context!!.messages + InferenceMessage(MessageRole.USER, options.prompt) else emptyList()
        return InferenceRequest(sessionId = sessionId, capability = RouteCapability.FAST, systemPrompt = instructions, userText = options.prompt,
            messages = messages, tools = definitions.filter { it.name in options.tools },
            provenance = InferenceProvenance(InferenceDomain.DIAGNOSTIC, InferencePurpose.CONNECTION_TEST, userInitiated = true))
    }
}
internal data class ProbeResult(val sessionId: String, val report: String)

/** No route fallback and no executor: isolate one explicit endpoint, never apply proposed tools. */
internal class DevelopmentProbeRunner(private val app: AtlasApplication, private val backendOverride: InferenceBackend? = null) {
    fun definitions(): List<ToolDefinition> = ToolHarness(app.database, { _, _ -> error("Diagnostic tools cannot execute") }, { DeviceHealth() },
        additionalTools = AndroidContextTools.create(app, app.database, app.settings) + WorkspaceTools.create(app.database, app.mediaRepository)).definitions

    suspend fun run(endpoint: ProviderEndpoint, options: ProbeOptions, sourceSessionId: String?): ProbeResult = withContext(Dispatchers.IO) {
        val db = app.database
        val source = sourceSessionId?.let(db::loadSession)
        require(!options.history && !options.workspace || source != null) { "Select/import an Atlas session into DEV before attaching history or workspace" }
        val context = ContextAssembler().assemble(source ?: AtlasSession(id = "diagnostic-context", name = "DEV", goal = "Inference request isolation", status = SessionStatus.IDLE, createdAtMs = 0, updatedAtMs = 0),
            if (options.history && source != null) db.loadMessages(source.id) else emptyList(),
            if (options.history && source != null) db.loadActiveMemories(source.id) else emptyList(),
            if (options.history && source != null) db.loadSummary(source.id) else null,
            null, System.currentTimeMillis(), toolsAvailable = options.tools.isNotEmpty(),
            workspaceContextJson = if (options.workspace && source != null) db.workspaceContextForSession(source.id) else null)
        val session = db.createSession("DEV inference probe", "Isolate provider request compatibility; model tools never execute")
        db.updateSessionStatus(session.id, SessionStatus.DONE)
        val request = ProbeRequestBuilder.build(session.id, options, context, definitions())
        val results = JSONArray()
        fun event(type: String, data: JSONObject) { db.appendEvent(session.id, type, data); results.put(JSONObject(data.toString()).put("event", type)) }
        event("dev.probe_started", JSONObject().put("request_id", request.requestId).put("endpoint_id", endpoint.id).put("model", endpoint.model)
            .put("instructions", options.instructions).put("instruction_override", options.instructionOverride.isNotBlank())
            .put("history", options.history).put("workspace", options.workspace).put("hosted_search", options.hostedSearch)
            .put("tool_names", JSONArray(request.tools.map { it.name })).put("tool_execution", "disabled"))
        val backend = backendOverride ?: ProviderBackendRegistry(OpenAiCompatibleBackend(), ChatGptPlanBackend(app.chatGptAuth, allowHostedSearch = options.hostedSearch, omitEmptyOptionalFields = true,
            diagnosticSink = { _, data -> event("provider.http_diagnostic", data) }))
        val started = System.currentTimeMillis()
        try {
            val result = withTimeout(125_000) { backend.infer(endpoint.copy(timeoutMs = 120_000), if (endpoint.kind == ProviderKind.CHATGPT_PLAN) null else app.settings.apiKey(endpoint), request) }
            event("dev.probe_completed", JSONObject().put("ok", true).put("elapsed_ms", System.currentTimeMillis() - started)
                .put("reply", result.text.take(2000)).put("proposed_tools", JSONArray(result.toolCalls.map { it.name })).put("tools_executed", 0)
                .put("input_tokens", result.promptTokens).put("output_tokens", result.completionTokens))
        } catch (cancelled: CancellationException) {
            event("dev.probe_cancelled", JSONObject().put("ok", false).put("elapsed_ms", System.currentTimeMillis() - started).put("tools_executed", 0))
            throw cancelled
        } catch (error: Exception) {
            event("dev.probe_failed", JSONObject().put("ok", false).put("elapsed_ms", System.currentTimeMillis() - started)
                .put("error", ChatGptOAuth.redact(error.message.orEmpty())).put("ambiguous", (error as? InferenceUnavailableException)?.outcomeAmbiguous)
                .put("tools_executed", 0))
        }
        ProbeResult(session.id, results.toString(2))
    }
}
