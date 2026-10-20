package com.grinningfrog.atlas.runtime

import com.grinningfrog.atlas.model.AtlasToolCall
import com.grinningfrog.atlas.model.ToolCallStatus

/** Stop repeated deterministic rejections while leaving corrected proposals and changed policy recoverable. */
object ToolRetryPolicy {
    fun sameArguments(a: String,b: String): Boolean = runCatching {
        com.grinningfrog.atlas.workspace.application.WorkspaceApplicationContract.canonical(org.json.JSONObject(a))==com.grinningfrog.atlas.workspace.application.WorkspaceApplicationContract.canonical(org.json.JSONObject(b))
    }.getOrDefault(a==b)
    fun deterministicFailure(call: AtlasToolCall): Boolean {
        if(call.name !in setOf("workspace_package_stage","atlas_task_handoff_pixels"))return false
        return call.status==ToolCallStatus.REJECTED ||
            call.status==ToolCallStatus.FAILED && (call.name=="atlas_task_handoff_pixels" && call.error.orEmpty().startsWith("user_quote") || call.error.orEmpty().startsWith("IDENTICAL_RETRY_SUPPRESSED") || call.error.orEmpty().startsWith("Unknown fields:") || call.error.orEmpty().startsWith("VALIDATION_FAILED")) ||
            call.status==ToolCallStatus.COMPLETED && runCatching {org.json.JSONObject(call.resultJson.orEmpty()).optString("error")=="STAGING_ARGUMENTS_INVALID"}.getOrDefault(false)
    }
    /** Corrected arguments stay eligible; transient executor failures do not suppress a proposal. */
    fun identicalDeterministicFailure(proposal: AtlasToolCall, history: List<AtlasToolCall>): Boolean =
        history.filter {it.id!=proposal.id && it.name==proposal.name && sameArguments(it.argumentsJson,proposal.argumentsJson)}
            .takeLast(2).let {matches -> matches.size==2 && matches.all(::deterministicFailure)}
    fun repeatedRejections(calls: List<AtlasToolCall>): Set<String> = calls.groupBy { it.name }.filterValues { history ->
        val last = history.takeLast(2)
        last.size == 2 && last.all { it.status == ToolCallStatus.REJECTED || (it.status == ToolCallStatus.FAILED && it.name == ToolHarness.CLARIFICATION_TOOL) } &&
            !last[0].error.isNullOrBlank() && last[0].error == last[1].error
    }.keys
}
