package com.grinningfrog.atlas.runtime

import com.grinningfrog.atlas.data.AtlasDatabase
import com.grinningfrog.atlas.model.AtlasToolCall
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Host-created dispatch identity. Proposal/audit identity never changes on continuation. */
internal data class ToolExecutionContext(
    val lease: ExecutionLease,
    val executingTurnId: String,
    val operationId: String,
    val proposalTurnId: String,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ToolExecutionContext>

    fun check(database: AtlasDatabase, call: AtlasToolCall) {
        val tasks = ExecutionTaskStore(database)
        tasks.check(lease)
        require(operationId == call.id && proposalTurnId == call.turnId) { "VISUAL_DISPATCH_CONFLICT" }
        val task = tasks.forTurn(proposalTurnId)
        require(task?.id == lease.taskId && task.sessionId == call.sessionId &&
            tasks.currentTurn(lease.taskId) == executingTurnId) { "STALE_VISUAL_SLICE" }
        val stored = database.loadToolCall(call.id)
        require(stored != null && stored.turnId == proposalTurnId && stored.sessionId == call.sessionId &&
            stored.name == call.name && stored.argumentsJson == call.argumentsJson) { "TOOL_PROPOSAL_CHANGED" }
    }
}
