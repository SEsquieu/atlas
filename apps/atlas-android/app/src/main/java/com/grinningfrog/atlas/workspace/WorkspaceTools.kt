package com.grinningfrog.atlas.workspace

import com.grinningfrog.atlas.data.AtlasDatabase
import com.grinningfrog.atlas.model.AtlasSession
import com.grinningfrog.atlas.model.AtlasToolCall
import com.grinningfrog.atlas.model.ToolCallProposal
import com.grinningfrog.atlas.model.ToolDefinition
import com.grinningfrog.atlas.model.ToolRisk
import com.grinningfrog.atlas.runtime.AtlasToolAdapter
import com.grinningfrog.atlas.runtime.ToolPolicyDecision
import com.grinningfrog.atlas.runtime.ToolExecutionResult
import org.json.JSONArray
import org.json.JSONObject

private const val AUTHORING_GUIDE = "Prefer atlas.workspace.v2 for interactive apps. V2 requires format,title,entry_view,state,collections,actions,views, and should include tests. State fields are {type:string|integer|decimal|boolean|timestamp|enum,initial,values?}; collection fields use the same types. Components: text(value), status/metric(value), button(action), input(binding), toggle(binding,action), progress(value,max), list(collection,primary_field,secondary_field), section(children,compact?), row(children), grid(children,columns:1..6), divider. Actions contain bounded steps: set(key,value), increment(key,by), toggle(key), insert(collection,data), navigate(view), sequence(steps), branch(if,then,else), stop; enabled_if and disabled_message guard actions. Root invariants are {name?,assert,message?}. Expressions are literals, {var:'state.key'}, {var:'count.collection'}, or {op:eq|ne|gt|gte|lt|lte|and|or|not|add|subtract|multiply|divide|concat|if|count,args:[...]}. Tests are {name,action|actions,initial_state?,assert}. Validate against the active workspace before replacing. V1 remains supported for static dashboards."

object WorkspaceTools {
    fun create(database: AtlasDatabase): List<AtlasToolAdapter> = listOf(
        ListWorkspaces(database), ValidateDefinition(database), CreateWorkspace(database), InspectWorkspace(database), ReplaceDefinition(database), AddRecord(database),
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
        ToolRisk.SESSION_WRITE, maxCallsPerTurn = 4,
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

private class ValidateDefinition(database: AtlasDatabase) : WorkspaceAdapter(database) {
    override val definition = ToolDefinition(
        "workspace_validate_definition", "Validate and deterministically simulate a proposed workspace definition. When workspace_id is supplied, also migrate and test against its durable state. Returns precise schema/test/migration failures. $AUTHORING_GUIDE",
        """{"type":"object","properties":{"definition":{"type":"object"},"workspace_id":{"type":"string"}},"required":["definition"],"additionalProperties":false}""",
        ToolRisk.READ_ONLY, maxCallsPerTurn = 6,
    )
    override fun evaluate(session: AtlasSession, proposal: ToolCallProposal) = ToolPolicyDecision(true, false, ToolRisk.READ_ONLY, "Pure bounded workspace validation")
    override suspend fun execute(call: AtlasToolCall): ToolExecutionResult {
        val args = JSONObject(call.argumentsJson)
        val proposed = args.getJSONObject("definition")
        val validation = WorkspaceDefinitionValidator.validate(proposed.toString())
        val session = database.loadSession(call.sessionId)
        val requestedWorkspace = args.optString("workspace_id").takeIf(String::isNotBlank)
        val workspaceId = requestedWorkspace?.let { allowedWorkspace(session ?: error("Session is unavailable"), it) }
        val persisted = workspaceId?.let(database::loadWorkspacePersistedState)
        val migration = if (validation.valid && proposed.optString("format") == WorkspaceRuntimeV2.FORMAT && workspaceId != null) WorkspaceRuntimeV2.migrateState(proposed, persisted) else null
        val simulation = if (validation.valid && proposed.optString("format") == WorkspaceRuntimeV2.FORMAT) WorkspaceRuntimeV2.simulate(proposed, persisted) else null
        return ToolExecutionResult(JSONObject().apply {
            put("valid", validation.valid); put("errors", JSONArray(validation.errors)); put("format", proposed.optString("format"))
            simulation?.let { put("simulation_passed", it.passed); put("tests_run", it.testsRun); put("test_failures", JSONArray(it.failures)) }
            migration?.let { put("migration", JSONObject().put("added", JSONArray(it.addedKeys)).put("reset", JSONArray(it.resetKeys)).put("removed", JSONArray(it.removedKeys))) }
            put("ready_to_apply", validation.valid && (simulation?.passed != false))
        }.toString())
    }
}

private class InspectWorkspace(database: AtlasDatabase) : WorkspaceAdapter(database) {
    override val definition = ToolDefinition(
        "workspace_inspect", "Inspect an active workspace. Compact mode is optimized for diagnosis; request full mode only when editing the definition. Records are omitted unless explicitly requested.",
        """{"type":"object","properties":{"workspace_id":{"type":"string"},"mode":{"type":"string","enum":["compact","full"]},"collection":{"type":"string"},"include_records":{"type":"boolean"},"record_limit":{"type":"integer","minimum":1,"maximum":50}},"additionalProperties":false}""",
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
        val definition = JSONObject(revision.definitionJson)
        val records = JSONArray()
        if (args.optBoolean("include_records")) database.listWorkspaceRecords(id, args.optString("collection").takeIf(String::isNotBlank), args.optInt("record_limit", 10).coerceIn(1, 50)).forEach { record ->
            records.put(JSONObject().apply { put("id", record.id); put("collection", record.collection); put("data", JSONObject(record.dataJson)) })
        }
        return ToolExecutionResult(JSONObject().apply {
            put("workspace_id", id); put("name", workspace.name); put("revision_id", revision.id)
            if (args.optString("mode", "compact") == "full") put("definition", definition)
            else put("definition_summary", JSONObject().apply {
                put("format", definition.optString("format")); put("title", definition.optString("title")); put("entry_view", definition.optString("entry_view"))
                put("state_keys", JSONArray(definition.optJSONObject("state")?.keys()?.asSequence()?.toList().orEmpty()))
                put("collections", JSONArray(definition.optJSONObject("collections")?.keys()?.asSequence()?.toList().orEmpty()))
                put("actions", JSONArray(definition.optJSONObject("actions")?.keys()?.asSequence()?.toList().orEmpty()))
                put("views", JSONArray((0 until (definition.optJSONArray("views")?.length() ?: 0)).mapNotNull { definition.optJSONArray("views")?.optJSONObject(it)?.optString("id") }))
            })
            if (args.optBoolean("include_records")) put("records", records)
            if (definition.optString("format") == WorkspaceRuntimeV2.FORMAT) put("runtime_state", database.loadWorkspaceRuntimeState(id, definition))
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
        val definition = args.getJSONObject("definition")
        val migration = if (definition.optString("format") == WorkspaceRuntimeV2.FORMAT) {
            WorkspaceRuntimeV2.migrateState(definition, database.loadWorkspacePersistedState(id))
        } else null
        val revision = database.replaceWorkspaceDefinition(
            id, args.getString("base_revision_id"), definition.toString(), "MODEL", session.id, call.turnId, args.getString("purpose"),
        )
        return ToolExecutionResult(JSONObject().put("ok", true).put("workspace_id", id).put("revision_id", revision.id).put("content_hash", revision.contentHash).apply {
            migration?.let { put("migration", JSONObject().put("added", JSONArray(it.addedKeys)).put("reset", JSONArray(it.resetKeys)).put("removed", JSONArray(it.removedKeys))) }
        }.toString())
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
