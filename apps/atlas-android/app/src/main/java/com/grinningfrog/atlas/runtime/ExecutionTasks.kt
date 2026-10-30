package com.grinningfrog.atlas.runtime

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.grinningfrog.atlas.data.AtlasDatabase
import com.grinningfrog.atlas.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

enum class ExecutionState { READY, RUNNING, WAITING, PAUSED, COMPLETED, CANCELLED, FAILED }
enum class SliceOutcome { YIELDED, RESPONDED, WAITING_USER, WAITING_PERMISSION, WAITING_PROVIDER, BLOCKED }
data class ExecutionBudget(val inputTokens: Long = 10_000_000, val outputTokens: Long = 1_000_000, val modelSteps: Long = 10_000,
    val toolCalls: Long = 10_000, val activeMs: Long = 12 * 60 * 60_000L) {
    init { require(inputTokens > 0 && outputTokens > 0 && modelSteps > 0 && toolCalls > 0 && activeMs > 0) }
    fun json() = JSONObject().put("input_tokens", inputTokens).put("output_tokens", outputTokens).put("model_steps", modelSteps).put("tool_calls", toolCalls).put("active_ms", activeMs)
    companion object { fun from(j: JSONObject) = ExecutionBudget(j.optLong("input_tokens", 10_000_000), j.optLong("output_tokens", 1_000_000), j.optLong("model_steps", 10_000), j.optLong("tool_calls", 10_000), j.optLong("active_ms", 12 * 60 * 60_000L)) }
}
data class ExecutionTask(val id: String, val sessionId: String, val state: ExecutionState, val generation: Long, val owner: String?, val leaseUntil: Long,
    val nextRunAt: Long, val document: JSONObject) {
    val objective: String get() = document.getString("objective")
    val waitReason: String get() = document.optString("wait_reason")
    val usage: JSONObject get() = document.getJSONObject("usage")
}
data class ExecutionLease(val taskId: String, val owner: String, val generation: Long)

/** SQLite is the execution authority. A lease fences stale runners, including after restart. */
class ExecutionTaskStore(private val database: AtlasDatabase, private val now: () -> Long = System::currentTimeMillis) {
    companion object {
        const val LEASE_MS = 15 * 60_000L
        fun createTables(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS execution_tasks(task_id TEXT PRIMARY KEY,session_id TEXT NOT NULL,state TEXT NOT NULL,generation INTEGER NOT NULL,lease_owner TEXT,lease_until INTEGER NOT NULL,next_run_at INTEGER NOT NULL,document_json TEXT NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS execution_tasks_session ON execution_tasks(session_id,state)")
            db.execSQL("CREATE TABLE IF NOT EXISTS execution_slices(turn_id TEXT PRIMARY KEY,task_id TEXT NOT NULL,ordinal INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS execution_slices_task ON execution_slices(task_id,ordinal)")
            db.execSQL("CREATE TABLE IF NOT EXISTS local_operation_receipts(operation_id TEXT PRIMARY KEY,session_id TEXT NOT NULL,arguments_hash TEXT NOT NULL,result_json TEXT NOT NULL)")
        }
        private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    private fun <T> atomic(block: () -> T): T = database.writeTransaction { block() }
    fun pinRuntime(taskId: String, selected: String): String = atomic {
        val task = requireNotNull(get(taskId))
        val pinned = task.document.optString("runtime_core_id").takeIf { it.isNotBlank() }
        if (pinned != null) return@atomic pinned
        task.document.put("runtime_core_id", selected)
        save(task)
        event(task, "execution.runtime_pinned", JSONObject().put("runtime_core_id", selected))
        selected
    }

    fun get(id: String): ExecutionTask? = database.readableDatabase.rawQuery("SELECT task_id,session_id,state,generation,lease_owner,lease_until,next_run_at,document_json FROM execution_tasks WHERE task_id=?", arrayOf(id)).use { c ->
        if (!c.moveToFirst()) null else ExecutionTask(c.getString(0), c.getString(1), ExecutionState.valueOf(c.getString(2)), c.getLong(3), if(c.isNull(4)) null else c.getString(4), c.getLong(5), c.getLong(6), JSONObject(c.getString(7)))
    }
    fun forTurn(turnId: String): ExecutionTask? = database.readableDatabase.rawQuery("SELECT task_id FROM execution_slices WHERE turn_id=?", arrayOf(turnId)).use { c -> if(c.moveToFirst()) get(c.getString(0)) else null }
    /** Resolve only from this durable task, never the currently browsed workspace. */
    internal fun bindWorkspaceArguments(turnId: String, proposal: ToolCallProposal): ToolCallProposal {
        if (!WorkspaceArgumentBinding.supports(proposal.name)) return proposal
        val task = forTurn(turnId) ?: return proposal
        val binding = task.document.optJSONObject("workspace_target") ?: return proposal
        val workspace = binding.optString("workspace_id")
        val candidate = calls(task.id).lastOrNull { call ->
            call.name == "workspace_package_stage" && call.status == ToolCallStatus.COMPLETED &&
                runCatching { JSONObject(call.argumentsJson).optString("workspace_id") == workspace &&
                    JSONObject(call.resultJson.orEmpty()).optString("candidate_id").isNotBlank() }.getOrDefault(false)
        }?.let { JSONObject(it.resultJson!!).getString("candidate_id") }
        val taskTurns = turns(task.id).toSet()
        val user = database.loadMessages(task.sessionId, limit = 10000).lastOrNull { it.role == MessageRole.USER && it.turnId in taskTurns }
        return WorkspaceArgumentBinding.bind(proposal, workspace, binding.optLong("sequence"), candidate, user?.id, user?.content)
    }

    /** A task captures its default target once; browsing never changes it. */
    fun workspaceSession(session: AtlasSession, turnId: String?): AtlasSession {
        val task = turnId?.let(::forTurn) ?: return session
        require(task.sessionId == session.id)
        val binding = task.document.optJSONObject("workspace_target") ?: return session
        return session.copy(activeComposableWorkspaceId = binding.optString("workspace_id").takeIf { it.isNotBlank() && it != "null" },
            workspaceNavigationSequence = binding.optLong("sequence"))
    }
    fun bindWorkspace(turnId: String, workspaceId: String, delivery: String? = null, criteria: JSONArray? = null, requirePixels: Boolean = false, requirePersistence: Boolean = false): Long = atomic {
        val task = forTurn(turnId) ?: error("Workspace targeting requires a durable task")
        require(task.state == ExecutionState.RUNNING) { "Task is not running" }
        val session = database.loadSession(task.sessionId) ?: error("Session unavailable")
        val workspace = database.loadComposableWorkspace(workspaceId) ?: error("Workspace unavailable")
        require(workspace.realmId == session.workspaceId && workspace.status == com.grinningfrog.atlas.workspace.WorkspaceStatus.ACTIVE) { "Workspace outside task realm or deleted" }
        require(delivery == null || delivery in setOf("activation","draft","inspect")) { "Unknown delivery requirement" }
        val priorRequirement=task.document.optJSONObject("completion_requirement")
        require(delivery == null || priorRequirement?.optString("workspace_id") != workspaceId ||
            priorRequirement?.optString("delivery") != "activation" || delivery == "activation") { "An activation requirement cannot be weakened within the same task" }
        if(delivery!=null) task.document.put("completion_requirement",(priorRequirement?.takeIf {it.optString("workspace_id")==workspaceId} ?: JSONObject().put("workspace_id",workspaceId)).put("delivery",delivery))
        if(criteria!=null || requirePixels || requirePersistence) {
            val requirement=task.document.optJSONObject("completion_requirement")?.takeIf { it.optString("workspace_id")==workspaceId }
                ?: JSONObject().put("workspace_id",workspaceId).also { task.document.put("completion_requirement",it) }
            val retained=task.document.optJSONObject("acceptance")?.takeIf {it.optString("workspace_id")==workspaceId}?.optJSONArray("criteria") ?: JSONArray()
            val names=(0 until retained.length()).map(retained::getString).toMutableSet()
            criteria?.let { require(it.length()<=16);for(i in 0 until it.length()) { val name=it.getString(i).trim();require(name.length in 1..200);names.add(name) } }
            require(names.size<=16)
            task.document.put("acceptance",JSONObject().put("workspace_id",workspaceId).put("criteria",JSONArray(names.toList())))
            if(requirePixels)requirement.put("require_pixels",true)
            if(requirePersistence)requirement.put("require_persistence",true)
        }
        val old = task.document.optJSONObject("workspace_target")
        val sequence = if (old?.optString("workspace_id") == workspaceId) (old?.optLong("sequence") ?: 0) else (old?.optLong("sequence") ?: 0) + 1
        task.document.put("workspace_target", JSONObject().put("workspace_id", workspaceId).put("sequence", sequence))
        save(task); event(task, "execution.workspace_target", task.document.getJSONObject("workspace_target"))
        sequence
    }
    fun list(sessionId: String): List<ExecutionTask> = database.readableDatabase.rawQuery("SELECT task_id FROM execution_tasks WHERE session_id=? ORDER BY rowid DESC LIMIT 100", arrayOf(sessionId)).use { c -> buildList { while(c.moveToNext()) get(c.getString(0))?.let(::add) } }
    // Ownership and recovery must never depend on the bounded UI history projection.
    private fun running(sessionId: String): List<ExecutionTask> = database.readableDatabase.rawQuery("SELECT task_id FROM execution_tasks WHERE session_id=? AND state='RUNNING'",arrayOf(sessionId)).use { c -> buildList { while(c.moveToNext()) get(c.getString(0))?.let(::add) } }
    fun turns(taskId: String): List<String> = database.readableDatabase.rawQuery("SELECT turn_id FROM execution_slices WHERE task_id=? ORDER BY ordinal", arrayOf(taskId)).use { c -> buildList { while(c.moveToNext()) add(c.getString(0)) } }
    fun calls(taskId: String): List<AtlasToolCall> = database.loadExecutionToolCalls(taskId, 10000)
    fun pending(taskId: String): List<AtlasToolCall> = database.loadExecutionToolCalls(taskId, 10000, true)
    private fun save(task: ExecutionTask) {
        database.writableDatabase.update("execution_tasks", ContentValues().apply {
            put("state", task.state.name); put("generation", task.generation); put("lease_owner", task.owner); put("lease_until", task.leaseUntil)
            put("next_run_at", task.nextRunAt); put("document_json", task.document.put("updated_at", now()).toString())
        }, "task_id=?", arrayOf(task.id))
    }
    private fun event(task: ExecutionTask, type: String, details: JSONObject = JSONObject()) = database.appendEvent(task.sessionId, type,
        details.put("execution_task_id", task.id).put("state", task.state.name).put("generation", task.generation).put("usage", task.usage))
    fun create(session: AtlasSession, objective: String, turnId: String, budget: ExecutionBudget, atomicTools: List<String> = emptyList()): ExecutionTask = atomic {
        val id = UUID.randomUUID().toString()
        val doc = JSONObject().put("objective", objective).put("root_turn_id", turnId).put("created_at", now()).put("budget", budget.json())
            .put("usage", JSONObject().put("input_tokens",0).put("output_tokens",0).put("model_steps",0).put("tool_calls",0).put("active_ms",0))
            .put("scope", JSONObject().put("actor_id",session.actorId ?: JSONObject.NULL).put("realm_id",session.workspaceId).put("session_id",session.id).put("atomic_local_tools",JSONArray(atomicTools)))
            .put("workspace_target", JSONObject().put("workspace_id", session.activeComposableWorkspaceId ?: JSONObject.NULL).put("sequence", session.workspaceNavigationSequence))
            .put("slice_count",1).put("checkpoint",JSONObject()).put("consecutive_retries",0)
        database.writableDatabase.insertOrThrow("execution_tasks",null,ContentValues().apply { put("task_id",id);put("session_id",session.id);put("state","READY");put("generation",0);put("lease_until",0);put("next_run_at",0);put("document_json",doc.toString()) })
        link(id, turnId, 1)
        get(id)!!.also { event(it,"execution.created",JSONObject().put("objective",objective).put("budget",budget.json())) }
    }
    private fun link(taskId: String, turnId: String, ordinal: Int) { database.writableDatabase.insertOrThrow("execution_slices",null,ContentValues().apply {put("task_id",taskId);put("turn_id",turnId);put("ordinal",ordinal)}) }
    fun claim(taskId: String, owner: String): ExecutionLease? = atomic {
        val task = get(taskId) ?: return@atomic null
        val session = database.loadSession(task.sessionId) ?: return@atomic null
        if(session.status != SessionStatus.ACTIVE || session.workspaceId != task.document.getJSONObject("scope").getString("realm_id") || (session.actorId ?: "") != task.document.getJSONObject("scope").optString("actor_id", "").let { if(it=="null") "" else it }) return@atomic null
        if(task.state != ExecutionState.READY || task.nextRunAt > now()) return@atomic null
        if(running(task.sessionId).any { it.id != task.id && it.state == ExecutionState.RUNNING && it.leaseUntil >= now() }) return@atomic null
        task.document.put("active_started_at",now())
        val claimed = task.copy(state=ExecutionState.RUNNING,owner=owner,generation=task.generation+1,leaseUntil=now()+LEASE_MS)
        save(claimed); event(claimed,"execution.claimed"); ExecutionLease(taskId,owner,claimed.generation)
    }
    fun valid(lease: ExecutionLease): Boolean = get(lease.taskId)?.let { it.state==ExecutionState.RUNNING && it.owner==lease.owner && it.generation==lease.generation && it.leaseUntil>=now() && database.loadSession(it.sessionId)?.let { session -> session.status==SessionStatus.ACTIVE && session.workspaceId==it.document.getJSONObject("scope").getString("realm_id") && (session.actorId ?: "")==it.document.getJSONObject("scope").optString("actor_id", "").let { value -> if(value=="null") "" else value } }==true } == true
    fun check(lease: ExecutionLease) { if(!valid(lease)) throw CancellationException("Execution lease changed") }
    fun currentTurn(taskId: String) = turns(taskId).last()
    fun nextTurn(taskId: String): String = atomic {
        val task=get(taskId)!!; require(task.state==ExecutionState.READY)
        val previous=database.loadTurn(currentTurn(taskId))
        if(previous?.status==TurnStatus.WAITING_FOR_CONFIRMATION && pending(taskId).none {it.status==ToolCallStatus.WAITING_FOR_CONFIRMATION}) {
            database.updateTurn(previous.id,TurnStatus.INTERRUPTED,error="Authorization resolved; execution transferred to continuation")
        }
        val turn=database.createTurn(task.sessionId,"continuation")
        val ordinal=task.document.optInt("slice_count")+1; task.document.put("slice_count",ordinal); save(task);link(taskId,turn.id,ordinal)
        event(task,"execution.slice_created",JSONObject().put("turnId",turn.id).put("ordinal",ordinal));turn.id
    }
    fun attachReply(taskId: String, turnId: String): ExecutionTask = atomic {
        val task=get(taskId)!!;require(task.state==ExecutionState.WAITING && task.waitReason in setOf("clarification","unresolved_tool_failure","repeated_failure"))
        val ordinal=task.document.optInt("slice_count")+1;task.document.put("slice_count",ordinal)
        val replies=task.document.optJSONArray("follow_up_turn_ids") ?: JSONArray()
        replies.put(turnId);task.document.put("follow_up_turn_ids",replies).put("repeated_failures",0)
        link(taskId,turnId,ordinal);val ready=task.copy(state=ExecutionState.READY,generation=task.generation+1,owner=null,leaseUntil=0,nextRunAt=0);save(ready);ready
    }
    /** Only an unambiguous blocked task receives conversational steering. Other waits keep their gates. */
    fun followUpTask(sessionId: String): ExecutionTask? = database.readableDatabase.rawQuery(
        "SELECT task_id FROM execution_tasks WHERE session_id=? AND state IN ('READY','RUNNING','WAITING','PAUSED') LIMIT 2",arrayOf(sessionId)
    ).use { cursor -> buildList {while(cursor.moveToNext())get(cursor.getString(0))?.let(::add)} }.singleOrNull()
        ?.takeIf {it.state==ExecutionState.WAITING && it.waitReason in setOf("unresolved_tool_failure","repeated_failure")}

    /** Only optional visual inspection can be handed back; required execution/consent gates are untouched. */
    fun handOffPixels(call: AtlasToolCall, userQuote: String): JSONObject = atomic {
        val task=forTurn(call.turnId) ?: error("Task unavailable")
        require(task.state==ExecutionState.RUNNING && task.sessionId==call.sessionId)
        require(userQuote.length in 8..500)
        val slices=turns(task.id).toSet()
        val user=database.loadMessages(task.sessionId,limit=10000).lastOrNull {it.role==MessageRole.USER && it.turnId in slices}
            ?: error("No user instruction in this task")
        require(user.content.contains(userQuote)) { "user_quote must quote the latest user instruction in this task" }
        val binding=task.document.optJSONObject("workspace_target") ?: error("No workspace target")
        val workspace=binding.optString("workspace_id");require(workspace.isNotBlank() && workspace!="null")
        val requirement=task.document.optJSONObject("completion_requirement")?.takeIf {it.optString("workspace_id")==workspace}
            ?: JSONObject().put("workspace_id",workspace).also {task.document.put("completion_requirement",it)}
        requirement.put("require_pixels",false).put("pixels_owner","user").put("pixels_handoff_turn_id",user.turnId)
        task.document.put("pixel_handoff",JSONObject().put("workspace_id",workspace).put("user_message_id",user.id).put("user_quote",userQuote).put("operation_id",call.id))
        save(task);event(task,"execution.pixels_handed_to_user",task.document.getJSONObject("pixel_handoff"))
        JSONObject().put("ok",true).put("workspace_id",workspace).put("pixels_owner","user").put("pixels_verified",false).put("required_execution_checks_unchanged",true)
    }

    /** Native confirmation approves exactly these candidate bytes, never a synthetic test pass. */
    fun acceptUnverified(call: AtlasToolCall, candidateId: String, criteria: JSONArray, quote: String): JSONObject = atomic {
        require(call.status==ToolCallStatus.APPROVED && call.requiresConfirmation) { "USER_CONFIRMATION_REQUIRED" }
        val task=forTurn(call.turnId) ?: error("Task unavailable")
        require(task.state==ExecutionState.RUNNING && task.sessionId==call.sessionId)
        val workspace=task.document.getJSONObject("workspace_target").getString("workspace_id")
        val candidate=com.grinningfrog.atlas.workspace.application.WorkspacePackageRepository(database).inspect(workspace,task.document.getJSONObject("scope").getString("realm_id"),candidateId)
        require(database.loadComposableWorkspace(workspace)?.liveRevisionId==candidate.getString("base_revision_id")) { "REVISION_CONFLICT" }
        val user=database.loadMessages(task.sessionId,limit=10000).lastOrNull {it.role==MessageRole.USER && it.turnId in turns(task.id)} ?: error("No user instruction")
        val boundUserId = JSONObject(call.argumentsJson).optString("user_message_id")
        require(boundUserId.isBlank() || boundUserId == user.id) { "USER_INSTRUCTION_CHANGED: request fresh native confirmation" }
        require(quote.length in 1..500 && user.content.contains(quote)) { "Quote the latest user instruction" }
        val missing=missingAcceptance(call.turnId,candidateId)
        val selected=(0 until criteria.length()).map(criteria::getString)
        require(selected.isNotEmpty() && selected.distinct().size==selected.size && selected.all {it in missing}) { "Choose exact outstanding criteria" }
        val receipt=JSONObject().put("candidate_id",candidateId).put("content_hash",candidate.getString("content_hash"))
            .put("workspace_id",workspace).put("criteria",criteria).put("status","user_accepted_unverified")
            .put("user_message_id",user.id).put("user_quote",quote).put("confirmation_operation_id",call.id)
            .put("tested",false).put("at_ms",now())
        val accepted=task.document.optJSONArray("manual_acceptance") ?: JSONArray()
        accepted.put(receipt);task.document.put("manual_acceptance",accepted);save(task)
        event(task,"execution.behavior_accepted_unverified",receipt)
        JSONObject(receipt.toString()).put("ok",true).put("authority","Acceptance only; schema, permissions, migration, startup and unknown effects unchanged")
    }

    fun manualAcceptance(turnId: String, candidateId: String): JSONArray {
        val task=forTurn(turnId) ?: return JSONArray()
        val workspace=task.document.optJSONObject("workspace_target")?.optString("workspace_id") ?: return JSONArray()
        val candidate=runCatching {com.grinningfrog.atlas.workspace.application.WorkspacePackageRepository(database).inspect(workspace,task.document.getJSONObject("scope").getString("realm_id"),candidateId)}.getOrNull() ?: return JSONArray()
        val entries=task.document.optJSONArray("manual_acceptance") ?: JSONArray()
        return JSONArray((0 until entries.length()).map(entries::getJSONObject).filter {it.optString("candidate_id")==candidateId && it.optString("workspace_id")==workspace && it.optString("content_hash")==candidate.optString("content_hash")})
    }

    fun missingAcceptance(turnId: String, candidateId: String): List<String> {
        val task=forTurn(turnId) ?: return emptyList()
        val acceptance=task.document.optJSONObject("acceptance") ?: JSONObject().put("workspace_id",task.document.optJSONObject("workspace_target")?.optString("workspace_id"))
        if(acceptance.optString("workspace_id")!=task.document.optJSONObject("workspace_target")?.optString("workspace_id"))return emptyList()
        val verified=calls(task.id).filter {it.name in setOf("workspace_package_ui_test","workspace_visual_test") && it.status==ToolCallStatus.COMPLETED}.flatMap {
            val receipt=runCatching {JSONObject(it.resultJson.orEmpty())}.getOrDefault(JSONObject())
            if((it.name=="workspace_package_ui_test" || receipt.optString("kind")=="visual_render") && receipt.optBoolean("passed") && receipt.optString("candidate_id")==candidateId) {
                val names=receipt.optJSONArray("verified_criteria") ?: JSONArray();(0 until names.length()).map(names::getString)
            } else emptyList()
        }.toSet()
        val required=acceptance.optJSONArray("criteria") ?: JSONArray()
        val missing=(0 until required.length()).map(required::getString).filter {it !in verified}.toMutableList()
        if(task.document.optJSONObject("completion_requirement")?.optBoolean("require_persistence")==true && !calls(task.id).any {
            it.name=="workspace_package_ui_test" && it.status==ToolCallStatus.COMPLETED && runCatching {val r=JSONObject(it.resultJson.orEmpty());r.optString("candidate_id")==candidateId && r.optBoolean("passed") && r.optBoolean("storage_verified") && r.optString("storage_scope")=="isolated_test_database"}.getOrDefault(false)
        })missing.add("Persistence: use verify_storage=true, commit, reload and assert_record on this candidate")
        val accepted=manualAcceptance(turnId,candidateId)
        val waived=(0 until accepted.length()).flatMap {index -> val names=accepted.getJSONObject(index).getJSONArray("criteria");(0 until names.length()).map(names::getString)}.toSet()
        return missing.filter {it !in waived}
    }

    fun available(taskId: String): Boolean {
        val t=get(taskId) ?: return false; val b=t.document.getJSONObject("budget");val u=t.usage
        return listOf("input_tokens","output_tokens","model_steps","tool_calls","active_ms").all { u.optLong(it)<b.getLong(it) }
    }
    /** Reserve before dispatch; missing provider usage is conservatively estimated, never charged as zero. */
    fun reserveInference(lease: ExecutionLease, inputEstimate: Long, outputReserve: Long = 2048, requestId: String = UUID.randomUUID().toString()): Boolean = atomic {
        check(lease);val t=get(lease.taskId)!!;val b=t.document.getJSONObject("budget");val u=t.usage
        if(!available(t.id) || u.optLong("input_tokens")+inputEstimate>b.getLong("input_tokens") || u.optLong("output_tokens")+outputReserve>b.getLong("output_tokens")) return@atomic false
        val reservations=t.document.optJSONObject("inference_reservations") ?: JSONObject().also { t.document.put("inference_reservations",it) }
        require(!reservations.has(requestId))
        reservations.put(requestId,JSONObject().put("input",inputEstimate).put("output",outputReserve))
        u.put("input_tokens",u.optLong("input_tokens")+inputEstimate).put("output_tokens",u.optLong("output_tokens")+outputReserve).put("model_steps",u.optLong("model_steps")+1)
        t.document.put("reservation",JSONObject().put("input",inputEstimate).put("output",outputReserve).put("request_id",requestId));save(t.copy(leaseUntil=now()+LEASE_MS));true
    }
    fun reconcileInference(lease: ExecutionLease, input: Long?, output: Long?) = atomic {
        check(lease);val task=get(lease.taskId)!!;val reservation=task.document.optJSONObject("reservation") ?: return@atomic
        reconcileUsage(task,reservation.optString("request_id"),reservation,input,output)
    }
    /** Accounting only: a diagnostic late response never obtains a lease or executes its proposed tools. */
    fun reconcileLateInference(turnId: String, requestId: String, input: Long?, output: Long?) = atomic {
        val task=forTurn(turnId) ?: return@atomic
        val reservation=task.document.optJSONObject("inference_reservations")?.optJSONObject(requestId) ?: return@atomic
        reconcileUsage(task,requestId,reservation,input,output)
        event(task,"execution.late_usage_reconciled",JSONObject().put("request_id",requestId).put("turn_id",turnId))
    }
    private fun reconcileUsage(task: ExecutionTask, requestId: String, reservation: JSONObject, input: Long?, output: Long?) {
        task.usage.put("input_tokens",maxOf(0,task.usage.optLong("input_tokens")-reservation.optLong("input")+(input ?: reservation.optLong("input"))))
            .put("output_tokens",maxOf(0,task.usage.optLong("output_tokens")-reservation.optLong("output")+(output ?: reservation.optLong("output"))))
        task.document.optJSONObject("inference_reservations")?.remove(requestId)
        if(task.document.optJSONObject("reservation")?.optString("request_id")==requestId)task.document.remove("reservation")
        save(task)
    }
    /** Truncation yields for continuation, but repeated incomplete output is a diagnosable wait. */
    fun observeResponse(lease: ExecutionLease, finishReason: String?): Boolean = atomic {
        check(lease); val task=get(lease.taskId)!!
        val truncated=finishReason in setOf("length", "max_tokens", "max_output_tokens")
        val count=if(truncated) task.document.optInt("consecutive_cutoffs")+1 else 0
        task.document.put("consecutive_cutoffs",count);save(task)
        if(count>=3) control(task.id,ExecutionState.WAITING,"context_cutoff")
        !truncated
    }
    fun reserveTool(lease: ExecutionLease, executionTurnId: String? = null, operationId: String? = null): Boolean = atomic {
        check(lease);val task=get(lease.taskId)!!;if(!available(task.id)) return@atomic false
        task.usage.put("tool_calls",task.usage.optLong("tool_calls")+1);save(task.copy(leaseUntil=now()+LEASE_MS))
        if(executionTurnId!=null && operationId!=null) event(task,"execution.tool_dispatched",JSONObject().put("turnId",executionTurnId).put("operation_id",operationId))
        true
    }
    fun finish(lease: ExecutionLease, outcome: SliceOutcome, activeMs: Long): ExecutionTask? = atomic {
        if(!valid(lease)) return@atomic get(lease.taskId)
        val task=get(lease.taskId)!!;task.usage.put("active_ms",task.usage.optLong("active_ms")+activeMs.coerceAtLeast(0));task.document.remove("active_started_at")
        save(task)
        task.document.put("consecutive_retries",0)
        val all=calls(task.id);val pending=pending(task.id).any { it.status==ToolCallStatus.UNKNOWN || it.status==ToolCallStatus.RUNNING }
        val last=all.groupBy(::operationGroup).values.mapNotNull { it.lastOrNull() }
        val superseded = all.filter(::failedOperation).mapNotNull { call -> (supersedingControl(call, all) ?: supersedingActivation(call, all))?.let { call to it } }.toMap()
        val diagnosticFailures=last.filter { nonBlockingAuditFailure(it, task, all) }
        val failures=last.filter { it !in diagnosticFailures && it !in superseded }.filter(::failedOperation)
        val work = ExecutionWorkState.build(all, task.document.optJSONObject("workspace_target")?.optString("workspace_id"))
        val requirement=task.document.optJSONObject("completion_requirement")
        val missingCriteria=work.optJSONObject("latest_candidate")?.optString("candidate_id")?.let {missingAcceptance(currentTurn(task.id),it)} ?: emptyList()
        work.put("missing_criteria",JSONArray(missingCriteria))
        val pixelsPending=!pixelsSatisfied(task,all)
        val deliveryPending=(missingCriteria.isNotEmpty() || pixelsPending) || requirement?.optString("delivery")=="activation" &&
            requirement?.optString("workspace_id")==task.document.optJSONObject("workspace_target")?.optString("workspace_id") &&
            work.optString("delivery_status")!="activated"
        val signature=failures.joinToString("|") { call ->
            if (recoverableVerification(call)) {
                val r=ExecutionWorkState.receipt(call)
                JSONObject().put("tool",call.name).put("error",call.error).apply {
                    for (key in listOf("content_hash","passed","detail","host_failures","missing_operations","missing_criteria","failed_steps","failed_fixtures","notification_verified"))
                        if(r.has(key))put(key,r.get(key))
                    r.optJSONArray("failed_steps")?.let { steps -> for(i in 0 until steps.length()) {
                        steps.getJSONObject(i).remove("ui_state");steps.getJSONObject(i).remove("recovery_guidance")
                    } }
                }.toString()
            } else call.name+":"+(call.error ?: call.resultJson.orEmpty()).take(1000)
        }
        val completionSignature=if(deliveryPending) "activation_required:"+(work.optJSONObject("latest_candidate")?.optString("content_hash") ?: "no_candidate") else ""
        val effectiveSignature=signature+completionSignature
        val failedThisSlice=failures.any { it.turnId==currentTurn(task.id) }
        val priorFrontier = task.document.optJSONObject("work_state")?.optString("frontier")
        val madeProgress = priorFrontier != work.getString("frontier")
        val stagnant = if (madeProgress) 0 else task.document.optInt("no_progress_slices") + 1
        task.document.put("no_progress_slices", stagnant)
        work.put("no_progress_slices", stagnant)
        task.document.put("work_state", work)
        event(task, "execution.progress", JSONObject().put("frontier_changed", madeProgress).put("no_progress_slices", stagnant))
        val repeatedCalls=failures.filter(ToolRetryPolicy::deterministicFailure).maxOfOrNull { failed -> all.count { prior ->
            prior.name==failed.name && ToolRetryPolicy.sameArguments(prior.argumentsJson,failed.argumentsJson) && ToolRetryPolicy.deterministicFailure(prior)
        } } ?: 0
        val repeated=maxOf(repeatedCalls, if(effectiveSignature.isNotBlank() && (failedThisSlice || !madeProgress) && effectiveSignature==task.document.optString("failure_signature")) task.document.optInt("repeated_failures")+1 else if(failedThisSlice || deliveryPending) 1 else 0)
        task.document.put("failure_signature",effectiveSignature).put("repeated_failures",repeated)
        val recent=all.takeLast(20)
        val definitionId=all.lastOrNull { it.name == "workspace_package_activate" || runCatching {JSONObject(it.argumentsJson).has("definition")}.getOrDefault(false) }?.id ?: task.document.getJSONObject("checkpoint").optString("latest_definition_operation_id").takeIf { it.isNotBlank() && it!="null" }
        task.document.put("checkpoint",JSONObject().put("last_turn_id",currentTurn(task.id)).put("operation_ids",JSONArray(recent.map {it.id}))
            .put("diagnostic_operation_ids", JSONArray(diagnosticFailures.map { it.id }))
            .put("unresolved_operation_ids", JSONArray(failures.map { it.id }))
            .put("resolved_operations", JSONArray(superseded.map { (failed, success) -> JSONObject().put("operation_id", failed.id)
                .put("tool", failed.name).put("occurred_at_ms", failed.updatedAtMs).put("resolved_at_ms", success.updatedAtMs)
                .put("resolved_by", success.id).put("resolution", if (failed.name == ToolHarness.CLARIFICATION_TOOL) "corrected_control_accepted" else if(failed.name=="workspace_visual_test") "superseded_by_passing_visual_run" else "superseded_by_committed_activation") }))
            .put("completed_operations",JSONArray(recent.filter {it.status==ToolCallStatus.COMPLETED}.map {it.id}))
            .put("latest_definition_operation_id",definitionId))
        val recover = outcome == SliceOutcome.RESPONDED && (deliveryPending || failures.isNotEmpty()) && failures.all(::recoverableVerification)
        val reason=when { pending -> "unknown_operation_outcome";outcome==SliceOutcome.RESPONDED&&failures.isEmpty()&&!deliveryPending->"";!available(task.id)->"task_allowance";repeated>=3->"repeated_failure";recover -> "";outcome==SliceOutcome.RESPONDED&&failures.isNotEmpty()->"unresolved_tool_failure";else->when(outcome){SliceOutcome.WAITING_USER->"clarification";SliceOutcome.WAITING_PERMISSION->"authorization";SliceOutcome.WAITING_PROVIDER->"provider";SliceOutcome.BLOCKED->"runtime_blocker";else->""} }
        task.document.put("wait_reason_at_ms", if (reason.isNotBlank()) now() else JSONObject.NULL)
        task.document.put("wait_reason",reason).put("completion_kind",if(outcome==SliceOutcome.RESPONDED&&reason.isBlank()&&!recover) "response_finished; consult operation receipts for effects" else JSONObject.NULL)
        task.document.put("recovery_required", recover && reason.isBlank())
        if (recover && reason.isBlank()) event(task,"execution.recovery_required",JSONObject().put("reason",if(deliveryPending && failures.isEmpty()) "activation_required" else "recoverable_verification_failure").put("operation_ids",JSONArray(failures.map { it.id })))
        val state=if(reason.isNotBlank()) ExecutionState.WAITING else if(recover) ExecutionState.READY else if(outcome==SliceOutcome.RESPONDED) ExecutionState.COMPLETED else ExecutionState.READY
        val result=task.copy(state=state,owner=null,leaseUntil=0,nextRunAt=0);save(result);event(result,"execution.checkpoint",JSONObject().put("outcome",outcome.name).put("checkpoint",task.document.getJSONObject("checkpoint")).put("wait_reason",reason));result
    }
    private fun supersedingControl(call: AtlasToolCall, all: List<AtlasToolCall>): AtlasToolCall? {
        if (call.name != ToolHarness.CLARIFICATION_TOOL || call.status != ToolCallStatus.REJECTED) return null
        val args = runCatching { JSONObject(call.argumentsJson) }.getOrNull() ?: return null
        return all.drop(all.indexOf(call) + 1).firstOrNull {
            if (it.name != call.name || it.status != ToolCallStatus.COMPLETED) false else {
                val result = runCatching { JSONObject(it.resultJson.orEmpty()) }.getOrDefault(JSONObject())
                val next = runCatching { JSONObject(it.argumentsJson) }.getOrDefault(JSONObject())
                result.optBoolean("accepted") && next.optString("action") == args.optString("action") &&
                    next.optString("clarification_id") == args.optString("clarification_id")
            }
        }
    }

    private fun recoverableVerification(call: AtlasToolCall) =
        (call.name in setOf("workspace_package_test", "workspace_package_ui_test", "workspace_package_validate", "workspace_visual_test") ||
            call.name=="workspace_package_read_file" && call.status==ToolCallStatus.FAILED && call.error.orEmpty().startsWith("EMPTY_PACKAGE_DRAFT") ||
            call.name=="workspace_package_stage" && (call.status==ToolCallStatus.COMPLETED && runCatching {JSONObject(call.resultJson.orEmpty()).optString("error")=="STAGING_ARGUMENTS_INVALID"}.getOrDefault(false) || call.status==ToolCallStatus.FAILED && (call.error.orEmpty().startsWith("VALIDATION_FAILED") || call.error.orEmpty().startsWith("Unknown fields:"))) ||
            call.name=="workspace_package_activate" && call.status==ToolCallStatus.COMPLETED && runCatching {JSONObject(call.resultJson.orEmpty()).optString("error") in setOf("ACCEPTANCE_REQUIRED","CHECK_REQUIRED")}.getOrDefault(false)) &&
        call.status in setOf(ToolCallStatus.COMPLETED, ToolCallStatus.REJECTED, ToolCallStatus.FAILED)

    private fun operationGroup(call:AtlasToolCall):String = call.name + if(call.name=="workspace_visual_test") ":"+runCatching {JSONObject(call.argumentsJson).optString("action")}.getOrDefault("") else ""

    private fun failedOperation(call: AtlasToolCall): Boolean = call.status in setOf(ToolCallStatus.FAILED, ToolCallStatus.REJECTED) ||
        (call.status == ToolCallStatus.COMPLETED && runCatching { JSONObject(call.resultJson.orEmpty()).let { result ->
            listOf("valid", "ok", "passed", "structurally_valid").any { result.has(it) && !result.optBoolean(it) }
        } }.getOrDefault(false))

    /** Only discarded, side-effect-free candidate attempts can be superseded. Unknown outcomes never are. */
    private fun supersedingActivation(call: AtlasToolCall, all: List<AtlasToolCall>): AtlasToolCall? {
        if(call.name=="workspace_visual_test" && call.status in setOf(ToolCallStatus.FAILED,ToolCallStatus.REJECTED,ToolCallStatus.COMPLETED)) {
            val args=runCatching {JSONObject(call.argumentsJson)}.getOrDefault(JSONObject())
            val result=runCatching {JSONObject(call.resultJson.orEmpty())}.getOrDefault(JSONObject())
            val opened=all.take(all.indexOf(call)+1).lastOrNull { prior -> prior.name=="workspace_visual_test" && prior.status==ToolCallStatus.COMPLETED &&
                runCatching {JSONObject(prior.resultJson.orEmpty()).optString("instance_id")==args.optString("instance_id")}.getOrDefault(false) }
            val context=opened?.let {runCatching {JSONObject(it.resultJson.orEmpty())}.getOrNull()} ?: JSONObject()
            val workspace=result.optString("workspace_id").ifBlank {args.optString("workspace_id").ifBlank {context.optString("workspace_id")}}
            if(workspace.isBlank())return null
            return all.drop(all.indexOf(call)+1).lastOrNull { success -> success.name=="workspace_visual_test" && success.status==ToolCallStatus.COMPLETED &&
                runCatching {val r=receipt(success)?.let(::JSONObject) ?: return@runCatching false
                    r.optString("kind")=="visual_render" && r.optBoolean("passed") && r.optString("workspace_id")==workspace
                }.getOrDefault(false) }
        }
        if(call.name=="workspace_accept_unverified" && call.status in setOf(ToolCallStatus.FAILED,ToolCallStatus.REJECTED)) {
            val args=runCatching {JSONObject(call.argumentsJson)}.getOrNull() ?: return null
            val workspace=args.optString("workspace_id").takeIf(String::isNotBlank) ?: return null
            val candidate=args.optString("candidate_id").takeIf(String::isNotBlank) ?: return null
            return all.drop(all.indexOf(call)+1).lastOrNull { success ->
                success.name=="workspace_package_activate" && success.status==ToolCallStatus.COMPLETED &&
                    runCatching {val r=receipt(success)?.let(::JSONObject) ?: return@runCatching false
                        r.optBoolean("activated") && r.optString("workspace_id")==workspace && r.optString("candidate_id")==candidate &&
                            database.loadComposableWorkspace(workspace)?.liveRevisionId==r.optString("revision_id")
                    }.getOrDefault(false)
            }
        }
        if (call.name !in setOf("workspace_package_stage", "workspace_package_test", "workspace_package_ui_test", "workspace_package_validate") ||
            call.status !in setOf(ToolCallStatus.FAILED, ToolCallStatus.REJECTED, ToolCallStatus.COMPLETED)) return null
        val args = runCatching { JSONObject(call.argumentsJson) }.getOrNull() ?: return null
        val workspace = args.optString("workspace_id").takeIf(String::isNotBlank) ?: return null
        val base = args.optString("base_revision_id").takeIf(String::isNotBlank) ?: args.optString("candidate_id").takeIf(String::isNotBlank)?.let { candidate ->
            database.readableDatabase.rawQuery("SELECT base_revision_id FROM workspace_package_candidates WHERE workspace_id=? AND candidate_id=?", arrayOf(workspace, candidate)).use { if (it.moveToFirst()) it.getString(0) else null }
        } ?: return null
        return all.drop(all.indexOf(call) + 1).lastOrNull { success ->
            if (success.name != "workspace_package_activate" || success.status != ToolCallStatus.COMPLETED) false
            else runCatching {
                val receipt = receipt(success)?.let(::JSONObject) ?: return@runCatching false
                receipt.optBoolean("activated") && receipt.optString("workspace_id") == workspace && receipt.optString("previous_revision_id") == base &&
                    database.loadComposableWorkspace(workspace)?.liveRevisionId == receipt.optString("revision_id")
            }.getOrDefault(false)
        }
    }
    private fun nonBlockingAuditFailure(call: AtlasToolCall, task: ExecutionTask, all: List<AtlasToolCall>): Boolean {
        if (call.name == "atlas_runtime_stage" && call.status in setOf(ToolCallStatus.FAILED, ToolCallStatus.REJECTED)) {
            val parent = runCatching { JSONObject(call.argumentsJson).getString("parent_runtime_id") }.getOrNull() ?: return false
            return all.drop(all.indexOf(call) + 1).any { success -> success.name == "atlas_runtime_activate" && success.status == ToolCallStatus.COMPLETED &&
                runCatching { val r = JSONObject(success.resultJson.orEmpty()); r.optBoolean("selected_for_new_tasks") && !r.optBoolean("current_task_changed", true) && r.optString("parent_runtime_id") == parent }.getOrDefault(false) }
        }
        val recoveryWorkspace = if (call.status == ToolCallStatus.FAILED) runCatching {
            val result = JSONObject(call.resultJson.orEmpty())
            when {
                call.name in setOf("workspace_package_test", "workspace_package_ui_test") && result.optString("error_code") == "CANDIDATE_BASE_CONFLICT" -> JSONObject(call.argumentsJson).getString("workspace_id")
                call.name == "workspace_package_create" && result.optString("error_code") == "EXISTING_TARGET_BOUND" -> result.getJSONObject("recovery").getString("workspace_id")
                else -> null
            }
        }.getOrNull() else null
        if (recoveryWorkspace != null) {
            val workspace = recoveryWorkspace
            return workspace == task.document.optJSONObject("workspace_target")?.optString("workspace_id") &&
                all.drop(all.indexOf(call) + 1).any { success -> success.name == "workspace_package_activate" && success.status == ToolCallStatus.COMPLETED &&
                    runCatching { val r = receipt(success)?.let(::JSONObject) ?: return@runCatching false
                        r.optBoolean("activated") && r.optString("workspace_id") == workspace && database.loadComposableWorkspace(workspace)?.liveRevisionId == r.optString("revision_id") }.getOrDefault(false) }
        }
        if(call.name=="atlas_task_handoff_pixels" && call.status in setOf(ToolCallStatus.FAILED,ToolCallStatus.REJECTED)) {
            val requirement=task.document.optJSONObject("completion_requirement")
            if(requirement?.optBoolean("require_pixels")==true && !pixelsSatisfied(task,all))return false
            val workspace=task.document.optJSONObject("workspace_target")?.optString("workspace_id") ?: return false
            return all.any { success -> success.name=="workspace_package_activate" && success.status==ToolCallStatus.COMPLETED &&
                runCatching { val r=receipt(success)?.let(::JSONObject) ?: return@runCatching false
                    r.optBoolean("activated") && r.optString("workspace_id")==workspace && database.loadComposableWorkspace(workspace)?.liveRevisionId==r.optString("revision_id")
                }.getOrDefault(false) }
        }
        if(call.name=="workspace_package_read_file" && call.status==ToolCallStatus.FAILED && call.error.orEmpty().startsWith("EMPTY_PACKAGE_DRAFT")) {
            val workspace=runCatching {JSONObject(call.argumentsJson).getString("workspace_id")}.getOrNull() ?: return false
            return all.drop(all.indexOf(call)+1).any {next -> next.name=="workspace_package_stage" && next.status==ToolCallStatus.COMPLETED && runCatching {val r=JSONObject(next.resultJson.orEmpty());r.optString("workspace_id")==workspace && r.optBoolean("structurally_valid")}.getOrDefault(false)}
        }
        if(call.name=="workspace_capture_view" && call.status==ToolCallStatus.FAILED) {
            val args=runCatching {JSONObject(call.argumentsJson)}.getOrDefault(JSONObject())
            val requirement=task.document.optJSONObject("completion_requirement")
            if(requirement?.optBoolean("require_pixels")==true && !pixelsSatisfied(task,all))return false
            val error=call.error.orEmpty()+call.resultJson.orEmpty()
            if(!error.contains("WORKSPACE_NOT_OPEN") && !error.contains("Target workspace is not open for pixel capture") && !error.contains("Workspace panel is not visible or has not drawn this revision"))return false
            if(requirement?.optString("pixels_owner")=="user" && requirement.optString("workspace_id")==args.optString("workspace_id"))return true
            return all.any { success -> success.name=="workspace_package_activate" && success.status==ToolCallStatus.COMPLETED &&
                runCatching {val r=receipt(success)?.let(::JSONObject) ?: return@runCatching false
                    r.optBoolean("activated") && r.optString("workspace_id")==args.optString("workspace_id") &&
                    database.loadComposableWorkspace(r.getString("workspace_id"))?.liveRevisionId==r.optString("revision_id")
                }.getOrDefault(false) }
        }
        if (call.name != CapabilityDiscovery.TOOL || call.status != ToolCallStatus.FAILED) return false
        val args = runCatching { JSONObject(call.argumentsJson) }.getOrNull() ?: return false
        val result = runCatching { JSONObject(call.resultJson.orEmpty()) }.getOrNull() ?: return false
        if (args.optString("stage") != "resume_local" || args.has("blocker_id") || result.optString("error_code") != "missing_blocker") return false
        if (CapabilityDiscovery(database).active(currentTurn(task.id))) return false
        val mutations = setOf("workspace_package_create", "workspace_package_activate", "workspace_create", "workspace_replace_definition", "workspace_patch_definition", "workspace_update_metadata",
            "workspace_add_record", "workspace_update_record", "workspace_delete_record", "workspace_restore_record")
        // A completion claim alone is insufficient: require an atomic committed effect receipt.
        return all.any { it.name in mutations && it.status == ToolCallStatus.COMPLETED && receipt(it) != null }
    }
    private fun pixelsSatisfied(task: ExecutionTask, all: List<AtlasToolCall>): Boolean {
        val requirement=task.document.optJSONObject("completion_requirement") ?: return true
        if(!requirement.optBoolean("require_pixels"))return true
        val workspace=requirement.optString("workspace_id")
        val revision=database.loadComposableWorkspace(workspace)?.liveRevisionId ?: return false
        val visible=all.any {it.name=="workspace_capture_view" && it.status==ToolCallStatus.COMPLETED && runCatching {
            val r=JSONObject(it.resultJson.orEmpty());r.optBoolean("ok") && r.optString("workspace_id")==workspace && r.optString("revision_id")==revision
        }.getOrDefault(false)}
        if(visible)return true
        return all.any { call -> call.name=="workspace_visual_test" && call.status==ToolCallStatus.COMPLETED && runCatching {
            val r=receipt(call)?.let(::JSONObject) ?: return@runCatching false
            if(r.optString("kind")!="visual_render" || !r.optBoolean("passed") || r.optString("workspace_id")!=workspace || r.optString("observation_id").isBlank())return@runCatching false
            database.readableDatabase.rawQuery("SELECT result_json FROM workspace_application_activations WHERE workspace_id=? AND candidate_id=?",arrayOf(workspace,r.getString("candidate_id"))).use { rows ->
                if(!rows.moveToFirst())return@use false
                val activation=JSONObject(rows.getString(0))
                activation.optString("revision_id")==revision && activation.optString("visual_check_id")==r.getString("check_id")
            }
        }.getOrDefault(false) }

    }
    fun outstanding(taskId: String): JSONArray {
        val task=get(taskId) ?: return JSONArray();val all=calls(taskId)
        return JSONArray(all.groupBy(::operationGroup).values.mapNotNull {it.lastOrNull()}.filter {
            failedOperation(it) && !nonBlockingAuditFailure(it,task,all) && supersedingControl(it,all)==null && supersedingActivation(it,all)==null
        }.map {JSONObject().put("operation_id",it.id).put("tool",it.name).put("error",it.error).put("result",it.resultJson?.take(1200))})
    }
    /** Repair stale optional-inspection waits before creating another inference slice. */
    fun reconcileDelivered(taskId: String): ExecutionTask? = atomic {
        val task=get(taskId) ?: return@atomic null
        if(task.state !in setOf(ExecutionState.WAITING,ExecutionState.PAUSED,ExecutionState.READY) ||
            task.waitReason !in setOf("unresolved_tool_failure","repeated_failure") || pending(taskId).isNotEmpty())return@atomic task
        val work=ExecutionWorkState.build(calls(taskId),task.document.optJSONObject("workspace_target")?.optString("workspace_id"))
        val activation=work.optJSONObject("activation") ?: return@atomic task
        if(work.optString("delivery_status")!="activated" || database.loadComposableWorkspace(activation.optString("workspace_id"))?.liveRevisionId!=activation.optString("revision_id") || outstanding(taskId).length()>0)return@atomic task
        if(missingAcceptance(currentTurn(taskId),activation.optString("candidate_id")).isNotEmpty() || !pixelsSatisfied(task,calls(taskId)))return@atomic task
        val checkpoint=task.document.optJSONObject("checkpoint") ?: JSONObject()
        val diagnostics=calls(taskId).filter {nonBlockingAuditFailure(it,task,calls(taskId))}.map {it.id}
        checkpoint.put("unresolved_operation_ids",JSONArray()).put("diagnostic_operation_ids",JSONArray(diagnostics))
        task.document.put("checkpoint",checkpoint)
        task.document.put("work_state",work).put("wait_reason","").put("recovery_required",false).put("completion_kind","delivered; optional inspection unavailable")
        val result=task.copy(state=ExecutionState.COMPLETED,generation=task.generation+1,owner=null,leaseUntil=0,nextRunAt=0)
        save(result);event(result,"execution.delivery_reconciled");result
    }
    fun control(taskId: String, state: ExecutionState, reason: String, budget: ExecutionBudget? = null): ExecutionTask? = atomic {
        val t=get(taskId) ?: return@atomic null
        if(t.state in setOf(ExecutionState.COMPLETED,ExecutionState.CANCELLED,ExecutionState.FAILED)) return@atomic t
        if(t.state==ExecutionState.RUNNING) { t.usage.put("active_ms",t.usage.optLong("active_ms")+(now()-t.document.optLong("active_started_at",now())).coerceAtLeast(0));t.document.remove("active_started_at") }
        if(state in setOf(ExecutionState.READY,ExecutionState.PAUSED,ExecutionState.CANCELLED)) reconcileLocal(t)
        if(state==ExecutionState.READY && pending(taskId).any {it.status==ToolCallStatus.UNKNOWN || it.status==ToolCallStatus.RUNNING}) return@atomic t
        if(state==ExecutionState.CANCELLED) {
            pending(taskId).filter { it.status in setOf(ToolCallStatus.PROPOSED,ToolCallStatus.APPROVED,ToolCallStatus.WAITING_FOR_CONFIRMATION) }.forEach { call ->
                val feedback=JSONObject().put("ok",false).put("error","Task cancelled before execution").toString()
                database.updateToolCall(call.id,ToolCallStatus.REJECTED,resultJson=feedback,error=reason)
                database.insertMessage(AtlasMessage(sessionId=t.sessionId,turnId=call.turnId,role=MessageRole.TOOL,content=feedback,createdAtMs=now(),kind=MessageKind.TOOL_RESULT,toolCallId=call.id))
            }
            database.updateTurn(currentTurn(taskId),TurnStatus.CANCELLED,error=reason)
            database.loadPendingClarification(t.sessionId)?.takeIf {forTurn(it.sourceTurnId)?.id==taskId}?.let {database.updateClarification(it.id,ClarificationStatus.ABANDONED)}
        }
        t.document.put("wait_reason",reason);budget?.let { t.document.put("budget",it.json()) }
        val result=t.copy(state=state,generation=t.generation+1,owner=null,leaseUntil=0,nextRunAt=0);save(result);event(result,"execution.${state.name.lowercase()}",JSONObject().put("reason",reason));result
    }
    fun retry(lease: ExecutionLease, reason: String, activeMs: Long = 0): ExecutionTask? = atomic {
        if(!valid(lease)) return@atomic get(lease.taskId)
        val t=get(lease.taskId)!!;t.usage.put("active_ms",t.usage.optLong("active_ms")+activeMs.coerceAtLeast(0));t.document.remove("active_started_at");val n=t.document.optInt("consecutive_retries")+1;t.document.put("consecutive_retries",n).put("wait_reason",reason)
        val result=t.copy(state=if(n>=8) ExecutionState.WAITING else ExecutionState.READY,owner=null,leaseUntil=0,nextRunAt=now()+minOf(60_000L,1000L shl minOf(n,6)))
        save(result);event(result,"execution.provider_retry",JSONObject().put("attempt",n).put("next_run_at",result.nextRunAt));result
    }
    fun context(taskId: String): String {
        val t=get(taskId) ?: return ""
        val work = ExecutionWorkState.build(calls(taskId), t.document.optJSONObject("workspace_target")?.optString("workspace_id"))
        val latest=calls(taskId).lastOrNull {it.name == "workspace_package_activate" || runCatching {JSONObject(it.argumentsJson).has("definition")}.getOrDefault(false)} ?: t.document.getJSONObject("checkpoint").optString("latest_definition_operation_id").takeIf { it.isNotBlank() && it!="null" }?.let(database::loadToolCall)
        val receipts=JSONArray();var receiptCharacters=0
        for(call in calls(taskId).takeLast(20).asReversed()) {
            val read = call.name in setOf("workspace_package_read_file", "workspace_package_contract", "workspace_package_inspect", "workspace_inspect", "workspace_render_diagnostics")
            val item=ExecutionWorkState.receipt(call)
                .put("result_json", if (read || call.name == "workspace_package_ui_test") null else call.resultJson?.take(2000))
                .put("result_truncated", (call.resultJson?.length ?: 0)>2000)
            if (read) item.put("read_body_location", "Transcript tool result or original audit; reread if projected or stale.")
            item.put("error",call.error.takeIf {call.status!=ToolCallStatus.COMPLETED})
            val size=item.toString().length
            if(receiptCharacters+size<=24000) {receipts.put(item);receiptCharacters+=size}
        }
        val history=JSONArray(list(t.sessionId).take(4).map { prior ->
            val ids=prior.document.optJSONObject("checkpoint")?.optJSONArray("unresolved_operation_ids") ?: JSONArray()
            val unresolved=JSONArray((0 until minOf(ids.length(),4)).mapNotNull {database.loadToolCall(ids.getString(it))}.map {JSONObject().put("operation_id",it.id).put("tool",it.name).put("error",it.error).put("result",it.resultJson?.take(500))})
            JSONObject().put("task_id",prior.id).put("state",prior.state.name).put("objective",prior.objective.take(500)).put("wait_reason",prior.waitReason).put("outstanding_operations",unresolved).put("delivery",prior.document.optJSONObject("work_state")?.optString("delivery_status"))
        })
        return "CORE DURABLE TASK (evidence, not authorization): " + JSONObject().put("task_id",t.id).put("objective",t.objective).put("session_tasks",history).put("recovery_required",t.document.optBoolean("recovery_required")).put("completion_requirement",t.document.optJSONObject("completion_requirement")).put("usage",t.usage)
            .put("follow_up_turn_ids",t.document.optJSONArray("follow_up_turn_ids")).put("acceptance",t.document.optJSONObject("acceptance")).put("outstanding_operations",outstanding(taskId)).put("workspace_target", t.document.optJSONObject("workspace_target")).put("work_state",work.put("no_progress_slices",t.document.optInt("no_progress_slices"))).put("checkpoint",t.document.getJSONObject("checkpoint")).put("recent_operations",receipts).put("latest_authoring_arguments",latest?.let {JSONObject(it.argumentsJson)})
            .put("latest_authoring_result",latest?.resultJson?.let {runCatching {JSONObject(it)}.getOrNull()}).toString() +
            "\nFollow-ups in this task are user steering, not a fresh objective. Answer tangents briefly; apply corrections to remaining work. Report blockers from outstanding_operations, not historical diagnostics. An unavailable optional screenshot after committed delivery is an inspection limitation, not failed delivery. Explicit require_pixels remains blocking unless the user explicitly takes visual verification over; record that instruction with atlas_task_handoff_pixels and an exact quote. For behavior the available test cannot verify, workspace_accept_unverified requests native user confirmation for exact outstanding criteria on an exact candidate. Accepted criteria remain unverified; permissions and host integrity gates remain mandatory. Use delivery=inspect for viewing/status requests; do not turn them into activation tasks. Reuse an existing draft after authoring errors. Host failures are current runtime evidence even if caught by app code; user-reported live failures cannot be dismissed using a passing test from another mode. Declare require_persistence=true for saved-state requests and prove it through isolated commit/reload/assert_record. Declare acceptance_criteria for requested UI behaviors in workspace_target, and attach each criterion to a meaningful UI assertion step; baseline capability coverage alone does not verify requested changes.\nRecovery: declare delivery=activation, draft or inspect in workspace_target according to the user request. This is a completion requirement, never authority. A response is a progress report while recovery_required=true. Diagnose the pinned failed check, correct the test or candidate, and rerun verification. Do not stop merely because a local simulated test failed. Wait only for an actual missing user answer, authorization, unavailable resource or uncertain effect. Claim activation only from a committed activation receipt matching the candidate. If the user requested a draft or review, do not activate it without authorization." +
            "\nWorkspace targeting: ambiguous commands use the captured workspace_target. Explicitly named edit targets must be resolved with workspace_target; inspect other workspaces as references without retargeting. Never change target merely because the user browses. Ask clarification for duplicate names or unclear components. Continue the pending objective from recorded receipts. work_state holds the exact latest used candidate and checks for this target. A failed UI assertion is not a workspace migration problem: compare expected and actual text, correct the test or implementation, and exercise the remaining operations. Never reconstruct or combine UUID fragments. Reuse exact receipt identifiers. Successful reads alone are not app progress. If no_progress_slices grows, reassess the failing test or edit strategy instead of repeating inspection; retain the task and existing candidate. A new slice is not a new user request, not new authority, and not proof of success. Avoid repeating committed effects."
    }
    private fun reconcileLocal(task: ExecutionTask) {
        val local=task.document.getJSONObject("scope").getJSONArray("atomic_local_tools")
        val names=(0 until local.length()).map(local::getString).toSet()
        pending(task.id).filter {it.status==ToolCallStatus.UNKNOWN}.forEach { call ->
            val receipt=receipt(call)
            if(receipt!=null) database.updateToolCall(call.id,ToolCallStatus.COMPLETED,resultJson=receipt)
            else if(call.name in names) database.updateToolCall(call.id,ToolCallStatus.FAILED,error="Atomic local operation rolled back; no effect receipt")
        }
    }
    fun recover(sessionId: String) = atomic {
        running(sessionId).forEach { task ->
            task.usage.put("active_ms",task.usage.optLong("active_ms")+(now()-task.document.optLong("active_started_at",now())).coerceIn(0,LEASE_MS));task.document.remove("active_started_at")
            reconcileLocal(task)
            val unknown=pending(task.id).any {it.status==ToolCallStatus.UNKNOWN}
            task.document.put("wait_reason",if(unknown) "unknown_operation_outcome" else "process_restart")
            val result=task.copy(state=if(unknown) ExecutionState.WAITING else ExecutionState.READY,generation=task.generation+1,owner=null,leaseUntil=0)
            save(result);event(result,"execution.recovered")
        }
    }
    fun receipt(call: AtlasToolCall): String? = database.readableDatabase.rawQuery("SELECT session_id,arguments_hash,result_json FROM local_operation_receipts WHERE operation_id=?",arrayOf(call.id)).use { c ->
        if(!c.moveToFirst()) null else {require(c.getString(0)==call.sessionId&&c.getString(1)==hash(call.name+"\n"+call.argumentsJson)){"Operation ID reused with different scope or arguments"};c.getString(2)} }
    fun localOperation(call: AtlasToolCall, lease: ExecutionLease?, execute: () -> ToolExecutionResult): ToolExecutionResult = atomic {
        receipt(call)?.let { return@atomic ToolExecutionResult(it) }
        forTurn(call.turnId)?.let { task -> require(lease != null && lease.taskId == task.id) { "A task effect requires its execution lease" };check(lease) }
        val result=execute()
        lease?.let(::check)
        database.writableDatabase.insertOrThrow("local_operation_receipts",null,ContentValues().apply {put("operation_id",call.id);put("session_id",call.sessionId);put("arguments_hash",hash(call.name+"\n"+call.argumentsJson));put("result_json",result.resultJson)})
        if(database.loadToolCall(call.id)!=null) database.updateToolCall(call.id,ToolCallStatus.COMPLETED,resultJson=result.resultJson)
        result
    }
}

/** Driver is the same foreground slice used by the app. No model-authored scheduling or extra authority. */
class DurableTaskExecutor(private val store: ExecutionTaskStore, private val now: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) }) {
    suspend fun run(taskId: String, driver: suspend (ExecutionLease, String) -> SliceOutcome): ExecutionTask? {
        val owner=UUID.randomUUID().toString()
        while(true) {
            currentCoroutineContext().ensureActive()
            val task=store.get(taskId) ?: return null
            if(task.state!=ExecutionState.READY) return task
            if(task.nextRunAt>now()) {sleep(minOf(task.nextRunAt-now(),60_000));continue}
            if(!store.available(taskId)) return store.control(taskId,ExecutionState.WAITING,"task_allowance")
            val lease=store.claim(taskId,owner) ?: return store.get(taskId)
            val turnId=store.currentTurn(taskId)
            val started=now()
            val outcome=try { driver(lease,turnId) } catch(cancelled: CancellationException) {
                val current=store.get(taskId)
                if(current?.state==ExecutionState.RUNNING && current.owner==lease.owner && current.generation==lease.generation) store.control(taskId,ExecutionState.PAUSED,"runner interrupted")
                throw cancelled
            }
            catch(error: com.grinningfrog.atlas.provider.InferenceUnavailableException) {
                if(!error.outcomeAmbiguous && error.providerCode !in setOf("subscription_sharing_usage_limit_exceeded","subscription_sharing_invalid_user","subscription_sharing_user_not_eligible")) {
                    val retry=store.retry(lease,error.message.orEmpty(),now()-started);if(retry?.state!=ExecutionState.READY) return retry;store.nextTurn(taskId);continue
                }
                return store.finish(lease,SliceOutcome.WAITING_PROVIDER,now()-started)
            } catch(error: Exception) { store.finish(lease,SliceOutcome.BLOCKED,now()-started);throw error }
            val result=store.finish(lease,outcome,now()-started) ?: return null
            if(result.state!=ExecutionState.READY) return result
            store.nextTurn(taskId)
            sleep(1) // A cooperative boundary, not an additional inference or a user interaction.
        }
    }
}
