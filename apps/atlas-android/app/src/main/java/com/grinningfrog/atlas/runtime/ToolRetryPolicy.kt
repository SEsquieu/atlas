package com.grinningfrog.atlas.runtime

import com.grinningfrog.atlas.model.AtlasToolCall
import com.grinningfrog.atlas.model.ToolCallStatus

/** Stop repeated deterministic rejections while leaving corrected proposals and changed policy recoverable. */
object ToolRetryPolicy {
    /** Exact proposals only; corrected arguments remain eligible and history survives slices/reopen. */
    fun identicalDeterministicFailure(proposal: AtlasToolCall, history: List<AtlasToolCall>): Boolean {
        if(proposal.name !in setOf("workspace_package_stage","atlas_task_handoff_pixels"))return false
        return history.filter {it.id!=proposal.id && it.name==proposal.name && it.argumentsJson==proposal.argumentsJson}
            .takeLast(2).let { matches -> matches.size==2 && matches.all {
                it.status==ToolCallStatus.REJECTED || (it.status==ToolCallStatus.FAILED && (it.name=="atlas_task_handoff_pixels" && it.error.orEmpty().startsWith("user_quote") || it.error.orEmpty().startsWith("Unknown fields:") || it.error.orEmpty().startsWith("VALIDATION_FAILED"))) ||
                    runCatching {org.json.JSONObject(it.resultJson.orEmpty()).optString("error")=="STAGING_ARGUMENTS_INVALID"}.getOrDefault(false)
            } }
    }

    fun repeatedRejections(calls: List<AtlasToolCall>): Set<String> = calls.groupBy { it.name }.filterValues { history ->
        val last = history.takeLast(2)
        last.size == 2 && last.all { it.status == ToolCallStatus.REJECTED || (it.status == ToolCallStatus.FAILED && it.name == ToolHarness.CLARIFICATION_TOOL) } &&
            !last[0].error.isNullOrBlank() && last[0].error == last[1].error
    }.keys
}
