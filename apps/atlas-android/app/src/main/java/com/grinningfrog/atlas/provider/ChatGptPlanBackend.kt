package com.grinningfrog.atlas.provider

import android.util.Base64
import com.grinningfrog.atlas.model.InferenceMessage
import com.grinningfrog.atlas.model.InferenceRequest
import com.grinningfrog.atlas.model.InferenceResponse
import com.grinningfrog.atlas.model.InferenceStreamEvent
import com.grinningfrog.atlas.model.MessageRole
import com.grinningfrog.atlas.model.ProviderEndpoint
import com.grinningfrog.atlas.model.ProviderCitation
import com.grinningfrog.atlas.model.ProviderToolUse
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
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

class ChatGptPlanBackend(
    private val auth: ChatGptTokenProvider,
    private val baseClient: OkHttpClient = OkHttpClient(),
    private val responsesUrl: String = "https://api.openai.com/v1/responses",
) : InferenceBackend {
    private val hostedSearchUnsupportedModels = ConcurrentHashMap.newKeySet<String>()

    override suspend fun infer(endpoint: ProviderEndpoint, apiKey: String?, request: InferenceRequest): InferenceResponse {
        var completed: InferenceResponse? = null
        stream(endpoint, apiKey, request).collect { if (it is InferenceStreamEvent.Completed) completed = it.response }
        return completed ?: throw InferenceUnavailableException("ChatGPT stream ended without response.completed", outcomeAmbiguous = true)
    }

    override fun stream(endpoint: ProviderEndpoint, apiKey: String?, request: InferenceRequest): Flow<InferenceStreamEvent> = flow {
        val started = System.nanoTime()
        var refreshed = false
        var hostedSearch = endpoint.model !in hostedSearchUnsupportedModels
        var hostedSearchUnavailable = false
        while (true) {
            val token = auth.accessToken(forceRefresh = refreshed)
            val call = client(endpoint).newCall(httpRequest(endpoint, token, request, hostedSearch))
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
            if (!response.isSuccessful) {
                val body = response.body?.string().orEmpty()
                val error = providerError(response.code, body, response.header("x-request-id") ?: response.header("openai-request-id"))
                invalidation.close()
                response.close()
                if (hostedSearch && error.providerCode == "subscription_sharing_unsupported_capability") {
                    hostedSearchUnsupportedModels += endpoint.model
                    hostedSearch = false
                    hostedSearchUnavailable = true
                    continue
                }
                throw error
            }
            try { response.use { network ->
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
                val citations = linkedMapOf<String, ProviderCitation>()
                val providerToolUses = mutableListOf<ProviderToolUse>()
                if (hostedSearchUnavailable) providerToolUses += ProviderToolUse("web_search", status = "unsupported")
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
                            "response.output_item.added" -> event.optJSONObject("item")?.let { item ->
                                when (item.optString("type")) {
                                    "function_call" -> {
                                        val index = event.optInt("output_index", calls.size)
                                        val aggregate = calls.getOrPut(index) { MutableCall() }
                                        aggregate.itemId = item.optString("id").takeIf(String::isNotBlank) ?: aggregate.itemId
                                        aggregate.callId = item.optString("call_id").takeIf(String::isNotBlank) ?: aggregate.callId
                                        aggregate.name = item.optString("name").takeIf(String::isNotBlank) ?: aggregate.name
                                        item.optString("arguments").takeIf(String::isNotEmpty)?.let(aggregate.arguments::append)
                                        emit(InferenceStreamEvent.ToolCallDelta(index, aggregate.callId, aggregate.name, ""))
                                    }
                                    // Hosted calls are recorded once their final action/status is available.
                                }
                            }
                            "response.function_call_arguments.delta" -> {
                                val index = event.optInt("output_index", calls.size)
                                val aggregate = calls.getOrPut(index) { MutableCall(itemId = event.optString("item_id")) }
                                val delta = event.optString("delta")
                                aggregate.arguments.append(delta)
                                if (firstTokenMs == null) firstTokenMs = elapsedMs(started)
                                emit(InferenceStreamEvent.ToolCallDelta(index, aggregate.callId, aggregate.name, delta))
                            }
                            "response.output_item.done" -> event.optJSONObject("item")?.let { item ->
                                when (item.optString("type")) {
                                    "function_call" -> {
                                        val index = event.optInt("output_index", calls.size)
                                        val aggregate = calls.getOrPut(index) { MutableCall() }
                                        aggregate.itemId = item.optString("id").takeIf(String::isNotBlank) ?: aggregate.itemId
                                        aggregate.callId = item.optString("call_id").takeIf(String::isNotBlank) ?: aggregate.callId
                                        aggregate.name = item.optString("name").takeIf(String::isNotBlank) ?: aggregate.name
                                        val finalArguments = item.optString("arguments")
                                        if (finalArguments.isNotBlank()) { aggregate.arguments.clear(); aggregate.arguments.append(finalArguments) }
                                    }
                                    "web_search_call" -> providerToolUses += parseHostedToolUse(item)
                                }
                            }
                            "response.output_text.annotation.added" -> event.optJSONObject("annotation")?.let { collectCitation(it, citations) }
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
                                collectCompletedOutput(finished, citations, providerToolUses)
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
                val finalText = appendSources(text.toString().trim(), citations.values.toList())
                emit(InferenceStreamEvent.Completed(InferenceResponse(request.requestId, endpoint.id, finalText, elapsedMs(started),
                    selectedModel = model ?: endpoint.model, toolCalls = proposals, finishReason = "completed", providerContinuationId = responseId,
                    firstTokenLatencyMs = firstTokenMs, promptTokens = inputTokens, completionTokens = outputTokens, totalTokens = totalTokens,
                    citations = citations.values.toList(), providerToolUses = providerToolUses.distinct())))
            } } finally { invalidation.close() }
            return@flow
        }
    }.flowOn(Dispatchers.IO)

    internal fun requestJson(endpoint: ProviderEndpoint, request: InferenceRequest, includeHostedSearch: Boolean = true): JSONObject = JSONObject().apply {
        put("model", endpoint.model)
        put("store", false)
        put("stream", true)
        put("instructions", request.systemPrompt)
        put("input", input(request))
        put("tools", JSONArray().apply {
            if (includeHostedSearch) put(JSONObject().put("type", "web_search"))
            if (request.tools.isNotEmpty()) put(JSONObject().apply {
                put("type", "namespace")
                put("name", TOOL_NAMESPACE)
                put("description", "Atlas device tools. Atlas enforces permissions and executes approved calls locally.")
                put("tools", JSONArray(request.tools.map { tool -> JSONObject().apply {
                    put("type", "function"); put("name", tool.name); put("description", tool.description)
                    put("parameters", JSONObject(tool.parametersJson)); put("strict", false)
                } }))
            })
        })
    }

    private fun collectCompletedOutput(response: JSONObject, citations: MutableMap<String, ProviderCitation>, uses: MutableList<ProviderToolUse>) {
        val output = response.optJSONArray("output") ?: return
        for (index in 0 until output.length()) {
            val item = output.optJSONObject(index) ?: continue
            when (item.optString("type")) {
                "web_search_call" -> uses += parseHostedToolUse(item)
                "message" -> item.optJSONArray("content")?.let { content ->
                    for (contentIndex in 0 until content.length()) {
                        val annotations = content.optJSONObject(contentIndex)?.optJSONArray("annotations") ?: continue
                        for (annotationIndex in 0 until annotations.length()) {
                            annotations.optJSONObject(annotationIndex)?.let { collectCitation(it, citations) }
                        }
                    }
                }
            }
        }
    }

    private fun collectCitation(annotation: JSONObject, citations: MutableMap<String, ProviderCitation>) {
        if (annotation.optString("type") != "url_citation") return
        val url = annotation.optString("url").takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: return
        citations[url] = ProviderCitation(annotation.optString("title").ifBlank { url }, url)
    }

    private fun parseHostedToolUse(item: JSONObject): ProviderToolUse {
        val action = item.optJSONObject("action")
        val queries = mutableListOf<String>()
        action?.optString("query")?.takeIf(String::isNotBlank)?.let(queries::add)
        action?.optJSONArray("queries")?.let { values ->
            for (index in 0 until values.length()) values.optString(index).takeIf(String::isNotBlank)?.let(queries::add)
        }
        val urls = buildList {
            action?.optString("url")?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let(::add)
            action?.optJSONArray("urls")?.let { values ->
                for (index in 0 until values.length()) values.optString(index)
                    .takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let(::add)
            }
        }
        return ProviderToolUse("web_search", action?.optString("type")?.takeIf(String::isNotBlank),
            item.optString("status").takeIf(String::isNotBlank), queries.distinct(), urls.distinct())
    }

    private fun appendSources(text: String, citations: List<ProviderCitation>): String {
        if (citations.isEmpty()) return text
        if (citations.all { text.contains(it.url) }) return text
        return buildString {
            append(text)
            append("\n\nSources:\n")
            citations.distinctBy { it.url }.forEachIndexed { index, citation ->
                append(index + 1).append(". [").append(citation.title).append("](").append(citation.url).append(")\n")
            }
        }.trimEnd()
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

    private fun httpRequest(endpoint: ProviderEndpoint, token: String, request: InferenceRequest, includeHostedSearch: Boolean) = Request.Builder().url(responsesUrl)
        .header("Authorization", "Bearer $token").header("Content-Type", "application/json").header("Accept", "text/event-stream")
        .header("X-Atlas-Request-Id", request.requestId)
        .post(requestJson(endpoint, request, includeHostedSearch).toString().toRequestBody("application/json".toMediaType())).build()

    private fun client(endpoint: ProviderEndpoint) = baseClient.newBuilder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(endpoint.timeoutMs, TimeUnit.MILLISECONDS).writeTimeout(30, TimeUnit.SECONDS).callTimeout(endpoint.timeoutMs, TimeUnit.MILLISECONDS).build()

    private fun providerError(status: Int, raw: String, requestId: String?): InferenceUnavailableException {
        val root = runCatching { JSONObject(raw) }.getOrNull()
        val provider = root?.optJSONObject("error")
        val code = provider.stringOrNull("code")
        val type = provider.stringOrNull("type")
        val param = provider.stringOrNull("param")
        val detail = provider.stringOrNull("message") ?: root.stringOrNull("detail")
        code?.takeIf(String::isNotBlank)?.let(auth::onProviderFailure)
        val message = when (code) {
            "subscription_sharing_user_not_eligible" -> "This ChatGPT account or workspace is not eligible for plan inference."
            "subscription_sharing_usage_limit_exceeded" -> "The ChatGPT plan usage limit was reached. Review usage in ChatGPT settings."
            "subscription_sharing_usage_unavailable" -> "ChatGPT plan usage is temporarily unavailable."
            "subscription_sharing_unsupported_capability" -> "The selected ChatGPT model does not support part of this request."
            "subscription_sharing_route_not_supported" -> "ChatGPT plan inference rejected this API route."
            "subscription_sharing_invalid_user", "chatpass_v2_scope_not_authorized", "chatpass_v2_invalid_authorization_context" -> "ChatGPT authorization is no longer valid. Sign in again."
            else -> buildString {
                append("ChatGPT returned HTTP ").append(status)
                val explanation = detail?.let(ChatGptOAuth::redact)?.take(300)
                    ?: code ?: type
                explanation?.takeIf(String::isNotBlank)?.let { append(": ").append(it) }
                if (param != null) append(" (parameter ").append(param.take(100)).append(')')
            }
        } + requestId?.let { " (request $it)" }.orEmpty()
        return InferenceUnavailableException(message, outcomeAmbiguous = status !in setOf(400, 401, 403, 429, 503), providerCode = code ?: type)
    }

    private fun responseFailure(response: JSONObject?): InferenceUnavailableException {
        return providerError(400, response?.toString().orEmpty(), null)
    }
    private fun JSONObject?.stringOrNull(key: String): String? = this?.takeIf { it.has(key) && !it.isNull(key) }
        ?.optString(key)?.takeUnless { it.isBlank() || it == "null" }
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
