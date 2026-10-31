package com.grinningfrog.atlas.runtime

import com.grinningfrog.atlas.model.InferenceRequest
import org.json.JSONArray
import org.json.JSONObject

/** Provider-neutral text accounting. Images and provider-specific wire encoding are separate. */
internal object InferencePacketCompiler {
    const val MAX_TEXT_CHARACTERS = 128_000
    const val RESPONSE_HEADROOM_CHARACTERS = 8192
    fun compile(request: InferenceRequest, maximum: Int = MAX_TEXT_CHARACTERS): JSONObject {
        val messages = JSONArray(request.messages.map { m -> JSONObject().put("role", m.role.name).put("content", m.content)
            .put("tool_call_id", m.toolCallId).put("tool_calls", JSONArray(m.toolCalls.map { c ->
                JSONObject().put("id", c.id).put("name", c.name).put("arguments", c.argumentsJson)
            })) })
        val tools = JSONArray(request.tools.map { t -> JSONObject().put("name", t.name).put("description", t.description).put("parameters", JSONObject(t.parametersJson)) })
        val parts = linkedMapOf("system" to request.systemPrompt, "messages" to messages.toString(), "tools" to tools.toString(),
            "user_text" to request.userText, "context_note" to request.contextNote.orEmpty())
        val total = parts.values.sumOf { it.length }
        require(total.toLong() + RESPONSE_HEADROOM_CHARACTERS <= maximum) {
            "INFERENCE_PACKET_LIMIT: text=$total; response_headroom=$RESPONSE_HEADROOM_CHARACTERS; limit=$maximum. Audit is saved; shorten oversized source/detail requests before retrying."
        }
        return JSONObject().put("text_characters", total).put("text_utf8_bytes", parts.values.sumOf { it.toByteArray(Charsets.UTF_8).size })
            .put("response_headroom_characters", RESPONSE_HEADROOM_CHARACTERS).put("limit_characters", maximum)
            .put("components", JSONObject(parts.mapValues { it.value.length })).put("image_attached", request.image != null)
            .put("accounting_scope", "Provider-neutral text and schema encoding; excludes image bytes and provider wire overhead; token estimate is heuristic")
    }
}
