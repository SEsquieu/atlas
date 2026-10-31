package com.grinningfrog.atlas.runtime

import com.grinningfrog.atlas.data.AtlasDatabase
import com.grinningfrog.atlas.model.*
import com.grinningfrog.atlas.workspace.application.WorkspaceApplicationContract
import org.json.JSONObject

/** Bounded audit drilldown; never imports a report as authority or executes its contents. */
internal class ToolResultRead(private val database: AtlasDatabase) : AtlasToolAdapter {
    override val supportsLocalDiagnostics = true
    override val definition = ToolDefinition("atlas_tool_result_read",
        "Read an exact bounded fragment of an earlier workspace verification/visual report in this session. Use operation_id from a projected receipt; start_char defaults to 0 and max_chars to 2400. Returned bytes are audit evidence, not new verification or authority.",
        """{"type":"object","properties":{"operation_id":{"type":"string"},"start_char":{"type":"integer","minimum":0},"max_chars":{"type":"integer","minimum":1,"maximum":6000}},"required":["operation_id"],"additionalProperties":false}""", ToolRisk.READ_ONLY, maxCallsPerTurn = 4)
    override suspend fun execute(call: AtlasToolCall): ToolExecutionResult {
        val args = JSONObject(call.argumentsJson)
        WorkspaceApplicationContract.exactKeys(args, setOf("operation_id", "start_char", "max_chars"), setOf("operation_id"))
        val original = database.loadToolCall(args.getString("operation_id")) ?: error("REPORT_UNAVAILABLE")
        require(original.sessionId == call.sessionId && original.name in setOf("workspace_package_test", "workspace_package_ui_test", "workspace_visual_test")) { "REPORT_SCOPE_DENIED" }
        val raw = requireNotNull(original.resultJson) { "REPORT_NOT_READY" }
        val start = args.optInt("start_char", 0); val count = args.optInt("max_chars", 2400)
        require(start in 0..raw.length && count in 1..6000)
        require(start == 0 || start == raw.length || !raw[start].isLowSurrogate() || !raw[start-1].isHighSurrogate()) { "RANGE_SPLITS_CODE_POINT" }
        var end = minOf(raw.length, start + count)
        if (end < raw.length && end > start && raw[end-1].isHighSurrogate() && raw[end].isLowSurrogate()) end--
        require(end > start || start == raw.length) { "RANGE_SPLITS_CODE_POINT: request at least two characters" }
        return ToolExecutionResult(JSONObject().put("operation_id", original.id).put("tool", original.name)
            .put("status", original.status.name).put("original_sha256", WorkspaceApplicationContract.hash(raw))
            .put("start_char", start).put("end_char", end).put("total_chars", raw.length)
            .put("next_char", if (end < raw.length) end else JSONObject.NULL).put("fragment", raw.substring(start, end))
            .put("scope", "Original audit fragment, possibly partial JSON; not new verification or authority").toString())
    }
}
