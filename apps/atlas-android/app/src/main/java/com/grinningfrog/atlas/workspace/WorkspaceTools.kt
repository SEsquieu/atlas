package com.grinningfrog.atlas.workspace

import com.grinningfrog.atlas.data.AtlasDatabase
import com.grinningfrog.atlas.media.MediaRepository
import com.grinningfrog.atlas.model.VisualObservation
import com.grinningfrog.atlas.model.ObservationTiming
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

private const val AUTHORING_GUIDE = "Prefer atlas.workspace.v2 for interactive apps. V2 requires format,title,entry_view,state,collections,actions,views, and should include tests. State fields are {type:string|integer|decimal|boolean|timestamp|enum,initial,values?}; collection fields use the same types. All components require a unique id. views is an array of {id,title,components}; collection schemas are {fields:{...}}. Text supports text_size:small|normal|large and bold:boolean; metric supports text_size; section supports show_label:boolean. Markdown is plain text. For small edits use workspace_patch_definition instead of rewriting the full definition. Components: text(value), status/metric(value), button(action), input(binding), toggle(binding,action), progress(value,max), list(collection,primary_field,secondary_field,checked_field?,row_action?,row_action_label?,remove_action?,remove_label?,sort_by?,sort_direction?,limit?), section(children,compact?), row(children), grid(children,columns:1..6), divider. Input and toggle bindings are bare state keys (binding:item_name, not state.item_name). Every action step is an object with type, for example {type:set,key:item_name,value:bread}; never use step or a nested set object. Actions contain bounded steps: set(key,value), increment(key,by), toggle(key), insert(collection,data), update_record(collection,record_id,data), toggle_record(collection,record_id,field), remove_record(collection,record_id), navigate(view), sequence(steps), branch(if,then,else), stop; enabled_if and disabled_message guard actions. Root invariants are {name?,assert,message?}. Expressions are literals, {var:'state.key'}, {var:'count.collection'}, {var:'row.id'} for the stable selected record ID and {var:'row.field'} for its data (row variables require row context), or {op:eq|ne|gt|gte|lt|lte|and|or|not|add|subtract|multiply|divide|concat|if|count,args:[...]}. A list checked_field must be a boolean collection field and requires row_action. Wire row_action to a toggle_record step with record_id:{var:row.id}; row controls and conversational workspace_update_record edits both persist validated record changes. Chat can add items with workspace_add_record; no separate input binding is required. Tests are {name,action|actions,initial_state?,initial_records?,row_collection?,row_id?,assert}; initial_records maps collections to arrays of schema-valid data with unique _id metadata for testing existing records. Use row_id and row_collection to select a fixture record. Example: {type:toggle_record,collection:items,record_id:{var:row.id},field:found}. Lists default to creation-time ascending with stable ID tie-breaking; sort_by names a collection field and sort_direction is ascending or descending. Unsupported component properties are rejected. remove_action can reference a remove_record action and the UI provides Undo. New required collection fields need a valid initial default; revision preview validates and backfills actual saved records atomically. Migration reports distinguish state migration from record_migration. Use workspace_render_diagnostics for ordering/errors and workspace_capture_view for UI pixels; capture_current_view is the physical camera. Validate against the active workspace before replacing. V1 remains supported for static dashboards."

object WorkspaceTools {
    fun create(database: AtlasDatabase, mediaRepository: MediaRepository? = null): List<AtlasToolAdapter> = listOf(
        ListWorkspaces(database), ValidateDefinition(database), CreateWorkspace(database), InspectWorkspace(database), ReplaceDefinition(database), PatchDefinition(database), AddRecord(database), UpdateRecord(database), DeleteRecord(database), RestoreRecord(database), RenderDiagnostics(database),
    ) + if (mediaRepository == null) emptyList() else listOf(CaptureWorkspace(database, mediaRepository))
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
        val proposed = JSONObject(definition)
        val simulation = if (proposed.optString("format") == WorkspaceRuntimeV2.FORMAT) WorkspaceRuntimeV2.simulate(proposed) else null
        require(simulation?.passed != false) { simulation?.failures.orEmpty().joinToString("; ") }
        val workspace = database.createComposableWorkspace(name, description, definition, session.workspaceId, session.id, call.turnId)
        val sequence = database.activateComposableWorkspace(session.id, workspace.id)
        return ToolExecutionResult(JSONObject().apply {
            put("ok", true); put("workspace_id", workspace.id); put("revision_id", workspace.liveRevisionId); put("navigation_sequence", sequence); put("valid", true)
            simulation?.let { put("simulation_passed", it.passed); put("tests_run", it.testsRun); put("test_failures", JSONArray(it.failures)) }
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
        require(requestedWorkspace == null || workspaceId != null) { "Workspace is unavailable to this session" }
        val persisted = workspaceId?.let(database::loadWorkspacePersistedState)
        val recordMigration = if (validation.valid && proposed.optString("format") == WorkspaceRuntimeV2.FORMAT && workspaceId != null) runCatching { database.workspaceRecordMigration(workspaceId, proposed).second } else null
        val migration = if (validation.valid && proposed.optString("format") == WorkspaceRuntimeV2.FORMAT && workspaceId != null) WorkspaceRuntimeV2.migrateState(proposed, persisted) else null
        val simulation = if (validation.valid && proposed.optString("format") == WorkspaceRuntimeV2.FORMAT) WorkspaceRuntimeV2.simulate(proposed, persisted) else null
        return ToolExecutionResult(JSONObject().apply {
            put("valid", validation.valid); put("errors", JSONArray(validation.errors)); put("format", proposed.optString("format"))
            simulation?.let { put("simulation_passed", it.passed); put("tests_run", it.testsRun); put("test_failures", JSONArray(it.failures)) }
            migration?.let { put("migration", JSONObject().put("added", JSONArray(it.addedKeys)).put("reset", JSONArray(it.resetKeys)).put("removed", JSONArray(it.removedKeys))) }
            recordMigration?.onSuccess { put("record_migration", it) }?.onFailure { put("record_migration_errors", JSONArray().put(it.message)) }
            put("ready_to_apply", validation.valid && (simulation?.passed != false) && recordMigration?.isFailure != true)
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
            put("pending_undo", JSONArray().apply {
                val pending = database.pendingWorkspaceUndo(id)
                for (i in 0 until pending.length()) {
                    val row = pending.getJSONObject(i)
                    put(JSONObject().put("undo_token", row.getString("undo_token")).put("record_id", row.getString("record_id")).put("collection", row.getString("collection_name")))
                }
            })
        }.toString())
    }
}

private open class ReplaceDefinition(database: AtlasDatabase) : WorkspaceAdapter(database) {
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
        val validation = WorkspaceDefinitionValidator.validate(definition.toString())
        require(validation.valid) { validation.errors.joinToString("; ") }
        val simulation = if (definition.optString("format") == WorkspaceRuntimeV2.FORMAT) WorkspaceRuntimeV2.simulate(definition, database.loadWorkspacePersistedState(id)) else null
        require(simulation?.passed != false) { simulation?.failures.orEmpty().joinToString("; ") }
        val recordMigration = if (definition.optString("format") == WorkspaceRuntimeV2.FORMAT) database.workspaceRecordMigration(id, definition).second else null
        val revision = database.replaceWorkspaceDefinition(
            id, args.getString("base_revision_id"), definition.toString(), "MODEL", session.id, call.turnId, args.getString("purpose"),
        )
        return ToolExecutionResult(JSONObject().put("ok", true).put("workspace_id", id).put("revision_id", revision.id).put("content_hash", revision.contentHash).apply {
            recordMigration?.let { put("record_migration", it) }
            put("valid", true)
            simulation?.let { put("simulation_passed", it.passed); put("tests_run", it.testsRun); put("test_failures", JSONArray(it.failures)) }
            migration?.let { put("migration", JSONObject().put("added", JSONArray(it.addedKeys)).put("reset", JSONArray(it.resetKeys)).put("removed", JSONArray(it.removedKeys))) }
        }.toString())
    }
}

private class PatchDefinition(database: AtlasDatabase) : ReplaceDefinition(database) {
    override val definition = ToolDefinition(
        "workspace_patch_definition", "Replace selected components by id or actions by name, preserving all other content. Changes are bounded, validated and simulated before a versioned commit. Component replacement must retain its id. $AUTHORING_GUIDE",
        """{"type":"object","properties":{"workspace_id":{"type":"string"},"base_revision_id":{"type":"string"},"navigation_sequence":{"type":"integer"},"purpose":{"type":"string","maxLength":500},"changes":{"type":"array","maxItems":32,"items":{"type":"object","properties":{"kind":{"type":"string","enum":["component","action"]},"id":{"type":"string"},"replacement":{"type":"object"}},"required":["kind","id","replacement"],"additionalProperties":false}}},"required":["workspace_id","base_revision_id","navigation_sequence","purpose","changes"],"additionalProperties":false}""",
        ToolRisk.SESSION_WRITE, maxCallsPerTurn = 3,
    )
    override suspend fun execute(call: AtlasToolCall): ToolExecutionResult {
        val args = JSONObject(call.argumentsJson)
        val revision = database.loadLiveWorkspaceRevision(args.getString("workspace_id")) ?: error("Workspace revision unavailable")
        require(revision.id == args.getString("base_revision_id")) { "Workspace revision changed; inspect again" }
        val patched = WorkspaceDefinitionPatch.apply(JSONObject(revision.definitionJson), args.getJSONArray("changes"))
        args.put("definition", patched)
        return super.execute(call.copy(argumentsJson = args.toString()))
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
        val session = database.loadSession(call.sessionId) ?: error("Session is unavailable")
        require(args.getString("workspace_id") == session.activeComposableWorkspaceId && allowedWorkspace(session, args.getString("workspace_id")) != null) { "Workspace context changed; inspect again" }
        val revision = database.loadLiveWorkspaceRevision(args.getString("workspace_id")) ?: error("Workspace is unavailable")
        val root = JSONObject(revision.definitionJson)
        require(args.getString("collection") != "AtlasRuntimeState") { "Runtime state is not a user collection" }
        if (root.optString("format") == WorkspaceRuntimeV2.FORMAT) WorkspaceRuntimeV2.validateRecord(root, args.getString("collection"), args.getJSONObject("data"))
        val record = database.addWorkspaceRecord(args.getString("workspace_id"), args.getString("collection"), args.getJSONObject("data").toString())
        return ToolExecutionResult(JSONObject().put("ok", true).put("record_id", record.id).toString())
    }
}

private class UpdateRecord(database: AtlasDatabase) : WorkspaceAdapter(database) {
    override val definition = ToolDefinition(
        "workspace_update_record", "Patch fields on exactly one existing record in the active v2 workspace, including conversational edits. First inspect records to obtain the stable record_id, base_revision_id, navigation_sequence and full expected_data. Preserve unspecified fields. Rejects stale context, changed records, invalid types and failed invariants; returns committed data. Do not guess an ID from an item name.",
        """{"type":"object","properties":{"workspace_id":{"type":"string"},"base_revision_id":{"type":"string"},"navigation_sequence":{"type":"integer"},"collection":{"type":"string"},"record_id":{"type":"string"},"expected_data":{"type":"object"},"data":{"type":"object"}},"required":["workspace_id","base_revision_id","navigation_sequence","collection","record_id","expected_data","data"],"additionalProperties":false}""",
        ToolRisk.SESSION_WRITE, maxCallsPerTurn = 6,
    )
    override fun evaluate(session: AtlasSession, proposal: ToolCallProposal): ToolPolicyDecision {
        val args = runCatching { JSONObject(proposal.argumentsJson) }.getOrNull()
        val allowed = args != null && args.optString("workspace_id") == session.activeComposableWorkspaceId &&
            args.optLong("navigation_sequence", -1) == session.workspaceNavigationSequence && allowedWorkspace(session, args.optString("workspace_id")) != null
        return ToolPolicyDecision(allowed, false, ToolRisk.SESSION_WRITE, if (allowed) "Validated reversible record update" else "Workspace context changed; inspect again")
    }
    override suspend fun execute(call: AtlasToolCall): ToolExecutionResult {
        val args = JSONObject(call.argumentsJson)
        val session = database.loadSession(call.sessionId) ?: error("Session is unavailable")
        require(evaluate(session, ToolCallProposal(call.id, call.name, call.argumentsJson)).allowed) { "Workspace context changed; inspect again" }
        val result = database.updateWorkspaceRecord(args.getString("workspace_id"), args.getString("base_revision_id"), args.getString("collection"), args.getString("record_id"), args.getJSONObject("data"), args.getJSONObject("expected_data"), sessionId = session.id, navigationSequence = args.getLong("navigation_sequence"))
        val mutation = result.mutations.filterIsInstance<WorkspaceMutation.UpdateRecord>().single()
        return ToolExecutionResult(JSONObject().put("ok", true).put("workspace_id", args.getString("workspace_id")).put("record_id", mutation.recordId).put("collection", mutation.collection).put("data", mutation.data).toString())
    }
}

private open class DeleteRecord(database: AtlasDatabase) : WorkspaceAdapter(database) {
    override val definition = ToolDefinition(
        "workspace_delete_record", "Remove exactly one inspected record from the active v2 workspace. Require full expected_data and current revision/navigation. Returns undo_token; workspace_restore_record can restore it. Last 50 deletions per workspace remain undoable across app restarts.",
        """{"type":"object","properties":{"workspace_id":{"type":"string"},"base_revision_id":{"type":"string"},"navigation_sequence":{"type":"integer"},"collection":{"type":"string"},"record_id":{"type":"string"},"expected_data":{"type":"object"}},"required":["workspace_id","base_revision_id","navigation_sequence","collection","record_id","expected_data"],"additionalProperties":false}""",
        ToolRisk.SESSION_WRITE, maxCallsPerTurn = 6,
    )
    override fun evaluate(session: AtlasSession, proposal: ToolCallProposal): ToolPolicyDecision {
        val args = runCatching { JSONObject(proposal.argumentsJson) }.getOrNull()
        val allowed = args != null && args.optString("workspace_id") == session.activeComposableWorkspaceId && args.optLong("navigation_sequence", -1) == session.workspaceNavigationSequence && allowedWorkspace(session, args.optString("workspace_id")) != null
        return ToolPolicyDecision(allowed, false, ToolRisk.SESSION_WRITE, if (allowed) "Validated reversible record operation" else "Workspace context changed; inspect again")
    }
    override suspend fun execute(call: AtlasToolCall): ToolExecutionResult {
        val args = JSONObject(call.argumentsJson)
        val result = database.deleteWorkspaceRecord(args.getString("workspace_id"), args.getString("base_revision_id"), args.getString("collection"), args.getString("record_id"), args.getJSONObject("expected_data"), call.sessionId, args.getLong("navigation_sequence"))
        return ToolExecutionResult(JSONObject().put("ok", true).put("record_id", args.getString("record_id")).put("undo_token", result.undoTokens.single()).toString())
    }
}

private class RestoreRecord(database: AtlasDatabase) : DeleteRecord(database) {
    override val definition = ToolDefinition(
        "workspace_restore_record", "Undo a deletion by undo_token in the active workspace, preserving its stable ID and creation order. Inspect for current revision/navigation and pending undo tokens. Fails safely if schema changed incompatibly, invariants fail, or token was used.",
        """{"type":"object","properties":{"workspace_id":{"type":"string"},"base_revision_id":{"type":"string"},"navigation_sequence":{"type":"integer"},"undo_token":{"type":"string"}},"required":["workspace_id","base_revision_id","navigation_sequence","undo_token"],"additionalProperties":false}""",
        ToolRisk.SESSION_WRITE, maxCallsPerTurn = 6,
    )
    override suspend fun execute(call: AtlasToolCall): ToolExecutionResult {
        val args = JSONObject(call.argumentsJson)
        val result = database.restoreWorkspaceRecord(args.getString("workspace_id"), args.getString("base_revision_id"), args.getString("undo_token"), call.sessionId, args.getLong("navigation_sequence"))
        val record = result.mutations.filterIsInstance<WorkspaceMutation.InsertRecord>().single()
        return ToolExecutionResult(JSONObject().put("ok", true).put("record_id", record.recordId).put("collection", record.collection).put("data", record.data).toString())
    }
}

private open class RenderDiagnostics(database: AtlasDatabase) : WorkspaceAdapter(database) {
    override val supportsLocalDiagnostics = true
    override val definition = ToolDefinition(
        "workspace_render_diagnostics", "Read the active workspace's rendered revision, logical list record order, visible component IDs, unsupported properties, UI errors and migration compatibility. Distinguishes an actually drawn viewport snapshot from the expected projection of current data. Available during discovery; does not mutate workspace or access external services.",
        """{"type":"object","properties":{"workspace_id":{"type":"string"}},"additionalProperties":false}""", ToolRisk.READ_ONLY, maxCallsPerTurn = 3,
    )
    override fun evaluate(session: AtlasSession, proposal: ToolCallProposal): ToolPolicyDecision {
        val args = runCatching { JSONObject(proposal.argumentsJson) }.getOrNull()
        val id = args?.optString("workspace_id")?.takeIf(String::isNotBlank) ?: session.activeComposableWorkspaceId
        return ToolPolicyDecision(id != null && id == session.activeComposableWorkspaceId && allowedWorkspace(session, id) != null, false, ToolRisk.READ_ONLY, "Scoped diagnostics of the active local workspace")
    }
    protected fun target(call: AtlasToolCall): Pair<AtlasSession, String> {
        val session = database.loadSession(call.sessionId) ?: error("Session is unavailable")
        require(evaluate(session, ToolCallProposal(call.id, call.name, call.argumentsJson)).allowed) { "Workspace context changed; inspect again" }
        return session to session.activeComposableWorkspaceId!!
    }
    override suspend fun execute(call: AtlasToolCall): ToolExecutionResult {
        val (session, id) = target(call)
        val revision = database.loadLiveWorkspaceRevision(id) ?: error("Workspace is unavailable")
        val root = JSONObject(revision.definitionJson)
        require(root.optString("format") == WorkspaceRuntimeV2.FORMAT) { "Render diagnostics require atlas.workspace.v2" }
        val records = buildMap<String, List<JSONObject>> {
            root.optJSONObject("collections")?.keys()?.forEach { collection -> put(collection, database.listWorkspaceRecords(id, collection, 5_000).map { JSONObject(it.dataJson).put("_id", it.id) }) }
        }
        val validation = WorkspaceRuntimeV2.validate(root)
        val expected = runCatching { WorkspaceRecordProjection.diagnostics(root, database.loadWorkspaceRuntimeState(id, root), records) }
        val migration = runCatching { database.workspaceRecordMigration(id, root).second }
        val rendered = WorkspaceRenderSurface.diagnostics(id, revision.id)
        val errors = database.loadRecentEvents(session.id, 200).filter { it.type in setOf("workspace.action_rejected", "workspace.render_failed") && JSONObject(it.dataJson).optString("workspace_id") == id }.takeLast(10)
        return ToolExecutionResult(JSONObject().put("workspace_id", id).put("revision_id", revision.id).put("navigation_sequence", session.workspaceNavigationSequence)
            .put("render_available", rendered != null).put("rendered", rendered).put("expected_projection", expected.getOrNull()).put("projection_error", expected.exceptionOrNull()?.message)
            .put("validation_errors", JSONArray(validation.errors)).put("record_migration", migration.getOrNull()).put("record_migration_error", migration.exceptionOrNull()?.message)
            .put("ui_errors", JSONArray(errors.map { JSONObject(it.dataJson).put("at_ms", it.atMs) })).toString())
    }
}

private class CaptureWorkspace(database: AtlasDatabase, private val media: MediaRepository) : RenderDiagnostics(database) {
    override val definition = ToolDefinition(
        "workspace_capture_view", "Capture the visible Atlas v2 workspace viewport as UI pixels for visual diagnosis. The workspace panel must be open and drawn at the current revision. Does not capture the physical camera, other apps, system screen, or offscreen content. Image attaches to the next inference step and remains separate from physical observations. Available as scoped read-only local diagnostics during discovery.",
        """{"type":"object","properties":{"workspace_id":{"type":"string"}},"additionalProperties":false}""", ToolRisk.READ_ONLY, maxCallsPerTurn = 2,
    )
    override fun evaluate(session: AtlasSession, proposal: ToolCallProposal): ToolPolicyDecision {
        val decision = super.evaluate(session, proposal)
        return decision.copy(allowed = decision.allowed && session.permissions.observe, reason = "Workspace pixels follow session observation policy; no camera permission required")
    }
    override suspend fun execute(call: AtlasToolCall): ToolExecutionResult {
        val (session, id) = target(call)
        val revision = database.loadLiveWorkspaceRevision(id) ?: error("Workspace is unavailable")
        val (bitmap, rendered) = WorkspaceRenderSurface.capture(id, revision.id)
        val ref = media.ingestWorkspaceBitmap(bitmap, session.id)
        val current = database.loadSession(session.id) ?: error("Session is unavailable")
        if (!current.permissions.observe || current.activeComposableWorkspaceId != id || current.workspaceNavigationSequence != session.workspaceNavigationSequence || database.loadLiveWorkspaceRevision(id)?.id != revision.id) {
            media.deleteStorageKeys(listOf(ref.storageKey)); error("Workspace changed during screenshot capture; retry")
        }
        val observation = VisualObservation(sessionId = session.id, media = ref, observedAtMs = rendered.getLong("drawn_at_ms"), availableAtMs = System.currentTimeMillis(),
            timing = ObservationTiming(ref.processingMs, 0, ref.processingMs), summary = "Atlas workspace UI viewport; workspace=$id; revision=${revision.id}. Not a physical camera observation.")
        database.saveObservation(observation)
        database.appendEvent(session.id, "workspace.capture_completed", JSONObject().put("workspace_id", id).put("revision_id", revision.id).put("media_id", ref.id).put("observation_id", observation.id).put("rendered", rendered))
        return ToolExecutionResult(JSONObject().put("ok", true).put("source", "workspace_ui").put("observation_id", observation.id).put("width", ref.width).put("height", ref.height).put("rendered", rendered).toString(), observation)
    }
}
