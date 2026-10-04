package com.grinningfrog.atlas.provider

import android.util.Base64
import com.grinningfrog.atlas.model.InferenceMessage
import com.grinningfrog.atlas.model.InferenceRequest
import com.grinningfrog.atlas.model.InferenceResponse
import com.grinningfrog.atlas.model.InferenceStreamEvent
import com.grinningfrog.atlas.model.MessageRole
import com.grinningfrog.atlas.model.ProviderEndpoint
import com.grinningfrog.atlas.model.ToolCallProposal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
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

class ChatGptPlanBackend(
    private val auth: ChatGptTokenProvider,
    private val baseClient: OkHttpClient = OkHttpClient(),
    private val responsesUrl: String = "https://api.openai.com/v1/responses",
) : InferenceBackend {
    override suspend fun infer(endpoint: ProviderEndpoint, apiKey: String?, request: InferenceRequest): InferenceResponse {
        var completed: InferenceResponse? = null
        stream(endpoint, apiKey, request).collect { if (it is InferenceStreamEvent.Completed) completed = it.response }
        return completed ?: throw InferenceUnavailableException("ChatGPT stream ended without response.completed", outcomeAmbiguous = true)
    }

    override fun stream(endpoint: ProviderEndpoint, apiKey: String?, request: InferenceRequest): Flow<InferenceStreamEvent> = flow {
        val started = System.nanoTime()
        var refreshed = false
        while (true) {
            val token = auth.accessToken(forceRefresh = refreshed)
            val call = client(endpoint).newCall(httpRequest(endpoint, token, request))
            val invalidation = auth.onSessionInvalidated(call::cancel)
            val response = try { call.await() } catch (cancelled: CancellationException) {
                invalidation.close()
                throw cancelled
            } catch (error: IOException) {
                invalidation.close()
                val definitelyNotAccepted = error is ConnectException || error is UnknownHostException
                throw InferenceUnavailableException("ChatGPT request failed: ${error.message ?: error.javaClass.simpleName}", error, !definitelyNotAccepted)
            }
            if (response.code == 401 && !refreshed) {
                invalidation.close()
                response.close()
                refreshed = true
                continue
            }
            try { response.use { network ->
                if (!network.isSuccessful) {
                    val body = network.body?.string().orEmpty()
                    throw providerError(network.code, body, network.header("x-request-id") ?: network.header("openai-request-id"))
                }
                val source = network.body?.source() ?: throw InferenceUnavailableException("ChatGPT returned an empty stream", outcomeAmbiguous = true)
                val text = StringBuilder()
                val calls = linkedMapOf<Int, MutableCall>()
                var responseId: String? = null
                var model: String? = null
                var firstTokenMs: Long? = null
                var inputTokens: Int? = null
                var outputTokens: Int? = null
                var totalTokens: Int? = null
                var completed = false
                try {
                    while (true) {
                        val line = source.readUtf8Line() ?: break
                        if (!line.startsWith("data:")) continue
                        val raw = line.removePrefix("data:").trim()
                        if (raw.isBlank() || raw == "[DONE]") continue
                        val event = JSONObject(raw)
                        when (event.optString("type")) {
                            "response.created", "response.in_progress" -> event.optJSONObject("response")?.let { response ->
                                responseId = response.optString("id").takeIf(String::isNotBlank) ?: responseId
                                model = response.optString("model").takeIf(String::isNotBlank) ?: model
                            }
                            "response.output_text.delta" -> event.optString("delta").takeIf(String::isNotEmpty)?.let { delta ->
                                if (firstTokenMs == null) firstTokenMs = elapsedMs(started)
                                text.append(delta); emit(InferenceStreamEvent.TextDelta(delta))
                            }
                            "response.output_item.added" -> event.optJSONObject("item")?.takeIf { it.optString("type") == "function_call" }?.let { item ->
                                val index = event.optInt("output_index", calls.size)
                                val aggregate = calls.getOrPut(index) { MutableCall() }
                                aggregate.itemId = item.optString("id").takeIf(String::isNotBlank) ?: aggregate.itemId
                                aggregate.callId = item.optString("call_id").takeIf(String::isNotBlank) ?: aggregate.callId
                                aggregate.name = item.optString("name").takeIf(String::isNotBlank) ?: aggregate.name
                                item.optString("arguments").takeIf(String::isNotEmpty)?.let(aggregate.arguments::append)
                                emit(InferenceStreamEvent.ToolCallDelta(index, aggregate.callId, aggregate.name, ""))
                            }
                            "response.function_call_arguments.delta" -> {
                                val index = event.optInt("output_index", calls.size)
                                val aggregate = calls.getOrPut(index) { MutableCall(itemId = event.optString("item_id")) }
                                val delta = event.optString("delta")
                                aggregate.arguments.append(delta)
                                if (firstTokenMs == null) firstTokenMs = elapsedMs(started)
                                emit(InferenceStreamEvent.ToolCallDelta(index, aggregate.callId, aggregate.name, delta))
                            }
                            "response.output_item.done" -> event.optJSONObject("item")?.takeIf { it.optString("type") == "function_call" }?.let { item ->
                                val index = event.optInt("output_index", calls.size)
                                val aggregate = calls.getOrPut(index) { MutableCall() }
                                aggregate.itemId = item.optString("id").takeIf(String::isNotBlank) ?: aggregate.itemId
                                aggregate.callId = item.optString("call_id").takeIf(String::isNotBlank) ?: aggregate.callId
                                aggregate.name = item.optString("name").takeIf(String::isNotBlank) ?: aggregate.name
                                val finalArguments = item.optString("arguments")
                                if (finalArguments.isNotBlank()) { aggregate.arguments.clear(); aggregate.arguments.append(finalArguments) }
                            }
                            "response.completed" -> {
                                val finished = event.getJSONObject("response")
                                responseId = finished.optString("id").takeIf(String::isNotBlank) ?: responseId
                                model = finished.optString("model").takeIf(String::isNotBlank) ?: model
                                finished.optJSONObject("usage")?.let { usage ->
                                    inputTokens = usage.optInt("input_tokens").takeIf { usage.has("input_tokens") }
                                    outputTokens = usage.optInt("output_tokens").takeIf { usage.has("output_tokens") }
                                    totalTokens = usage.optInt("total_tokens").takeIf { usage.has("total_tokens") }
                                        ?: listOfNotNull(inputTokens, outputTokens).takeIf { it.size == 2 }?.sum()
                                }
                                completed = true
                            }
                            "response.failed" -> throw responseFailure(event.optJSONObject("response"))
                            "response.incomplete" -> throw InferenceUnavailableException("ChatGPT returned an incomplete response", outcomeAmbiguous = true)
                            "error" -> throw InferenceUnavailableException(safeProviderMessage(event), outcomeAmbiguous = text.isNotEmpty() || calls.isNotEmpty())
                        }
                    }
                } catch (cancelled: CancellationException) { call.cancel(); throw cancelled }
                catch (error: InferenceUnavailableException) { throw error }
                catch (error: Exception) { throw InferenceUnavailableException("ChatGPT returned a malformed or interrupted stream", error, outcomeAmbiguous = true) }
                if (!completed) throw InferenceUnavailableException("ChatGPT stream ended without response.completed", outcomeAmbiguous = true)
                val proposals = calls.toSortedMap().map { (_, call) -> ToolCallProposal(
                    id = call.callId ?: call.itemId ?: throw InferenceUnavailableException("ChatGPT tool call had no call ID", outcomeAmbiguous = true),
                    name = call.name ?: throw InferenceUnavailableException("ChatGPT tool call had no name", outcomeAmbiguous = true),
                    argumentsJson = call.arguments.toString().ifBlank { "{}" },
                ) }
                if (text.isBlank() && proposals.isEmpty()) throw InferenceUnavailableException("ChatGPT returned neither assistant text nor tool calls", outcomeAmbiguous = true)
                auth.onProviderSuccess()
                emit(InferenceStreamEvent.Completed(InferenceResponse(request.requestId, endpoint.id, text.toString().trim(), elapsedMs(started),
                    selectedModel = model ?: endpoint.model, toolCalls = proposals, finishReason = "completed", providerContinuationId = responseId,
                    firstTokenLatencyMs = firstTokenMs, promptTokens = inputTokens, completionTokens = outputTokens, totalTokens = totalTokens)))
            } } finally { invalidation.close() }
            return@flow
        }
    }.flowOn(Dispatchers.IO)

    internal fun requestJson(endpoint: ProviderEndpoint, request: InferenceRequest): JSONObject = JSONObject().apply {
        put("model", endpoint.model)
        put("store", false)
        put("stream", true)
        put("instructions", request.systemPrompt)
        put("input", input(request))
        if (request.tools.isNotEmpty()) put("tools", JSONArray().put(JSONObject().apply {
            put("type", "namespace")
            put("name", TOOL_NAMESPACE)
            put("description", "Atlas device tools. Atlas enforces permissions and executes approved calls locally.")
            put("tools", JSONArray(request.tools.map { tool -> JSONObject().apply {
                put("type", "function"); put("name", tool.name); put("description", tool.description)
                put("parameters", JSONObject(tool.parametersJson)); put("strict", false)
            } }))
        }))
    }

    private fun input(request: InferenceRequest): JSONArray {
        val source = request.messages.ifEmpty { listOf(InferenceMessage(MessageRole.USER, request.userText)) }
        val result = JSONArray()
        val attachToLastUser = request.image != null && source.lastOrNull()?.role == MessageRole.USER
        source.forEachIndexed { index, message ->
            if (message.role == MessageRole.ASSISTANT && message.toolCalls.isNotEmpty()) {
                if (message.content.isNotBlank()) result.put(messageItem("assistant", message.content, null))
                message.toolCalls.forEach { call -> result.put(JSONObject().put("type", "function_call").put("call_id", call.id)
                    .put("namespace", TOOL_NAMESPACE).put("name", call.name).put("arguments", call.argumentsJson)) }
            } else if (message.role == MessageRole.TOOL) {
                result.put(JSONObject().put("type", "function_call_output").put("call_id", requireNotNull(message.toolCallId)).put("output", message.content))
            } else result.put(messageItem(message.role.name.lowercase(), message.content, request.takeIf { attachToLastUser && index == source.lastIndex }))
        }
        if (request.image != null && !attachToLastUser) result.put(messageItem("user", "Atlas supplied this current observation.", request))
        return result
    }

    private fun messageItem(role: String, text: String, imageRequest: InferenceRequest?): JSONObject = JSONObject().apply {
        put("role", role)
        put("content", JSONArray().apply {
            put(JSONObject().put("type", if (role == "assistant") "output_text" else "input_text").put("text", buildString {
                append(text); imageRequest?.contextNote?.let { append("\n\nAtlas context: ").append(it) }
            }))
            imageRequest?.image?.let { image ->
                val encoded = Base64.encodeToString(image.bytes, Base64.NO_WRAP)
                put(JSONObject().put("type", "input_image").put("image_url", "data:${image.mimeType};base64,$encoded"))
            }
        })
    }

    private companion object { const val TOOL_NAMESPACE = "atlas" }

    private fun httpRequest(endpoint: ProviderEndpoint, token: String, request: InferenceRequest) = Request.Builder().url(responsesUrl)
        .header("Authorization", "Bearer $token").header("Content-Type", "application/json").header("Accept", "text/event-stream")
        .header("X-Atlas-Request-Id", request.requestId)
        .post(requestJson(endpoint, request).toString().toRequestBody("application/json".toMediaType())).build()

    private fun client(endpoint: ProviderEndpoint) = baseClient.newBuilder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(endpoint.timeoutMs, TimeUnit.MILLISECONDS).writeTimeout(30, TimeUnit.SECONDS).callTimeout(endpoint.timeoutMs, TimeUnit.MILLISECONDS).build()

    private fun providerError(status: Int, raw: String, requestId: String?): InferenceUnavailableException {
        val code = runCatching { JSONObject(raw).optJSONObject("error")?.optString("code")?.ifBlank { null } ?: JSONObject(raw).optString("detail") }.getOrNull()
        code?.takeIf(String::isNotBlank)?.let(auth::onProviderFailure)
        val message = when (code) {
            "subscription_sharing_user_not_eligible" -> "This ChatGPT account or workspace is not eligible for plan inference."
            "subscription_sharing_usage_limit_exceeded" -> "The ChatGPT plan usage limit was reached. Review usage in ChatGPT settings."
            "subscription_sharing_usage_unavailable" -> "ChatGPT plan usage is temporarily unavailable."
            "subscription_sharing_unsupported_capability" -> "The selected ChatGPT model does not support part of this request."
            "subscription_sharing_route_not_supported" -> "ChatGPT plan inference rejected this API route."
            "subscription_sharing_invalid_user", "chatpass_v2_scope_not_authorized", "chatpass_v2_invalid_authorization_context" -> "ChatGPT authorization is no longer valid. Sign in again."
            else -> "ChatGPT returned HTTP $status${code?.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()}"
        } + requestId?.let { " (request $it)" }.orEmpty()
        return InferenceUnavailableException(message, outcomeAmbiguous = status !in setOf(400, 401, 403, 429, 503), providerCode = code)
    }

    private fun responseFailure(response: JSONObject?): InferenceUnavailableException {
        val error = response?.optJSONObject("error")
        val code = error?.optString("code")
        return providerError(400, JSONObject().put("error", JSONObject().put("code", code)).toString(), null)
    }
    private fun safeProviderMessage(event: JSONObject) = "ChatGPT stream error: " + event.optString("code", "unknown_error").take(100)
    private fun elapsedMs(started: Long) = (System.nanoTime() - started) / 1_000_000
    private data class MutableCall(var itemId: String? = null, var callId: String? = null, var name: String? = null, val arguments: StringBuilder = StringBuilder())

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
}
