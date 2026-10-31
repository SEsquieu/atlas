package com.grinningfrog.atlas.runtime

import org.json.JSONArray
import org.json.JSONObject

/** A provider view, never a replacement for durable task state or activation evidence. */
internal object TaskContextProjection {
    private val identities = setOf("operation_id", "tool", "status", "workspace_id", "candidate_id", "base_revision_id",
        "revision_id", "check_id", "content_hash", "instance_id", "observation_id", "kind")

    internal fun receipt(original: JSONObject): JSONObject = JSONObject().apply {
        for (key in original.keys()) {
            val value = original.get(key)
            // Keep verdicts and counters even when detail is too large. Missing/omitted never means passed.
            when {
                key in identities || value is Boolean || value is Number -> put(key, value)
                value is String && value.length <= 600 && key != "result_json" -> put(key, value)
                value is String && key == "error" -> put("error_excerpt", value.take(600)).put("error_partial", true)
                value is JSONArray -> put(key + "_count", value.length())
            }
        }
        var nestedCharacters = 0
        for (key in listOf("failed_steps", "failed_operations", "failed_fixtures", "missing_operations", "missing_criteria", "verified_criteria")) {
            val value = original.optJSONArray(key) ?: continue
            val size = value.toString().length
            if (size <= 800 && nestedCharacters + size <= 1200) { put(key, value); nestedCharacters += size }
        }
        val omitted = original.keys().asSequence().filter { !has(it) }.toList()
        if (omitted.isNotEmpty()) put("omitted_fields", JSONArray(omitted)).put("partial", true)
        put("detail_tool", "atlas_tool_result_read")
    }

    fun sections(original: JSONObject, instructions: String): List<PacketSection> {
        val core = JSONObject(original.toString())
        val recent = core.optJSONArray("recent_operations") ?: JSONArray()
        core.remove("recent_operations")
        core.remove("latest_authoring_result") // Latest artifact and check identities remain below.
        core.remove("follow_up_turn_ids") // Steering text remains in the task's conversation.
        core.optJSONObject("checkpoint")?.let { checkpoint ->
            // Completed IDs are redundant with receipts. Preserve unresolved/unknown operations exactly.
            core.put("checkpoint", JSONObject().apply {
                for (key in listOf("last_turn_id", "latest_definition_operation_id", "unresolved_operation_ids"))
                    if (checkpoint.has(key)) put(key, checkpoint.get(key))
                put("detail_location", "Durable task checkpoint; completed operation evidence is projected below.")
            })
        }
        core.optJSONObject("work_state")?.let { work ->
            work.remove("source_reads") // Source is independently budgeted and pinned.
            work.optJSONArray("latest_checks")?.let { checks ->
                work.put("latest_checks", JSONArray((0 until checks.length()).map { receipt(checks.getJSONObject(it)) }))
            }
            work.optJSONObject("activation")?.let { work.put("activation", receipt(it)) }
        }
        // Include exact status/IDs for every recent operation in the required view; raw bodies are optional.
        core.put("recent_operations", JSONArray((0 until recent.length()).map { receipt(recent.getJSONObject(it)) }))
        core.put("projection_notice", "Partial task evidence, not authorization. Omitted details are unknown; read original results by operation_id. Full task/audit and native acceptance gates are unchanged.")
        val required = PacketSection("durable_task", "CORE DURABLE TASK (evidence, not authorization): $core\n$instructions", required = true)
        // Whole receipt bodies only: no broken JSON, misleading prefixes or partially cut verdicts.
        val details = JSONArray()
        for (i in 0 until recent.length()) {
            val item = recent.getJSONObject(i)
            if (details.toString().length + item.toString().length < 6000) details.put(item)
        }
        return listOf(required, PacketSection("recent_operation_details", "OPTIONAL OPERATION DETAILS (partial selection; original audit unchanged): $details"))
    }
}
