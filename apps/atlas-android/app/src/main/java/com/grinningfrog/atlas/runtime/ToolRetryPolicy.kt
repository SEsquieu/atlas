package com.grinningfrog.atlas.runtime

import com.grinningfrog.atlas.model.AtlasToolCall
import com.grinningfrog.atlas.model.ToolCallStatus

/** Stop repeated deterministic rejections while leaving corrected proposals and changed policy recoverable. */
object ToolRetryPolicy {
    fun repeatedRejections(calls: List<AtlasToolCall>): Set<String> = calls.groupBy { it.name }.filterValues { history ->
        val last = history.takeLast(2)
        last.size == 2 && last.all { it.status == ToolCallStatus.REJECTED || (it.status == ToolCallStatus.FAILED && it.name == ToolHarness.CLARIFICATION_TOOL) } &&
            !last[0].error.isNullOrBlank() && last[0].error == last[1].error
    }.keys
}
