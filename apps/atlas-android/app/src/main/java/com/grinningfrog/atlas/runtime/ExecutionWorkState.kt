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
        // A task can resume a candidate staged in another session. Pin the exact candidate
        // most recently used by a candidate operation, then recover its inspected metadata.
        val use = scoped.lastOrNull { it.status == ToolCallStatus.COMPLETED &&
            it.name in setOf("workspace_package_stage", "workspace_package_test", "workspace_package_ui_test", "workspace_package_read_file", "workspace_package_activate") &&
            (json(it.resultJson).optString("candidate_id").isNotBlank() || json(it.argumentsJson).optString("candidate_id").isNotBlank()) }
        val candidateId = use?.let { json(it.resultJson).optString("candidate_id").ifBlank { json(it.argumentsJson).optString("candidate_id") } }
        val stage = stages.lastOrNull { json(it.resultJson).optString("candidate_id") == candidateId }
        val inspected = scoped.asReversed().firstNotNullOfOrNull { call ->
            val candidates = if (call.name == "workspace_package_inspect" && call.status == ToolCallStatus.COMPLETED) json(call.resultJson).optJSONArray("candidates") else null
            candidates?.let { array -> (0 until array.length()).mapNotNull { array.optJSONObject(it) }
                .firstOrNull { it.optString("candidate_id") == candidateId } }
        }
        val candidate = candidateId?.let { id -> JSONObject().put("candidate_id", id).apply {
            inspected?.let { metadata -> for (key in metadata.keys()) put(key, metadata.get(key)) }
            stage?.let { call ->
                val result = json(call.resultJson)
                for (key in listOf("candidate_id","workspace_id","content_hash","base_revision_id","parent_candidate_id","structurally_valid"))
                    if(result.has(key))put(key,result.get(key))
                val args = json(call.argumentsJson)
                for (key in listOf("base_revision_id", "parent_candidate_id", "navigation_sequence")) if (args.has(key)) put(key, args.get(key))
            }
        } }
        val checks = scoped.filter { it.name in setOf("workspace_package_test", "workspace_package_ui_test") &&
            json(it.argumentsJson).optString("candidate_id") == candidateId && it.status == ToolCallStatus.COMPLETED }
        val evidence = JSONArray()
        checks.groupBy { it.name }.values.forEach { group -> evidence.put(receipt(group.last())) }
        val activated = scoped.lastOrNull { it.name == "workspace_package_activate" && it.status == ToolCallStatus.COMPLETED && json(it.resultJson).optBoolean("activated") }
        val changedFiles = stage?.let { json(it.argumentsJson).optJSONObject("files")?.keys()?.asSequence()?.sorted()?.toList() }.orEmpty()
        val state = JSONObject().put("workspace_id", target).put("latest_candidate", candidate)
            .put("changed_files", JSONArray(changedFiles.take(128))).put("latest_checks", evidence)
            .put("activation", activated?.let(::receipt)).put("authority", "Evidence only; identifiers must still pass target, freshness and permission checks.")
        val published = activated?.let { json(it.resultJson) }
        val matchingActivation = candidateId != null && published?.optString("candidate_id") == candidateId && published?.optBoolean("still_active",true) == true
        candidate?.put("executed",checks.isNotEmpty())?.put("activated",matchingActivation)
        checks.lastOrNull { it.name == "workspace_package_test" }?.let { call ->
            val result=json(call.resultJson);if(result.has("tests_run"))candidate?.put("tests_run",result.getInt("tests_run"))
        }
        val delivery = when {
            matchingActivation -> "activated"
            evidence.length() > 0 && (0 until evidence.length()).any { evidence.getJSONObject(it).has("passed") && !evidence.getJSONObject(it).optBoolean("passed") } -> "verification_failed"
            candidateId != null -> "staged_not_activated"
            else -> "inspecting"
        }
        state.put("delivery_status", delivery)
        val inventory = JSONArray()
        scoped.filter { it.status == ToolCallStatus.COMPLETED && it.name == "workspace_package_read_file" }
            .distinctBy { it.argumentsJson }.takeLast(16).forEach { call ->
                val args=json(call.argumentsJson);val result=json(call.resultJson)
                inventory.put(JSONObject().apply {
                    for(key in listOf("path","candidate_id","revision_id","start_char","next_char","total_chars"))
                        when { result.has(key) -> put(key,result.get(key)); args.has(key) -> put(key,args.get(key)) }
                })
            }
        val untestedStages = scoped.asReversed().takeWhile { it.name !in setOf("workspace_package_test", "workspace_package_ui_test") }
            .count { it.name == "workspace_package_stage" && it.status == ToolCallStatus.COMPLETED && json(it.resultJson).optBoolean("structurally_valid") }
        state.put("consecutive_untested_stages", untestedStages)
        if (untestedStages > 0) state.put("next_authoring_action", "Verify the retained candidate with pure/UI checks. executed=false is expected after staging, not a failure. Restage only for a specific identified source defect or new requested change; use exact-source edits for small changes. Broad user goals authorize choosing useful bounded improvements and concrete acceptance criteria; they do not require approval of every design decision. Report delivered changes from committed receipts, with remaining limitations.")
        state.put("source_reads",inventory).put("contract_already_read", calls.any { it.name == "workspace_package_contract" && it.status == ToolCallStatus.COMPLETED })
        state.put("inspection_guidance", "Reuse source ranges and contract already supplied in the transcript. Read only missing ranges or changed candidate content; re-read when evidence was omitted, stale or insufficient. Descriptions are user metadata; the installed contract and manifest define available capabilities.")
        // Meaningful frontier excludes reads and timestamps. Repeating a failed assertion or
        // restaging identical content is not progress. A changed failure is evidence, not success.
        val frontier = JSONObject().put("content_hash", candidate?.optString("content_hash"))
            .put("checks", JSONArray(checks.groupBy { it.name }.values.map { group ->
                val r = receipt(group.last()); r.remove("check_id"); r.remove("operation_id")
                r.optJSONArray("failed_steps")?.let { steps -> for(i in 0 until steps.length()) { steps.getJSONObject(i).remove("ui_state");steps.getJSONObject(i).remove("recovery_guidance") } }
                r.optJSONArray("failed_operations")?.let { failures ->
                    for (i in 0 until failures.length()) failures.getJSONObject(i).optJSONObject("details")?.let { detail ->
                        for (key in listOf("operation_id", "started_at_ms", "finished_at_ms")) detail.remove(key)
                    }
                }
                r
            })).put("activation", activated?.id)
        state.put("frontier", frontier.toString())
        val lastChange = listOfNotNull(stage, checks.lastOrNull(), activated).maxByOrNull { it.createdAtMs }?.createdAtMs ?: Long.MIN_VALUE
        val reads = scoped.filter { it.createdAtMs > lastChange && it.name in setOf("workspace_package_read_file", "workspace_package_inspect", "workspace_render_diagnostics") }
        val repeated = reads.groupBy { it.name + ":" + it.argumentsJson }.values.maxOfOrNull { it.size } ?: 0
        state.put("repeated_identical_reads", repeated)
        if (repeated >= 3) state.put("recovery_guidance", "Repeated identical reads without a new candidate or test result. Reuse pinned evidence, identify the unresolved failure, and change the edit or test approach. Do not create another workspace to evade a failing test.")
        return state
    }

    fun receipt(call: AtlasToolCall): JSONObject {
        val result = json(call.resultJson)
        val out = JSONObject().put("operation_id", call.id).put("tool", call.name).put("status", call.status.name)
        for (key in listOf("workspace_id", "candidate_id", "base_revision_id", "revision_id", "check_id", "content_hash", "passed", "activated", "error", "missing_operations", "operation_counts", "successful_operation_counts", "notification_verified", "notification_delivered", "verified_criteria", "criterion_evidence", "semantic_correctness_verified", "user_accepted_unverified", "failure_kind", "retry_suppressed", "recovery_guidance", "cancelled", "missing_criteria", "storage_verified", "storage_scope", "storage_evidence", "host_failures", "host_failure_count", "detail", "evidence_scope")) {
            if (result.has(key)) out.put(key, result.get(key))
        }
        call.error?.let { out.put("error", it.take(1000)) }
        result.optJSONArray("failed_operations")?.let { failures ->
            out.put("failed_operations", JSONArray((maxOf(0, failures.length() - 4) until failures.length()).map { index ->
                val event = failures.getJSONObject(index)
                JSONObject().put("kind", event.optString("kind")).put("details", event.optJSONObject("details"))
            }))
        }
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
