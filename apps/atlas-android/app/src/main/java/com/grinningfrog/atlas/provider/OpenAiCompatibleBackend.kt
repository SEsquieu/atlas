package com.grinningfrog.atlas.provider

import android.util.Base64
import com.grinningfrog.atlas.model.InferenceRequest
import com.grinningfrog.atlas.model.InferenceResponse
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

class OpenAiCompatibleBackend(
    private val baseClient: OkHttpClient = OkHttpClient(),
) : InferenceBackend {
    override suspend fun infer(endpoint: ProviderEndpoint, apiKey: String?, request: InferenceRequest): InferenceResponse {
        val started = System.nanoTime()
        val payload = JSONObject().apply {
            put("model", endpoint.model)
            put("stream", false)
            put("temperature", 0.2)
            put("messages", requestMessages(request))
            if (request.tools.isNotEmpty()) put("tools", JSONArray(request.tools.map(::toolJson)))
        }
        val httpRequest = Request.Builder()
            .url(chatCompletionsUrl(endpoint.baseUrl))
            .header("Content-Type", "application/json")
            .header("X-Atlas-Request-Id", request.requestId)
            .header("X-Atlas-Capability", request.capability.name.lowercase())
            .header("X-Atlas-Risk", request.risk.name.lowercase())
            .header("X-Atlas-Latency-Class", request.latencyClass.name.lowercase())
            .header("X-Atlas-Requires-Tools", (request.tools.isNotEmpty()).toString())
            .apply { request.observation?.media?.purpose?.let { header("X-Atlas-Media-Purpose", it.name.lowercase()) } }
            .apply { if (!apiKey.isNullOrBlank()) header("Authorization", "Bearer $apiKey") }
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val client = baseClient.newBuilder()
            .connectTimeout(endpoint.timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(endpoint.timeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(endpoint.timeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(endpoint.timeoutMs, TimeUnit.MILLISECONDS)
            .build()

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
