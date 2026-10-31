package com.grinningfrog.atlas.runtime

import com.grinningfrog.atlas.model.InferenceRequest
import org.json.JSONArray
import org.json.JSONObject

data class PacketSection(val name: String, val text: String, val required: Boolean = false)
internal data class PreparedPacket(val request: InferenceRequest, val diagnostics: JSONObject)
internal class InferencePacketCapacityException(val diagnostics: JSONObject) : IllegalArgumentException(
    "INFERENCE_PACKET_LIMIT: required context cannot fit after evidence projection; " +
        "text=${diagnostics.getInt("text_characters")}; response_headroom=8192; limit=${diagnostics.getInt("limit_characters")}. " +
        "Task and audit are saved. Reduce the current source/detail request or change the context before resuming.")

/** Provider-neutral text accounting. Images and provider-specific wire encoding are separate. */
internal object InferencePacketCompiler {
    const val MAX_TEXT_CHARACTERS = 128_000
    const val RESPONSE_HEADROOM_CHARACTERS = 8192
    /** Reserve policy, steering, tool protocol and exact task facts before optional evidence. */
    fun prepare(request: InferenceRequest, sections: List<PacketSection>, maximum: Int = MAX_TEXT_CHARACTERS): PreparedPacket {
        require(sections.map { it.name }.distinct().size == sections.size)
        var prepared = request.copy(systemPrompt = request.systemPrompt + sections.filter { it.required }.joinToString("") { "\n\n" + it.text })
        val optional = sections.filterNot { it.required }
        // Reserve the omission manifest up front; adding evidence can only shorten its names.
        fun omissionManifest(names: List<String>) = "\nPACKET PROJECTION: omitted optional sections: ${names.joinToString().ifEmpty { "none" }}. Omitted evidence is unknown, never success or authority. Full durable audit is retained; retrieve pinned source or tool results when needed."
        val manifestOffset = prepared.systemPrompt.length
        val manifest = omissionManifest(optional.map { it.name }) + "    " // Room for 'none' even with short names.
        prepared = prepared.copy(systemPrompt = prepared.systemPrompt + manifest)
        val requiredStats = measure(prepared, maximum)
        if (requiredStats.getInt("text_characters").toLong() + RESPONSE_HEADROOM_CHARACTERS > maximum) {
            requiredStats.put("recovery", "essential_context_exceeds_capacity").put("sections", JSONArray(sections.map {
                JSONObject().put("name", it.name).put("characters", it.text.length).put("required", it.required)
            }))
            throw InferencePacketCapacityException(requiredStats)
        }
        val retained = mutableListOf<String>(); val omitted = mutableListOf<String>()
        var textCharacters = requiredStats.getInt("text_characters").toLong()
        for (section in optional) {
            val extra = section.text.length.toLong() + 2
            if (textCharacters + extra + RESPONSE_HEADROOM_CHARACTERS <= maximum) {
                prepared = prepared.copy(systemPrompt = prepared.systemPrompt + "\n\n" + section.text)
                textCharacters += extra
                retained += section.name
            } else omitted += section.name
        }
        prepared = prepared.copy(systemPrompt = prepared.systemPrompt.replaceRange(manifestOffset, manifestOffset + manifest.length, omissionManifest(omitted)))
        val stats = compile(prepared, maximum).put("recovery", if (omitted.isEmpty()) "within_budget" else "optional_evidence_projected")
            .put("retained_optional_sections", JSONArray(retained)).put("omitted_optional_sections", JSONArray(omitted))
            .put("required_text_characters", requiredStats.getInt("text_characters"))
            .put("sections", JSONArray(sections.map { JSONObject().put("name", it.name).put("characters", it.text.length).put("required", it.required) }))
        return PreparedPacket(prepared, stats)
    }

    fun compile(request: InferenceRequest, maximum: Int = MAX_TEXT_CHARACTERS): JSONObject {
        val stats = measure(request, maximum)
        if (stats.getInt("text_characters").toLong() + RESPONSE_HEADROOM_CHARACTERS > maximum) throw InferencePacketCapacityException(stats)
        return stats
    }

    private fun measure(request: InferenceRequest, maximum: Int): JSONObject {
        val messages = JSONArray(request.messages.map { m -> JSONObject().put("role", m.role.name).put("content", m.content)
            .put("tool_call_id", m.toolCallId).put("tool_calls", JSONArray(m.toolCalls.map { c ->
                JSONObject().put("id", c.id).put("name", c.name).put("arguments", c.argumentsJson)
            })) })
        val tools = JSONArray(request.tools.map { t -> JSONObject().put("name", t.name).put("description", t.description).put("parameters", JSONObject(t.parametersJson)) })
        val parts = linkedMapOf("system" to request.systemPrompt, "messages" to messages.toString(), "tools" to tools.toString(),
            "user_text" to request.userText, "context_note" to request.contextNote.orEmpty())
        val total = parts.values.sumOf { it.length }
        return JSONObject().put("text_characters", total).put("text_utf8_bytes", parts.values.sumOf { it.toByteArray(Charsets.UTF_8).size })
            .put("response_headroom_characters", RESPONSE_HEADROOM_CHARACTERS).put("limit_characters", maximum)
            .put("components", JSONObject(parts.mapValues { it.value.length })).put("image_attached", request.image != null)
            .put("accounting_scope", "Provider-neutral text and schema encoding; excludes image bytes and provider wire overhead; token estimate is heuristic")
    }
}
