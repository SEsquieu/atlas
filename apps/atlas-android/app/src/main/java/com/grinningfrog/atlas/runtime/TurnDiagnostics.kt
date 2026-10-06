package com.grinningfrog.atlas.runtime

import com.grinningfrog.atlas.model.AgentTurn
import com.grinningfrog.atlas.model.AtlasEvent
import com.grinningfrog.atlas.model.AtlasToolCall
import com.grinningfrog.atlas.model.ToolCallStatus
import com.grinningfrog.atlas.model.TurnStatus
import org.json.JSONArray
import org.json.JSONObject

/** One evidence projection shared by the operator and the model. No generated success claims. */
data class TurnDiagnostics(
    val turn: AgentTurn,
    val inferenceMs: Long,
    val toolCalls: Int,
    val runningTool: String?,
    val schema: String,
    val migration: String,
    val tests: String,
    val revisionIds: List<String>,
    val lateToolCalls: Int,
    val inferencePending: Boolean,
) {
    val terminal: Boolean get() = turn.status in setOf(TurnStatus.COMPLETED, TurnStatus.COMPLETED_LATE,
        TurnStatus.FAILED, TurnStatus.CANCELLED, TurnStatus.HARD_CANCELLED, TurnStatus.INTERRUPTED)
    val headline: String get() = when {
        turn.status == TurnStatus.SOFT_TIMED_OUT -> "STILL GENERATING · interaction deadline reached"
        turn.status == TurnStatus.COMPLETED_LATE && lateToolCalls > 0 -> "LATE RESULT · tool calls were not executed"
        turn.status == TurnStatus.COMPLETED -> "COMPLETED"
        turn.status == TurnStatus.COMPLETED_LATE -> "COMPLETED LATE"
        terminal && revisionIds.isNotEmpty() -> "REVISION COMMITTED · turn interrupted"
        terminal -> "${turn.status.name} · no workspace revision committed"
        revisionIds.isNotEmpty() -> "REVISION COMMITTED · finishing reply"
        else -> "ATLAS IS WORKING"
    }
    fun elapsedMs(nowMs: Long): Long = ((if (terminal) turn.updatedAtMs else nowMs) - turn.createdAtMs).coerceAtLeast(0)
    fun json(nowMs: Long): JSONObject = JSONObject().apply {
        put("turn_id", turn.id); put("status", turn.status.name); put("outcome", headline)
        put("elapsed_ms", elapsedMs(nowMs)); put("inference_ms", inferenceMs); put("inference_pending", inferencePending)
        put("steps_used", turn.stepCount); put("steps_limit", AgentLoopPolicy.MAX_STEPS)
        put("tool_calls_used", toolCalls); put("tool_calls_limit", AgentLoopPolicy.MAX_TOOL_CALLS)
        put("wall_time_limit_ms", AgentLoopPolicy.MAX_WALL_TIME_MS); put("interaction_deadline_ms", 60_000)
        put("schema", schema); put("migration", migration); put("tests", tests)
        put("committed_revision_ids", JSONArray(revisionIds)); put("late_unexecuted_tool_calls", lateToolCalls)
        runningTool?.let { put("running_tool", it) }; turn.error?.let { put("reason", it) }
    }
    companion object {
        fun project(turn: AgentTurn, calls: List<AtlasToolCall>, events: List<AtlasEvent>): TurnDiagnostics {
            val own = events.mapNotNull { event ->
                runCatching { JSONObject(event.dataJson) }.getOrNull()?.takeIf { it.optString("turnId") == turn.id }?.let { event to it }
            }
            val inferenceEvents = own.filter { it.first.type in setOf("provider.responded", "provider.completed_late", "provider.failed", "provider.hard_cancelled") }
            val requests = own.filter { it.first.type == "provider.requested" }.associateBy { it.second.optString("requestId") }
            val inferenceMs = inferenceEvents.distinctBy { it.second.optString("requestId") }.sumOf { (event, data) ->
                if (data.has("latencyMs")) data.optLong("latencyMs") else requests[data.optString("requestId")]?.let { (event.atMs - it.first.atMs).coerceAtLeast(0) } ?: 0L
            }
            val requested = own.filter { it.first.type == "provider.requested" }.map { it.second.optString("requestId") }.toSet()
            val finished = inferenceEvents.map { it.second.optString("requestId") }.toSet()
            val results = calls.filter { it.status == ToolCallStatus.COMPLETED }.mapNotNull { call ->
                runCatching { JSONObject(call.resultJson.orEmpty()) }.getOrNull()?.let { call to it }
            }
            val lastValidation = results.lastOrNull { it.first.name == "workspace_validate_definition" }?.second
            val committed = results.filter { (call, result) -> call.name in setOf("workspace_create", "workspace_replace_definition", "workspace_patch_definition") && result.optBoolean("ok") && result.has("revision_id") }
            val evidence = committed.lastOrNull()?.second ?: lastValidation
            return TurnDiagnostics(turn, inferenceMs, calls.size,
                calls.lastOrNull { it.status == ToolCallStatus.RUNNING }?.name,
                when { evidence?.has("valid") == true -> if (evidence.optBoolean("valid")) "passed" else "failed"; committed.isNotEmpty() -> "passed at commit"; else -> "not reached" },
                if (evidence?.has("migration") == true) "checked" else "not reported",
                when { evidence?.has("simulation_passed") == true -> if (evidence.optBoolean("simulation_passed")) "${evidence.optInt("tests_run")}/${evidence.optInt("tests_run")} passed" else "failed: ${evidence.optJSONArray("test_failures")}"; else -> "not reported" },
                committed.map { it.second.getString("revision_id") },
                own.filter { it.first.type == "provider.completed_late" }.sumOf { it.second.optInt("toolCallCount") },
                (turn.status in setOf(TurnStatus.WAITING_FOR_MODEL, TurnStatus.SOFT_TIMED_OUT) || (requested - finished).isNotEmpty()) && !turn.status.let { it in setOf(TurnStatus.COMPLETED, TurnStatus.FAILED, TurnStatus.CANCELLED, TurnStatus.HARD_CANCELLED, TurnStatus.INTERRUPTED, TurnStatus.COMPLETED_LATE) })
        }
    }
}
