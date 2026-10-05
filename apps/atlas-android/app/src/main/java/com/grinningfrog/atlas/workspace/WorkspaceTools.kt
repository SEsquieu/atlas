package com.grinningfrog.atlas.workspace

import com.grinningfrog.atlas.data.AtlasDatabase
import com.grinningfrog.atlas.model.AtlasSession
import com.grinningfrog.atlas.model.AtlasToolCall
import com.grinningfrog.atlas.model.ToolCallProposal
import com.grinningfrog.atlas.model.ToolDefinition
import com.grinningfrog.atlas.model.ToolPolicyDecision
import com.grinningfrog.atlas.model.ToolRisk
import com.grinningfrog.atlas.runtime.AtlasToolAdapter
import com.grinningfrog.atlas.runtime.ToolExecutionResult
import org.json.JSONArray
import org.json.JSONObject

private const val AUTHORING_GUIDE = "Definition format atlas.workspace.v1 requires title and components. Supported component types: text(text), status(label,value), metric(label,value), notes(label), counter(label,initial), checklist(label,items string array), form(label,collection,fields [{key,label}],submit_label), list/gallery(label,collection,primary_field,secondary_field), button(label,collection,data object), section(label,children), divider, canvas. Every component and nested child requires a unique id."

object WorkspaceTools {
    fun create(database: AtlasDatabase): List<AtlasToolAdapter> = listOf(
        ListWorkspaces(database), CreateWorkspace(database), InspectWorkspace(database), ReplaceDefinition(database), AddRecord(database),
    )
}

private abstract class WorkspaceAdapter(protected val database: AtlasDatabase) : AtlasToolAdapter {
    protected fun active(session: AtlasSession) = session.activeComposableWorkspaceId
    protected fun allowedWorkspace(session: AtlasSession, requested: String?): String? {
        val id = requested?.takeIf(String::isNotBlank) ?: active(session) ?: return null
        val workspace = database.loadComposableWorkspace(id) ?: return null
        return id.takeIf { workspace.realmId == session.workspaceId && workspace.status == WorkspaceStatus.ACTIVE }
    }
}

private class ListWorkspaces(database: AtlasDatabase) : WorkspaceAdapter(database) {
    override val definition = ToolDefinition(
        "workspace_list", "List the user's active composable workspaces and identify which one is currently open.",
        """{"type":"object","properties":{},"additionalProperties":false}""", ToolRisk.READ_ONLY, maxCallsPerTurn = 2,
    )
    override fun evaluate(session: AtlasSession, proposal: ToolCallProposal) = ToolPolicyDecision(true, false, ToolRisk.READ_ONLY, "Read-only workspace index")
    override suspend fun execute(call: AtlasToolCall): ToolExecutionResult {
        val session = database.loadSession(call.sessionId)
        val rows = JSONArray()
        database.listComposableWorkspaces().filter { session == null || it.realmId == session.workspaceId }.forEach { workspace ->
            rows.put(JSONObject().apply {
                put("id", workspace.id); put("name", workspace.name); put("description", workspace.description)
                put("revision_id", workspace.liveRevisionId); put("active", workspace.id == session?.activeComposableWorkspaceId)
            })
        }
        return ToolExecutionResult(JSONObject().put("workspaces", rows).toString())
    }
}

private class CreateWorkspace(database: AtlasDatabase) : WorkspaceAdapter(database) {
    override val definition = ToolDefinition(
        "workspace_create", "Create and open a persistent composable workspace. Supply a complete definition when the requested UI is known. $AUTHORING_GUIDE",
        """{"type":"object","properties":{"name":{"type":"string","maxLength":120},"description":{"type":"string","maxLength":1000},"definition":{"type":"object"}},"required":["name"],"additionalProperties":false}""",
        ToolRisk.SESSION_WRITE, maxCallsPerTurn = 2,
    )
    override fun evaluate(session: AtlasSession, proposal: ToolCallProposal) = ToolPolicyDecision(true, false, ToolRisk.SESSION_WRITE, "Creates a reversible local workspace")
    override suspend fun execute(call: AtlasToolCall): ToolExecutionResult {
        val args = JSONObject(call.argumentsJson)
        val session = database.loadSession(call.sessionId) ?: error("Session is unavailable")
        val name = args.getString("name").trim()
        require(name.isNotBlank()) { "Workspace name is required" }
        val description = args.optString("description").trim()
        val definition = args.optJSONObject("definition")?.toString() ?: WorkspaceDefinitionValidator.starter(name, description)
        val workspace = database.createComposableWorkspace(name, description, definition, session.workspaceId, session.id, call.turnId)
        val sequence = database.activateComposableWorkspace(session.id, workspace.id)
        return ToolExecutionResult(JSONObject().apply {
            put("ok", true); put("workspace_id", workspace.id); put("revision_id", workspace.liveRevisionId); put("navigation_sequence", sequence)
        }.toString())
    }
}

private class InspectWorkspace(database: AtlasDatabase) : WorkspaceAdapter(database) {
    override val definition = ToolDefinition(
        "workspace_inspect", "Inspect the active composable workspace definition and a bounded sample of its records.",
        """{"type":"object","properties":{"workspace_id":{"type":"string"},"collection":{"type":"string"}},"additionalProperties":false}""",
        ToolRisk.READ_ONLY, maxCallsPerTurn = 3,
    )
    override fun evaluate(session: AtlasSession, proposal: ToolCallProposal): ToolPolicyDecision {
        val id = runCatching { JSONObject(proposal.argumentsJson).optString("workspace_id") }.getOrNull()
        return ToolPolicyDecision(allowedWorkspace(session, id) != null, false, ToolRisk.READ_ONLY, "Workspace must be active and belong to this session realm")
    }
    override suspend fun execute(call: AtlasToolCall): ToolExecutionResult {
        val args = JSONObject(call.argumentsJson)
        val session = database.loadSession(call.sessionId) ?: error("Session is unavailable")
        val id = allowedWorkspace(session, args.optString("workspace_id")) ?: error("Workspace is unavailable")
        val workspace = database.loadComposableWorkspace(id) ?: error("Workspace is unavailable")
        val revision = database.loadLiveWorkspaceRevision(id) ?: error("Workspace revision is unavailable")
        val records = JSONArray()
        database.listWorkspaceRecords(id, args.optString("collection").takeIf(String::isNotBlank), 30).forEach { record ->
            records.put(JSONObject().apply { put("id", record.id); put("collection", record.collection); put("data", JSONObject(record.dataJson)) })
        }
        return ToolExecutionResult(JSONObject().apply {
            put("workspace_id", id); put("name", workspace.name); put("revision_id", revision.id)
            put("definition", JSONObject(revision.definitionJson)); put("records", records)
            put("navigation_sequence", session.workspaceNavigationSequence)
        }.toString())
    }
}

private class ReplaceDefinition(database: AtlasDatabase) : WorkspaceAdapter(database) {
    override val definition = ToolDefinition(
        "workspace_replace_definition", "Replace the active workspace definition. The base revision and navigation sequence prevent stale changes. $AUTHORING_GUIDE",
        """{"type":"object","properties":{"workspace_id":{"type":"string"},"base_revision_id":{"type":"string"},"navigation_sequence":{"type":"integer"},"purpose":{"type":"string","maxLength":500},"definition":{"type":"object"}},"required":["workspace_id","base_revision_id","navigation_sequence","purpose","definition"],"additionalProperties":false}""",
        ToolRisk.SESSION_WRITE, maxCallsPerTurn = 3,
    )
    override fun evaluate(session: AtlasSession, proposal: ToolCallProposal): ToolPolicyDecision {
        val args = runCatching { JSONObject(proposal.argumentsJson) }.getOrNull()
        val same = args != null && args.optString("workspace_id") == session.activeComposableWorkspaceId && args.optLong("navigation_sequence", -1) == session.workspaceNavigationSequence
        return ToolPolicyDecision(same, false, ToolRisk.SESSION_WRITE, if (same) "Versioned reversible workspace revision" else "Workspace context changed; inspect the active workspace again")
    }
    override suspend fun execute(call: AtlasToolCall): ToolExecutionResult {
        val args = JSONObject(call.argumentsJson)
        val session = database.loadSession(call.sessionId) ?: error("Session is unavailable")
        val id = args.getString("workspace_id")
        require(id == session.activeComposableWorkspaceId && args.getLong("navigation_sequence") == session.workspaceNavigationSequence) { "Workspace context changed" }
        val revision = database.replaceWorkspaceDefinition(
            id, args.getString("base_revision_id"), args.getJSONObject("definition").toString(), "MODEL", session.id, call.turnId, args.getString("purpose"),
        )
        return ToolExecutionResult(JSONObject().put("ok", true).put("workspace_id", id).put("revision_id", revision.id).put("content_hash", revision.contentHash).toString())
    }
}

private class AddRecord(database: AtlasDatabase) : WorkspaceAdapter(database) {
    override val definition = ToolDefinition(
        "workspace_add_record", "Add one structured record to a collection in the active workspace.",
        """{"type":"object","properties":{"workspace_id":{"type":"string"},"collection":{"type":"string"},"data":{"type":"object"}},"required":["workspace_id","collection","data"],"additionalProperties":false}""",
        ToolRisk.SESSION_WRITE, maxCallsPerTurn = 6,
    )
    override fun evaluate(session: AtlasSession, proposal: ToolCallProposal): ToolPolicyDecision {
        val id = runCatching { JSONObject(proposal.argumentsJson).optString("workspace_id") }.getOrNull()
        val allowed = id == session.activeComposableWorkspaceId && allowedWorkspace(session, id) != null
        return ToolPolicyDecision(allowed, false, ToolRisk.SESSION_WRITE, if (allowed) "Reversible local workspace record" else "Only the active workspace may be changed")
    }
    override suspend fun execute(call: AtlasToolCall): ToolExecutionResult {
        val args = JSONObject(call.argumentsJson)
        val record = database.addWorkspaceRecord(args.getString("workspace_id"), args.getString("collection"), args.getJSONObject("data").toString())
        return ToolExecutionResult(JSONObject().put("ok", true).put("record_id", record.id).toString())
    }
}
