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
            val definitelyNotAccepted = error is ConnectException || error is UnknownHostException
            throw InferenceUnavailableException("${endpoint.name} request failed: ${error.message ?: error.javaClass.simpleName}", error, outcomeAmbiguous = !definitelyNotAccepted)
        }
        return networkResponse.use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val safeToTryAnotherRoute = response.code in setOf(401, 403, 404, 429)
                throw InferenceUnavailableException("${endpoint.name} returned HTTP ${response.code}: ${body.take(300)}", outcomeAmbiguous = !safeToTryAnotherRoute)
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
                val definitelyNotAccepted = error is ConnectException || error is UnknownHostException
                throw InferenceUnavailableException("${endpoint.name} request failed: ${error.message ?: error.javaClass.simpleName}", error, outcomeAmbiguous = !definitelyNotAccepted)
            }
            response.use { networkResponse ->
                if (!networkResponse.isSuccessful) {
                    val body = networkResponse.body?.string().orEmpty()
                    val safeToTryAnotherRoute = networkResponse.code in setOf(401, 403, 404, 429)
                    throw InferenceUnavailableException("${endpoint.name} returned HTTP ${networkResponse.code}: ${body.take(300)}", outcomeAmbiguous = !safeToTryAnotherRoute)
                }
                val body = networkResponse.body ?: throw InferenceUnavailableException("${endpoint.name} returned an empty stream", outcomeAmbiguous = true)
                val tools = linkedMapOf<Int, MutableToolCall>()
                val text = StringBuilder()
                var responseId: String? = null
                var responseModel: String? = null
                var finishReason: String? = null
                var firstTokenMs: Long? = null
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
                        val choices = root.optJSONArray("choices") ?: continue
                        if (choices.length() == 0) continue
                        val choice = choices.getJSONObject(0)
                        finishReason = choice.optString("finish_reason").takeIf(String::isNotBlank) ?: finishReason
                        val delta = choice.optJSONObject("delta") ?: continue
                        delta.optString("content").takeIf(String::isNotEmpty)?.let { chunk ->
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
                                val arguments = function?.optString("arguments").orEmpty()
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
                )))
            }
        }.flowOn(Dispatchers.IO)
    }

    private fun httpRequest(endpoint: ProviderEndpoint, apiKey: String?, request: InferenceRequest, streaming: Boolean): Request {
        val payload = JSONObject().apply {
            put("model", endpoint.model)
            put("stream", streaming)
            put("temperature", 0.2)
            put("messages", requestMessages(request))
            request.responseContract?.let { put("max_tokens", it.maxOutputTokens) }
            if (request.tools.isNotEmpty()) put("tools", JSONArray(request.tools.map(::toolJson)))
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
            .apply { request.observation?.media?.purpose?.let { header("X-Atlas-Media-Purpose", it.name.lowercase()) } }
            .apply { if (!apiKey.isNullOrBlank()) header("Authorization", "Bearer $apiKey") }
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
    }

    private fun client(endpoint: ProviderEndpoint) = baseClient.newBuilder()
        .connectTimeout(endpoint.timeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(endpoint.timeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(endpoint.timeoutMs, TimeUnit.MILLISECONDS)
        .callTimeout(endpoint.timeoutMs, TimeUnit.MILLISECONDS)
        .build()

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
        ParsedAssistant(text, toolCalls, choice.optString("finish_reason").ifBlank { null }, root.optString("id").ifBlank { null })
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
}
