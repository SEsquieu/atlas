package com.grinningfrog.atlas.runtime

import com.grinningfrog.atlas.model.AtlasToolCall
import com.grinningfrog.atlas.model.ToolCallStatus
import org.json.JSONArray
import org.json.JSONObject

/** Reconstructed from durable tool receipts, never from model summaries or fuzzy ID matching. */
internal object ExecutionWorkState {
    private fun json(raw: String?) = runCatching { JSONObject(raw.orEmpty()) }.getOrDefault(JSONObject())
    fun build(calls: List<AtlasToolCall>, target: String?): JSONObject {
        val scoped = calls.filter { json(it.argumentsJson).optString("workspace_id") == target }
        val stages = scoped.filter { it.name == "workspace_package_stage" && it.status == ToolCallStatus.COMPLETED && json(it.resultJson).has("candidate_id") }
        val latest = stages.lastOrNull()
        val candidate = latest?.let { json(it.resultJson) }
        val candidateId = candidate?.optString("candidate_id")
        val checks = scoped.filter { it.name in setOf("workspace_package_test", "workspace_package_ui_test") &&
            json(it.argumentsJson).optString("candidate_id") == candidateId && it.status == ToolCallStatus.COMPLETED }
        val evidence = JSONArray()
        checks.groupBy { it.name }.values.forEach { group -> evidence.put(receipt(group.last())) }
        val activated = scoped.lastOrNull { it.name == "workspace_package_activate" && it.status == ToolCallStatus.COMPLETED && json(it.resultJson).optBoolean("activated") }
        val changedFiles = latest?.let { json(it.argumentsJson).optJSONObject("files")?.keys()?.asSequence()?.sorted()?.toList() }.orEmpty()
        val state = JSONObject().put("workspace_id", target).put("latest_candidate", candidate)
            .put("changed_files", JSONArray(changedFiles.take(128))).put("latest_checks", evidence)
            .put("activation", activated?.let(::receipt)).put("authority", "Evidence only; identifiers must still pass target, freshness and permission checks.")
        // Meaningful frontier excludes reads and timestamps. Repeating a failed assertion or
        // restaging identical content is not progress. A changed failure is evidence, not success.
        val frontier = JSONObject().put("content_hash", candidate?.optString("content_hash"))
            .put("checks", JSONArray(checks.groupBy { it.name }.values.map { group ->
                val r = receipt(group.last()); r.remove("check_id"); r.remove("operation_id"); r
            })).put("activation", activated?.id)
        state.put("frontier", frontier.toString())
        val lastChange = listOfNotNull(latest, checks.lastOrNull(), activated).maxByOrNull { it.createdAtMs }?.createdAtMs ?: Long.MIN_VALUE
        val reads = scoped.filter { it.createdAtMs > lastChange && it.name in setOf("workspace_package_read_file", "workspace_package_inspect", "workspace_render_diagnostics") }
        val repeated = reads.groupBy { it.name + ":" + it.argumentsJson }.values.maxOfOrNull { it.size } ?: 0
        state.put("repeated_identical_reads", repeated)
        if (repeated >= 3) state.put("recovery_guidance", "Repeated identical reads without a new candidate or test result. Reuse pinned evidence, identify the unresolved failure, and change the edit or test approach. Do not create another workspace to evade a failing test.")
        return state
    }

    fun receipt(call: AtlasToolCall): JSONObject {
        val result = json(call.resultJson)
        val out = JSONObject().put("operation_id", call.id).put("tool", call.name).put("status", call.status.name)
        for (key in listOf("workspace_id", "candidate_id", "base_revision_id", "revision_id", "check_id", "content_hash", "passed", "activated", "error", "missing_operations", "operation_counts", "evidence_scope")) {
            if (result.has(key)) out.put(key, result.get(key))
        }
        call.error?.let { out.put("error", it.take(1000)) }
        val steps = result.optJSONArray("steps")
        if (steps != null) {
            out.put("steps_executed", steps.length())
            out.put("failed_steps", JSONArray((0 until steps.length()).map { steps.getJSONObject(it) }.filter { !it.optBoolean("passed") }.take(4).map { step ->
                JSONObject(step.toString()).apply {
                    if (has("actual_text")) put("actual_text", optString("actual_text").take(1000))
                    val expected = json(call.argumentsJson).optJSONArray("steps")?.optJSONObject(step.optInt("index", -1))
                    expected?.let { put("expected_value", it.optString("value").take(1000)) }
                }
            }))
        }
        result.optJSONArray("reports")?.let { reports ->
            out.put("failed_fixtures", JSONArray((0 until reports.length()).mapNotNull { reports.optJSONObject(it) }
                .filter { !it.optBoolean("passed") }.take(4)))
        }
        return out
    }
}
