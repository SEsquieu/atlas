package com.grinningfrog.atlas.provider

import android.util.Base64
import com.grinningfrog.atlas.model.InferenceRequest
import com.grinningfrog.atlas.model.InferenceResponse
import com.grinningfrog.atlas.model.ProviderEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class OpenAiCompatibleBackend(
    private val baseClient: OkHttpClient = OkHttpClient(),
) : InferenceBackend {
    override suspend fun infer(endpoint: ProviderEndpoint, apiKey: String?, request: InferenceRequest): InferenceResponse = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        val payload = JSONObject().apply {
            put("model", endpoint.model)
            put("stream", false)
            put("temperature", 0.2)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", request.systemPrompt))
                put(JSONObject().put("role", "user").put("content", userContent(request)))
            })
        }
        val httpRequest = Request.Builder()
            .url(chatCompletionsUrl(endpoint.baseUrl))
            .header("Content-Type", "application/json")
            .header("X-Atlas-Request-Id", request.requestId)
            .header("X-Atlas-Capability", request.capability.name.lowercase())
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

        client.newCall(httpRequest).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw InferenceUnavailableException("${endpoint.name} returned HTTP ${response.code}: ${body.take(300)}")
            val text = extractText(body) ?: throw InferenceUnavailableException("${endpoint.name} returned no assistant text")
            InferenceResponse(request.requestId, endpoint.id, text, elapsedMs(started))
        }
    }

    private fun userContent(request: InferenceRequest): Any {
        val image = request.image
        if (image == null) return listOfNotNull(request.userText, request.contextNote).joinToString("\n\n")
        val encoded = Base64.encodeToString(image.bytes, Base64.NO_WRAP)
        return JSONArray().apply {
            put(JSONObject().put("type", "text").put("text", buildString {
                append(request.userText)
                request.contextNote?.let { append("\n\nAtlas context: ").append(it) }
            }))
            put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:${image.mimeType};base64,$encoded")))
        }
    }

    private fun extractText(raw: String): String? = runCatching {
        val root = JSONObject(raw)
        val content = root.getJSONArray("choices").getJSONObject(0).getJSONObject("message").opt("content")
        when (content) {
            is String -> content.trim().ifBlank { null }
            is JSONArray -> buildList {
                for (index in 0 until content.length()) {
                    val item = content.optJSONObject(index)
                    item?.optString("text")?.takeIf(String::isNotBlank)?.let(::add)
                }
            }.joinToString("\n").trim().ifBlank { null }
            else -> null
        }
    }.getOrNull()

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
