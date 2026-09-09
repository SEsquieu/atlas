package com.grinningfrog.atlas.provider

import android.util.Base64
import com.grinningfrog.atlas.model.InferenceRequest
import com.grinningfrog.atlas.model.InferenceResponse
import com.grinningfrog.atlas.model.InferenceStreamEvent
import com.grinningfrog.atlas.model.InferenceMessage
import com.grinningfrog.atlas.model.MessageRole
import com.grinningfrog.atlas.model.ProviderEndpoint
import com.grinningfrog.atlas.model.ToolCallProposal
import com.grinningfrog.atlas.model.ToolDefinition
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

class OpenAiCompatibleBackend(
    private val baseClient: OkHttpClient = OkHttpClient(),
) : InferenceBackend {
    override suspend fun infer(endpoint: ProviderEndpoint, apiKey: String?, request: InferenceRequest): InferenceResponse {
        val started = System.nanoTime()
        val httpRequest = httpRequest(endpoint, apiKey, request, streaming = false)
        val client = client(endpoint)

        val networkResponse = try {
            client.newCall(httpRequest).await()
        } catch (error: IOException) {
            if (error is SocketTimeoutException) {
                throw InferenceUnavailableException(
                    "${endpoint.name} reached its ${hardTimeoutMs(endpoint) / 1_000}s hard deadline.",
                    error,
                    outcomeAmbiguous = true,
                )
            }
            val definitelyNotAccepted = error is ConnectException || error is UnknownHostException
            throw InferenceUnavailableException("${endpoint.name} request failed: ${error.message ?: error.javaClass.simpleName}", error, outcomeAmbiguous = !definitelyNotAccepted)
        }
        return networkResponse.use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val safeToTryAnotherRoute = response.code in setOf(401, 403, 404, 429)
                throw InferenceUnavailableException(providerHttpError(endpoint, response.code, body), outcomeAmbiguous = !safeToTryAnotherRoute)
            }
            val parsed = parseAssistant(body)
            if (parsed.text.isBlank() && parsed.toolCalls.isEmpty()) throw InferenceUnavailableException("${endpoint.name} returned neither assistant text nor tool calls")
            InferenceResponse(
                requestId = request.requestId,
                endpointId = endpoint.id,
                text = parsed.text,
                latencyMs = elapsedMs(started),
                selectedModel = response.header("X-Atlas-Model"),
                routingProfile = response.header("X-Atlas-Profile"),
                routingReason = response.header("X-Atlas-Route-Reason"),
                routingRevision = response.header("X-Atlas-Route-Revision"),
                toolCalls = parsed.toolCalls,
                finishReason = parsed.finishReason,
                providerContinuationId = parsed.responseId,
                promptTokens = parsed.promptTokens,
                completionTokens = parsed.completionTokens,
                totalTokens = parsed.totalTokens,
            )
        }
    }

    override fun stream(endpoint: ProviderEndpoint, apiKey: String?, request: InferenceRequest): Flow<InferenceStreamEvent> {
        if (!endpoint.supportsStreaming) return super<InferenceBackend>.stream(endpoint, apiKey, request)
        return flow {
            val started = System.nanoTime()
            val response = try {
                client(endpoint).newCall(httpRequest(endpoint, apiKey, request, streaming = true)).await()
            } catch (error: IOException) {
                if (error is SocketTimeoutException) {
                    throw InferenceUnavailableException(
                        "${endpoint.name} reached its ${hardTimeoutMs(endpoint) / 1_000}s hard deadline.",
                        error,
                        outcomeAmbiguous = true,
                    )
                }
                val definitelyNotAccepted = error is ConnectException || error is UnknownHostException
                throw InferenceUnavailableException("${endpoint.name} request failed: ${error.message ?: error.javaClass.simpleName}", error, outcomeAmbiguous = !definitelyNotAccepted)
            }
            response.use { networkResponse ->
                if (!networkResponse.isSuccessful) {
                    val body = networkResponse.body?.string().orEmpty()
                    val safeToTryAnotherRoute = networkResponse.code in setOf(401, 403, 404, 429)
                    throw InferenceUnavailableException(providerHttpError(endpoint, networkResponse.code, body), outcomeAmbiguous = !safeToTryAnotherRoute)
                }
                val body = networkResponse.body ?: throw InferenceUnavailableException("${endpoint.name} returned an empty stream", outcomeAmbiguous = true)
                val tools = linkedMapOf<Int, MutableToolCall>()
                val text = StringBuilder()
                var responseId: String? = null
                var responseModel: String? = null
                var finishReason: String? = null
                var firstTokenMs: Long? = null
                var promptTokens: Int? = null
                var completionTokens: Int? = null
                var totalTokens: Int? = null
                try {
                    while (true) {
                        val line = body.source().readUtf8Line() ?: break
                        if (!line.startsWith("data:")) continue
                        val data = line.removePrefix("data:").trim()
                        if (data.isBlank()) continue
                        if (data == "[DONE]") break
                        val root = JSONObject(data)
                        root.optJSONObject("error")?.let { error -> throw IllegalStateException(error.optString("message", "Provider stream error")) }
                        responseId = root.optString("id").takeIf(String::isNotBlank) ?: responseId
                        responseModel = root.optString("model").takeIf(String::isNotBlank) ?: responseModel
                        root.optJSONObject("usage")?.let { usage ->
                            promptTokens = usage.optInt("prompt_tokens").takeIf { usage.has("prompt_tokens") }
                            completionTokens = usage.optInt("completion_tokens").takeIf { usage.has("completion_tokens") }
                            totalTokens = usage.optInt("total_tokens").takeIf { usage.has("total_tokens") }
                        }
                        val choices = root.optJSONArray("choices") ?: continue
                        if (choices.length() == 0) continue
                        val choice = choices.getJSONObject(0)
                        finishReason = choice.optString("finish_reason").takeIf(String::isNotBlank) ?: finishReason
                        val delta = choice.optJSONObject("delta") ?: continue
                        delta.opt("content").takeIf { it != null && it !== JSONObject.NULL }?.let { value ->
                            val chunk = value as? String ?: return@let
                            if (chunk.isEmpty()) return@let
                            if (firstTokenMs == null) firstTokenMs = elapsedMs(started)
                            text.append(chunk)
                            emit(InferenceStreamEvent.TextDelta(chunk))
                        }
                        delta.optJSONArray("tool_calls")?.let { calls ->
                            for (index in 0 until calls.length()) {
                                val part = calls.getJSONObject(index)
                                val toolIndex = part.optInt("index", index)
                                val aggregate = tools.getOrPut(toolIndex) { MutableToolCall() }
                                val id = part.optString("id").takeIf(String::isNotBlank)
                                val function = part.optJSONObject("function")
                                val name = function?.optString("name")?.takeIf(String::isNotBlank)
                                val arguments = function?.opt("arguments")
                                    .takeIf { it != null && it !== JSONObject.NULL } as? String ?: ""
                                if (id != null) aggregate.id = id
                                if (name != null) aggregate.name = name
                                aggregate.arguments.append(arguments)
                                if (firstTokenMs == null) firstTokenMs = elapsedMs(started)
                                emit(InferenceStreamEvent.ToolCallDelta(toolIndex, id, name, arguments))
                            }
                        }
                    }
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    throw InferenceUnavailableException("${endpoint.name} returned a malformed or interrupted stream", error, outcomeAmbiguous = true)
                }
                val proposals = tools.toSortedMap().map { (_, call) ->
                    ToolCallProposal(
                        id = call.id ?: throw InferenceUnavailableException("Streamed tool call had no id", outcomeAmbiguous = true),
                        name = call.name ?: throw InferenceUnavailableException("Streamed tool call had no name", outcomeAmbiguous = true),
                        argumentsJson = call.arguments.toString().ifBlank { "{}" },
                    )
                }
                if (text.isBlank() && proposals.isEmpty()) throw InferenceUnavailableException("${endpoint.name} returned neither assistant text nor tool calls", outcomeAmbiguous = true)
                emit(InferenceStreamEvent.Completed(InferenceResponse(
                    requestId = request.requestId,
                    endpointId = endpoint.id,
                    text = text.toString().trim(),
                    latencyMs = elapsedMs(started),
                    selectedModel = networkResponse.header("X-Atlas-Model") ?: responseModel,
                    routingProfile = networkResponse.header("X-Atlas-Profile"),
                    routingReason = networkResponse.header("X-Atlas-Route-Reason"),
                    routingRevision = networkResponse.header("X-Atlas-Route-Revision"),
                    toolCalls = proposals,
                    finishReason = finishReason,
                    providerContinuationId = responseId,
                    firstTokenLatencyMs = firstTokenMs,
                    promptTokens = promptTokens,
                    completionTokens = completionTokens,
                    totalTokens = totalTokens,
                )))
            }
        }.flowOn(Dispatchers.IO)
    }

    private fun providerHttpError(endpoint: ProviderEndpoint, code: Int, body: String): String {
        if (body.contains("image input is not supported", ignoreCase = true) &&
            body.contains("mmproj", ignoreCase = true)
        ) {
            return "${endpoint.name} rejected image input. Atlas sent the image correctly, but llama.cpp was started without a compatible multimodal projector. Restart llama-server with the matching --mmproj GGUF file."
        }
        return "${endpoint.name} returned HTTP $code: ${body.take(300)}"
    }

    private fun httpRequest(endpoint: ProviderEndpoint, apiKey: String?, request: InferenceRequest, streaming: Boolean): Request {
        val payload = JSONObject().apply {
            put("model", endpoint.model)
            put("stream", streaming)
            if (streaming) put("stream_options", JSONObject().put("include_usage", true))
            put("temperature", 0.2)
            put("messages", requestMessages(request))
            request.responseContract?.let { put("max_tokens", it.maxOutputTokens) }
            if (request.tools.isNotEmpty()) put("tools", JSONArray(request.tools.map(::toolJson)))
            if (isOnDevice(endpoint)) {
                // llama.cpp exposes Qwen's thinking tokens separately from assistant content.
                // Disable them so small local budgets are spent on the actual response.
                put("chat_template_kwargs", JSONObject().put("enable_thinking", endpoint.reasoningEnabled))
            }
        }
        return Request.Builder()
            .url(chatCompletionsUrl(endpoint.baseUrl))
            .header("Content-Type", "application/json")
            .header("Accept", if (streaming) "text/event-stream" else "application/json")
            .header("X-Atlas-Request-Id", request.requestId)
            .header("X-Atlas-Capability", request.capability.name.lowercase())
            .header("X-Atlas-Risk", request.risk.name.lowercase())
            .header("X-Atlas-Latency-Class", request.latencyClass.name.lowercase())
            .header("X-Atlas-Requires-Tools", (request.tools.isNotEmpty()).toString())
            .apply {
                request.observation?.media?.purpose?.let { header("X-Atlas-Media-Purpose", it.name.lowercase()) }
                request.workspaceId.takeIf(::isUuid)?.let { header("X-Atlas-Organization-Id", it) }
                request.sessionId.takeIf(::isUuid)?.let { header("X-Atlas-Session-Id", it) }
                request.taskRunId?.takeIf(::isUuid)?.let { header("X-Atlas-Task-Run-Id", it) }
            }
            .apply { if (!apiKey.isNullOrBlank()) header("Authorization", "Bearer $apiKey") }
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
    }

    private fun client(endpoint: ProviderEndpoint): OkHttpClient {
        val hardTimeoutMs = hardTimeoutMs(endpoint)
        return baseClient.newBuilder()
        .connectTimeout(minOf(endpoint.timeoutMs, 15_000), TimeUnit.MILLISECONDS)
        .readTimeout(hardTimeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(minOf(endpoint.timeoutMs, 30_000), TimeUnit.MILLISECONDS)
        .callTimeout(hardTimeoutMs, TimeUnit.MILLISECONDS)
        .build()
    }

    private fun hardTimeoutMs(endpoint: ProviderEndpoint) =
        if (isOnDevice(endpoint)) LOCAL_DIAGNOSTIC_HARD_TIMEOUT_MS else endpoint.timeoutMs

    private data class MutableToolCall(var id: String? = null, var name: String? = null, val arguments: StringBuilder = StringBuilder())

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resume(response) else response.close()
            }
        })
    }

    private fun requestMessages(request: InferenceRequest): JSONArray {
        val source = request.messages.ifEmpty { listOf(InferenceMessage(MessageRole.USER, request.userText)) }
        val result = JSONArray().put(JSONObject().put("role", "system").put("content", request.systemPrompt))
        val attachToLastUser = request.image != null && source.lastOrNull()?.role == MessageRole.USER
        source.forEachIndexed { index, message ->
            val attachImage = attachToLastUser && index == source.lastIndex
            result.put(messageJson(message, request.takeIf { attachImage }))
        }
        if (request.image != null && !attachToLastUser) {
            result.put(JSONObject().put("role", "user").put("content", imageContent(request, "Atlas tool supplied this current observation.")))
        }
        return result
    }

    private fun messageJson(message: InferenceMessage, imageRequest: InferenceRequest?): JSONObject = JSONObject().apply {
        put("role", message.role.name.lowercase())
        when (message.role) {
            MessageRole.TOOL -> {
                put("content", message.content); put("tool_call_id", requireNotNull(message.toolCallId))
            }
            MessageRole.ASSISTANT -> {
                put("content", message.content.ifBlank { JSONObject.NULL })
                if (message.toolCalls.isNotEmpty()) put("tool_calls", JSONArray(message.toolCalls.map { proposal ->
                    JSONObject().put("id", proposal.id).put("type", "function").put("function", JSONObject().put("name", proposal.name).put("arguments", proposal.argumentsJson))
                }))
            }
            MessageRole.USER -> put("content", imageRequest?.let { imageContent(it, message.content) } ?: message.content)
        }
    }

    private fun imageContent(request: InferenceRequest, text: String): JSONArray {
        val image = request.image
            ?: return JSONArray().put(JSONObject().put("type", "text").put("text", text))
        val encoded = Base64.encodeToString(image.bytes, Base64.NO_WRAP)
        return JSONArray().apply {
            put(JSONObject().put("type", "text").put("text", buildString {
                append(text)
                request.contextNote?.let { append("\n\nAtlas context: ").append(it) }
            }))
            put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:${image.mimeType};base64,$encoded")))
        }
    }

    private fun toolJson(tool: ToolDefinition) = JSONObject().put("type", "function").put("function", JSONObject().apply {
        put("name", tool.name); put("description", tool.description); put("parameters", JSONObject(tool.parametersJson))
    })

    private data class ParsedAssistant(
        val text: String,
        val toolCalls: List<ToolCallProposal>,
        val finishReason: String?,
        val responseId: String?,
        val promptTokens: Int?,
        val completionTokens: Int?,
        val totalTokens: Int?,
    )

    private fun parseAssistant(raw: String): ParsedAssistant = runCatching {
        val root = JSONObject(raw)
        val choice = root.getJSONArray("choices").getJSONObject(0)
        val message = choice.getJSONObject("message")
        val content = message.opt("content")
        val text = when (content) {
            is String -> content.trim().ifBlank { null }
            is JSONArray -> buildList {
                for (index in 0 until content.length()) {
                    val item = content.optJSONObject(index)
                    item?.optString("text")?.takeIf(String::isNotBlank)?.let(::add)
                }
            }.joinToString("\n").trim().ifBlank { null }
            else -> null
        }.orEmpty()
        val toolCalls = message.optJSONArray("tool_calls")?.let { array -> buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val function = item.getJSONObject("function")
                add(ToolCallProposal(item.getString("id"), function.getString("name"), function.optString("arguments", "{}")))
            }
        } }.orEmpty()
        val usage = root.optJSONObject("usage")
        ParsedAssistant(
            text, toolCalls, choice.optString("finish_reason").ifBlank { null }, root.optString("id").ifBlank { null },
            usage?.optInt("prompt_tokens")?.takeIf { usage.has("prompt_tokens") },
            usage?.optInt("completion_tokens")?.takeIf { usage.has("completion_tokens") },
            usage?.optInt("total_tokens")?.takeIf { usage.has("total_tokens") },
        )
    }.getOrElse { throw InferenceUnavailableException("Provider returned malformed assistant output", it, outcomeAmbiguous = true) }

    private fun chatCompletionsUrl(baseUrl: String): String {
        val normalized = baseUrl.trim().trimEnd('/')
        return when {
            normalized.endsWith("/chat/completions") -> normalized
            normalized.endsWith("/v1") -> "$normalized/chat/completions"
            else -> "$normalized/v1/chat/completions"
        }
    }

    private fun elapsedMs(startedNanos: Long) = (System.nanoTime() - startedNanos) / 1_000_000

    private fun isUuid(value: String) = UUID_PATTERN.matches(value)

    private fun isOnDevice(endpoint: ProviderEndpoint) =
        runCatching { EndpointSecurity.assess(endpoint.baseUrl).location == EndpointLocation.DEVICE }.getOrDefault(false)

    private companion object {
        val UUID_PATTERN = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")
    }
}

private const val LOCAL_DIAGNOSTIC_HARD_TIMEOUT_MS = 300_000L
