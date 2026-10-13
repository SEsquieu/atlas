package com.grinningfrog.atlas.runtime

import com.grinningfrog.atlas.model.ToolCallProposal
import org.json.JSONObject

/** Validate the whole control batch before changing pending clarification state. */
object ClarificationControl {
    fun rejection(proposals: List<ToolCallProposal>, pendingId: String?): StructuredToolRejection? {
        return try {
            require(proposals.size == 1) { "Clarification control must be the only proposal in a step" }
            val args = JSONObject(proposals.single().argumentsJson)
            require(args.keys().asSequence().all { it in setOf("action", "clarification_id", "question", "reason", "ambiguity", "options", "blocking", "normalized_answer") }) { "Unknown clarification argument" }
            when (args.getString("action").lowercase()) {
                "request" -> {
                    require(args.opt("question") is String && args.getString("question").isNotBlank() && args.opt("reason") is String && args.getString("reason").isNotBlank()) { "Clarification request requires a question and reason" }
                    require(!args.has("blocking") || args.opt("blocking") is Boolean) { "blocking must be boolean" }
                    if (args.has("options")) {
                        val options = args.getJSONArray("options")
                        require(options.length() <= 5 && (0 until options.length()).all { options.opt(it) is String }) { "options must contain at most five strings" }
                    }
                }
                "resolve", "defer", "abandon" -> require(pendingId != null && args.optString("clarification_id") == pendingId) { "Clarification control must match the current pending question" }
                else -> error("Unknown clarification action")
            }
            null
        } catch (error: Exception) {
            StructuredToolRejection(error.message ?: "Invalid clarification control", "invalid_clarification_control", JSONObject()
                .put("hint", "Correct the control and retry within the existing budget. request requires nonblank question and reason. Use one control per step; pending state was not changed.")
                .put("pending_clarification_id", pendingId ?: JSONObject.NULL))
        }
    }
}
