package com.grinningfrog.atlas.runtime

import com.grinningfrog.atlas.model.ToolCallProposal
import com.grinningfrog.atlas.model.ToolDefinition
import org.json.JSONArray
import org.json.JSONObject

/** Fill absent host identifiers only. Explicit values always reach the ordinary conflict checks. */
internal object WorkspaceArgumentBinding {
    private val candidateTools = setOf("workspace_package_validate", "workspace_package_test", "workspace_package_ui_test", "workspace_package_activate", "workspace_accept_unverified")
    private val navigationTools = setOf("workspace_package_stage", "workspace_package_activate", "workspace_accept_unverified")
    private val tools = candidateTools + navigationTools

    fun supports(name: String) = name in tools || name=="workspace_visual_test"

    fun definition(definition: ToolDefinition): ToolDefinition {
        if(definition.name=="workspace_visual_test")return definition.copy(description=definition.description+" For open, omit workspace_id/candidate_id to use the task target and latest staged candidate. Later actions use only instance_id; explicit identifiers are never corrected.")
        if (definition.name !in tools) return definition
        val schema = JSONObject(definition.parametersJson)
        val optional = setOf("workspace_id", "navigation_sequence") +
            (if (definition.name in candidateTools) setOf("candidate_id") else emptySet()) +
            (if (definition.name == "workspace_accept_unverified") setOf("user_quote") else emptySet())
        val required = schema.optJSONArray("required") ?: JSONArray()
        schema.put("required", JSONArray((0 until required.length()).map(required::getString).filterNot { it in optional }))
        return definition.copy(parametersJson = schema.toString(), description = definition.description +
            " Omit workspace_id, candidate_id and navigation_sequence to use the task's bound target and latest staged candidate where applicable; explicit values are never corrected. Base revisions and check IDs must still be supplied exactly." +
            if (definition.name == "workspace_accept_unverified") " Omit user_quote: Core binds the actual latest user message before native confirmation." else "")
    }

    fun bind(proposal: ToolCallProposal, workspaceId: String?, sequence: Long, candidateId: String?, userId: String?, userText: String?): ToolCallProposal {
        if(proposal.name=="workspace_visual_test") {
            val args=runCatching {JSONObject(proposal.argumentsJson)}.getOrNull() ?: return proposal
            if(args.optString("action")!="open" || workspaceId.isNullOrBlank() || workspaceId=="null")return proposal
            if(!args.has("workspace_id"))args.put("workspace_id",workspaceId)
            if(args.optString("workspace_id")==workspaceId && !args.has("candidate_id") && !candidateId.isNullOrBlank())args.put("candidate_id",candidateId)
            return proposal.copy(argumentsJson=args.toString())
        }
        if (proposal.name !in tools || workspaceId.isNullOrBlank() || workspaceId == "null") return proposal
        val args = runCatching { JSONObject(proposal.argumentsJson) }.getOrNull() ?: return proposal
        if (!args.has("workspace_id")) args.put("workspace_id", workspaceId)
        // Never combine a foreign explicit target with the task's candidate or navigation token.
        if (args.optString("workspace_id") != workspaceId) return proposal
        if (proposal.name in navigationTools && !args.has("navigation_sequence")) args.put("navigation_sequence", sequence)
        if (proposal.name in candidateTools && !args.has("candidate_id") && !candidateId.isNullOrBlank()) args.put("candidate_id", candidateId)
        if (proposal.name == "workspace_accept_unverified" && userId != null && userText != null) {
            if (!args.has("user_quote")) args.put("user_quote", userText.take(500))
            // This field is Core-owned, never trusted from model output.
            args.put("user_message_id", userId)
        }
        return proposal.copy(argumentsJson = args.toString())
    }
}
