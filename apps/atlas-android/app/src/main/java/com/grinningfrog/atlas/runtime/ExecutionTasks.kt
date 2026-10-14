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
    private fun <T> atomic(block: () -> T): T {
        val db = database.writableDatabase; db.beginTransaction()
        try { val result = block(); db.setTransactionSuccessful(); return result } finally { db.endTransaction() }
    }
    fun get(id: String): ExecutionTask? = database.readableDatabase.rawQuery("SELECT task_id,session_id,state,generation,lease_owner,lease_until,next_run_at,document_json FROM execution_tasks WHERE task_id=?", arrayOf(id)).use { c ->
        if (!c.moveToFirst()) null else ExecutionTask(c.getString(0), c.getString(1), ExecutionState.valueOf(c.getString(2)), c.getLong(3), if(c.isNull(4)) null else c.getString(4), c.getLong(5), c.getLong(6), JSONObject(c.getString(7)))
    }
    fun forTurn(turnId: String): ExecutionTask? = database.readableDatabase.rawQuery("SELECT task_id FROM execution_slices WHERE turn_id=?", arrayOf(turnId)).use { c -> if(c.moveToFirst()) get(c.getString(0)) else null }
    fun list(sessionId: String): List<ExecutionTask> = database.readableDatabase.rawQuery("SELECT task_id FROM execution_tasks WHERE session_id=? ORDER BY rowid DESC LIMIT 100", arrayOf(sessionId)).use { c -> buildList { while(c.moveToNext()) get(c.getString(0))?.let(::add) } }
    // Ownership and recovery must never depend on the bounded UI history projection.
    private fun running(sessionId: String): List<ExecutionTask> = database.readableDatabase.rawQuery("SELECT task_id FROM execution_tasks WHERE session_id=? AND state='RUNNING'",arrayOf(sessionId)).use { c -> buildList { while(c.moveToNext()) get(c.getString(0))?.let(::add) } }
    fun turns(taskId: String): List<String> = database.readableDatabase.rawQuery("SELECT turn_id FROM execution_slices WHERE task_id=? ORDER BY ordinal", arrayOf(taskId)).use { c -> buildList { while(c.moveToNext()) add(c.getString(0)) } }
    fun calls(taskId: String): List<AtlasToolCall> = database.loadExecutionToolCalls(taskId)
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
        val turn=database.createTurn(task.sessionId,"continuation")
        val ordinal=task.document.optInt("slice_count")+1; task.document.put("slice_count",ordinal); save(task);link(taskId,turn.id,ordinal)
        event(task,"execution.slice_created",JSONObject().put("turnId",turn.id).put("ordinal",ordinal));turn.id
    }
    fun attachReply(taskId: String, turnId: String): ExecutionTask = atomic {
        val task=get(taskId)!!;require(task.state==ExecutionState.WAITING && task.waitReason=="clarification")
        val ordinal=task.document.optInt("slice_count")+1;task.document.put("slice_count",ordinal)
        link(taskId,turnId,ordinal);val ready=task.copy(state=ExecutionState.READY,generation=task.generation+1,owner=null,leaseUntil=0,nextRunAt=0);save(ready);ready
    }
    fun available(taskId: String): Boolean {
        val t=get(taskId) ?: return false; val b=t.document.getJSONObject("budget");val u=t.usage
        return listOf("input_tokens","output_tokens","model_steps","tool_calls","active_ms").all { u.optLong(it)<b.getLong(it) }
    }
    /** Reserve before dispatch; missing provider usage is conservatively estimated, never charged as zero. */
    fun reserveInference(lease: ExecutionLease, inputEstimate: Long, outputReserve: Long = 2048): Boolean = atomic {
        check(lease);val t=get(lease.taskId)!!;val b=t.document.getJSONObject("budget");val u=t.usage
        if(!available(t.id) || u.optLong("input_tokens")+inputEstimate>b.getLong("input_tokens") || u.optLong("output_tokens")+outputReserve>b.getLong("output_tokens")) return@atomic false
        u.put("input_tokens",u.optLong("input_tokens")+inputEstimate).put("output_tokens",u.optLong("output_tokens")+outputReserve).put("model_steps",u.optLong("model_steps")+1)
        t.document.put("reservation",JSONObject().put("input",inputEstimate).put("output",outputReserve));save(t.copy(leaseUntil=now()+LEASE_MS));true
    }
    fun reconcileInference(lease: ExecutionLease, input: Long?, output: Long?) = atomic {
        check(lease);val t=get(lease.taskId)!!;val r=t.document.optJSONObject("reservation") ?: return@atomic
        t.usage.put("input_tokens",maxOf(0,t.usage.optLong("input_tokens")-r.optLong("input")+(input ?: r.optLong("input"))))
            .put("output_tokens",maxOf(0,t.usage.optLong("output_tokens")-r.optLong("output")+(output ?: r.optLong("output"))))
        t.document.remove("reservation");save(t.copy(leaseUntil=now()+LEASE_MS))
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
        val last=all.groupBy { it.name }.values.mapNotNull { it.lastOrNull() }
        val failures=last.filter { call -> call.status in setOf(ToolCallStatus.FAILED,ToolCallStatus.REJECTED) || runCatching { JSONObject(call.resultJson.orEmpty()).let { it.has("valid")&&!it.optBoolean("valid") || it.has("ok")&&!it.optBoolean("ok") } }.getOrDefault(false) }
        val signature=failures.joinToString("|") { it.name+":"+(it.error ?: it.resultJson.orEmpty()).take(1000) }
        val failedThisSlice=failures.any { it.turnId==currentTurn(task.id) }
        val madeProgress=all.any { it.turnId==currentTurn(task.id) && it.status==ToolCallStatus.COMPLETED && it !in failures }
        val repeated=if(signature.isNotBlank() && (failedThisSlice || !madeProgress) && signature==task.document.optString("failure_signature")) task.document.optInt("repeated_failures")+1 else if(failedThisSlice) 1 else 0
        task.document.put("failure_signature",signature).put("repeated_failures",repeated)
        val recent=all.takeLast(20)
        val definitionId=all.lastOrNull { runCatching {JSONObject(it.argumentsJson).has("definition")}.getOrDefault(false) }?.id ?: task.document.getJSONObject("checkpoint").optString("latest_definition_operation_id").takeIf { it.isNotBlank() && it!="null" }
        task.document.put("checkpoint",JSONObject().put("last_turn_id",currentTurn(task.id)).put("operation_ids",JSONArray(recent.map {it.id}))
            .put("completed_operations",JSONArray(recent.filter {it.status==ToolCallStatus.COMPLETED}.map {it.id}))
            .put("latest_definition_operation_id",definitionId))
        val reason=when { pending -> "unknown_operation_outcome";outcome==SliceOutcome.RESPONDED&&failures.isEmpty()->"";!available(task.id)->"task_allowance";repeated>=3->"repeated_failure";outcome==SliceOutcome.RESPONDED&&failures.isNotEmpty()->"unresolved_tool_failure";else->when(outcome){SliceOutcome.WAITING_USER->"clarification";SliceOutcome.WAITING_PERMISSION->"authorization";SliceOutcome.WAITING_PROVIDER->"provider";SliceOutcome.BLOCKED->"runtime_blocker";else->""} }
        task.document.put("wait_reason",reason).put("completion_kind",if(outcome==SliceOutcome.RESPONDED&&reason.isBlank()) "response_finished; consult operation receipts for effects" else JSONObject.NULL)
        val state=if(reason.isNotBlank()) ExecutionState.WAITING else if(outcome==SliceOutcome.RESPONDED) ExecutionState.COMPLETED else ExecutionState.READY
        val result=task.copy(state=state,owner=null,leaseUntil=0,nextRunAt=0);save(result);event(result,"execution.checkpoint",JSONObject().put("outcome",outcome.name).put("checkpoint",task.document.getJSONObject("checkpoint")).put("wait_reason",reason));result
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
        val latest=calls(taskId).lastOrNull {runCatching {JSONObject(it.argumentsJson).has("definition")}.getOrDefault(false)} ?: t.document.getJSONObject("checkpoint").optString("latest_definition_operation_id").takeIf { it.isNotBlank() && it!="null" }?.let(database::loadToolCall)
        return "CORE DURABLE TASK (evidence, not authorization): " + JSONObject().put("task_id",t.id).put("objective",t.objective).put("usage",t.usage)
            .put("checkpoint",t.document.getJSONObject("checkpoint")).put("latest_authoring_arguments",latest?.let {JSONObject(it.argumentsJson)})
            .put("latest_authoring_result",latest?.resultJson?.let {runCatching {JSONObject(it)}.getOrNull()}).toString() +
            "\nContinue the pending objective from recorded receipts. A new slice is not a new user request, not new authority, and not proof of success. Avoid repeating committed effects."
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
