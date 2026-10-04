package com.grinningfrog.atlas.intent

import android.content.ContentValues
import com.grinningfrog.atlas.data.AtlasDatabase
import org.json.JSONArray
import org.json.JSONObject

class SqliteIntentStore(private val database: AtlasDatabase) : IntentStore {
    override fun loadNonTerminal(): List<IntentRecord> {
        val terminal = "'RESOLVED','ABANDONED','SUPERSEDED'"
        val corrupt = mutableListOf<Triple<String?, String, String>>()
        val valid = database.readableDatabase.rawQuery("SELECT * FROM intents WHERE state NOT IN ($terminal) ORDER BY last_updated_at DESC", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    runCatching { cursor.toIntent() }.onSuccess(::add).onFailure { error ->
                        corrupt += Triple(runCatching { cursor.string("intent_id") }.getOrNull(), cursor.rowJson().toString(), error.message ?: error.javaClass.simpleName)
                    }
                }
            }
        }
        corrupt.forEach { (id, raw, error) -> quarantine(id, raw, error) }
        return valid
    }

    private fun quarantine(intentId: String?, raw: String, error: String) {
        database.writableDatabase.insert("intent_quarantine", null, ContentValues().apply {
            put("intent_id", intentId); put("raw_json", raw); put("error", error); put("quarantined_at", System.currentTimeMillis())
        })
        intentId?.let { database.writableDatabase.delete("intents", "intent_id=?", arrayOf(it)) }
    }

    override fun save(intent: IntentRecord) {
        database.writableDatabase.insertWithOnConflict("intents", null, intent.values(), android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun recordTransition(intentId: String, from: IntentState, to: IntentState, reason: String?, atMs: Long) {
        database.writableDatabase.insertOrThrow("intent_transitions", null, ContentValues().apply {
            put("intent_id", intentId); put("from_state", from.name); put("to_state", to.name); put("reason", reason); put("at_ms", atMs)
        })
    }

    override fun recordEvaluation(evaluation: IdleEvaluationRecord) {
        database.writableDatabase.insertOrThrow("idle_evaluations", null, ContentValues().apply {
            put("evaluation_id", evaluation.id); put("at_ms", evaluation.atMs); put("autonomy_mode", evaluation.autonomyMode.name)
            put("environment_json", evaluation.environment.json().toString()); put("candidate_count", evaluation.candidateCount)
            put("eligible_count", evaluation.eligibleCount); put("ranked_json", JSONArray(evaluation.ranked.map { it.json() }).toString())
            put("decision", evaluation.decision.name); put("selected_intent_id", evaluation.selectedIntentId); put("reason", evaluation.reason)
            put("next_evaluation_at", evaluation.nextEvaluationAtMs); put("budget_usage_json", evaluation.budgetUsage.json().toString())
        })
    }

    override fun recordWorkAttempt(attempt: IdleWorkAttemptRecord) {
        database.writableDatabase.insertOrThrow("idle_work_attempts", null, ContentValues().apply {
            put("attempt_id", attempt.id); put("intent_id", attempt.intentId); put("started_at", attempt.startedAtMs)
            put("completed_at", attempt.completedAtMs); put("autonomy_mode", attempt.autonomyMode.name)
            put("inference_location", attempt.inferenceLocation.name); put("contract_json", attempt.contract.json().toString())
            put("result_json", attempt.result.json().toString())
        })
    }

    override fun loadTelemetry(): IdleTelemetry = database.readableDatabase.rawQuery(
        "SELECT value_json FROM idle_runtime_state WHERE state_key='telemetry'", null
    ).use { cursor -> if (cursor.moveToFirst()) telemetry(JSONObject(cursor.getString(0))) else IdleTelemetry() }

    override fun updateTelemetry(transform: (IdleTelemetry) -> IdleTelemetry) {
        synchronized(this) {
            val next = transform(loadTelemetry())
            database.writableDatabase.insertWithOnConflict("idle_runtime_state", null, ContentValues().apply {
                put("state_key", "telemetry"); put("value_json", next.json().toString()); put("updated_at", System.currentTimeMillis())
            }, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    override fun loadBudgetUsage(day: String): BudgetUsage = database.readableDatabase.rawQuery(
        "SELECT value_json FROM idle_runtime_state WHERE state_key=?", arrayOf("budget:$day")
    ).use { cursor -> if (cursor.moveToFirst()) budgetUsage(JSONObject(cursor.getString(0))) else BudgetUsage() }

    override fun updateBudgetUsage(day: String, transform: (BudgetUsage) -> BudgetUsage) {
        synchronized(this) {
            val next = transform(loadBudgetUsage(day))
            database.writableDatabase.insertWithOnConflict("idle_runtime_state", null, ContentValues().apply {
                put("state_key", "budget:$day"); put("value_json", next.json().toString()); put("updated_at", System.currentTimeMillis())
            }, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
        }
    }
}

private fun IntentRecord.values() = ContentValues().apply {
    put("intent_id", id); put("type", type.name); put("subject", subject); put("description", description); put("origin", origin.name)
    put("created_at", createdAtMs); put("last_updated_at", lastUpdatedAtMs); put("last_evaluated_at", lastEvaluatedAtMs); put("last_worked_at", lastWorkedAtMs)
    put("state", state.name); put("importance", importance); put("user_relevance", userRelevance); put("confidence", confidence)
    put("environmental_affinity_json", environmentalAffinity?.json()?.toString()); put("required_capabilities_json", JSONArray(requiredCapabilities).toString())
    put("required_authorities_json", JSONArray(requiredAuthorities).toString()); put("estimated_cost", estimatedCost); put("estimated_risk", estimatedRisk)
    put("attempt_count", attemptCount); put("identical_failure_count", identicalFailureCount); put("successful_step_count", successfulStepCount)
    put("information_gain", informationGain); put("progress_rate", progressRate); put("blocked_reason", blockedReason); put("next_action", nextAction)
    put("parent_intent_id", parentIntentId); put("supersedes_intent_id", supersedesIntentId); put("cooldown_until", cooldownUntilMs)
    put("metadata_json", JSONObject(metadata).toString())
}

private fun android.database.Cursor.toIntent(): IntentRecord = IntentRecord(
    id = string("intent_id")!!, type = IntentType.valueOf(string("type")!!), subject = string("subject")!!, description = string("description"),
    origin = IntentOrigin.valueOf(string("origin")!!), createdAtMs = long("created_at")!!, lastUpdatedAtMs = long("last_updated_at")!!,
    lastEvaluatedAtMs = long("last_evaluated_at"), lastWorkedAtMs = long("last_worked_at"), state = IntentState.valueOf(string("state")!!),
    importance = double("importance")!!, userRelevance = double("user_relevance")!!, confidence = double("confidence")!!,
    environmentalAffinity = string("environmental_affinity_json")?.let { affinity(JSONObject(it)) },
    requiredCapabilities = string("required_capabilities_json").set(), requiredAuthorities = string("required_authorities_json").set(),
    estimatedCost = double("estimated_cost"), estimatedRisk = double("estimated_risk"), attemptCount = int("attempt_count") ?: 0,
    identicalFailureCount = int("identical_failure_count") ?: 0, successfulStepCount = int("successful_step_count") ?: 0,
    informationGain = double("information_gain"), progressRate = double("progress_rate"), blockedReason = string("blocked_reason"), nextAction = string("next_action"),
    parentIntentId = string("parent_intent_id"), supersedesIntentId = string("supersedes_intent_id"), cooldownUntilMs = long("cooldown_until"),
    metadata = string("metadata_json")?.let { JSONObject(it) }?.let { json -> json.keys().asSequence().associateWith { json.optString(it) } }.orEmpty(),
)

private fun android.database.Cursor.rowJson() = JSONObject().apply {
    columnNames.forEachIndexed { index, name ->
        put(name, when (getType(index)) {
            android.database.Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
            android.database.Cursor.FIELD_TYPE_INTEGER -> getLong(index)
            android.database.Cursor.FIELD_TYPE_FLOAT -> getDouble(index)
            android.database.Cursor.FIELD_TYPE_BLOB -> "<blob>"
            else -> getString(index)
        })
    }
}

private fun android.database.Cursor.index(name: String) = getColumnIndexOrThrow(name)
private fun android.database.Cursor.string(name: String) = index(name).let { if (isNull(it)) null else getString(it) }
private fun android.database.Cursor.long(name: String) = index(name).let { if (isNull(it)) null else getLong(it) }
private fun android.database.Cursor.int(name: String) = index(name).let { if (isNull(it)) null else getInt(it) }
private fun android.database.Cursor.double(name: String) = index(name).let { if (isNull(it)) null else getDouble(it) }
private fun String?.set(): Set<String> = if (this == null) emptySet() else JSONArray(this).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
private fun Set<String>.array() = JSONArray(toList())
private fun EnvironmentalAffinity.json() = JSONObject().put("locations", locations.array()).put("objects", objects.array()).put("capabilities", capabilities.array()).put("networkContexts", networkContexts.array()).put("projects", projects.array()).put("requiresCharging", requiresCharging)
private fun affinity(j: JSONObject) = EnvironmentalAffinity(j.optJSONArray("locations").set(), j.optJSONArray("objects").set(), j.optJSONArray("capabilities").set(), j.optJSONArray("networkContexts").set(), j.optJSONArray("projects").set(), j.optBoolean("requiresCharging"))
private fun JSONArray?.set(): Set<String> = if (this == null) emptySet() else (0 until length()).map { getString(it) }.toSet()
private fun RuntimeEnvironment.json() = JSONObject().put("userEngagement", userEngagement.name).put("mobility", mobility.name).put("power", power.name).put("connectivity", connectivity.name).put("cognitionMode", cognitionMode.name).put("freshness", freshness.name).put("locations", locations.array()).put("objects", visibleObjects.array()).put("capabilities", capabilities.array()).put("authorities", authorities.array()).put("projects", activeProjects.array()).put("nowMs", nowMs)
private fun IntentScore.json() = JSONObject().put("intentId", intentId).put("pressure", pressure).put("components", JSONObject(components))
private fun BudgetUsage.json() = JSONObject().put("inputTokens", inputTokens).put("outputTokens", outputTokens).put("cloudCostUsd", cloudCostUsd).put("wallTimeMs", wallTimeMs).put("toolCalls", toolCalls)
private fun WorkSliceContract.json() = JSONObject().put("workAttemptId", workAttemptId).put("intentId", intentId).put("autonomyMode", autonomyMode.name).put("allowedActionClasses", JSONArray(allowedActionClasses)).put("allowedTools", JSONArray(allowedTools)).put("maxDurationMs", maxDurationMs).put("maxModelTokens", maxModelTokens).put("maxCostUsd", maxCostUsd).put("maxToolCalls", maxToolCalls).put("networkScope", networkScope).put("filesystemScope", filesystemScope).put("externalEffects", externalEffects).put("userInterruptible", userInterruptible)
private fun WorkResult.json() = JSONObject().put("outcome", outcome.name).put("summary", summary).put("informationGain", informationGain).put("progressRate", progressRate).put("completionVerified", completionVerified).put("failureSignature", failureSignature).put("usage", usage.json())
private fun budgetUsage(j: JSONObject) = BudgetUsage(j.optLong("inputTokens"), j.optLong("outputTokens"), j.optDouble("cloudCostUsd"), j.optLong("wallTimeMs"), j.optInt("toolCalls"))
private fun IdleTelemetry.json() = JSONObject().put("runtimeEvaluations", runtimeEvaluations).put("intentStateTransitions", intentStateTransitions).put("environmentalEvents", environmentalEvents).put("intentsReranked", intentsReranked).put("deterministicWorkUnits", deterministicWorkUnits).put("toolWorkUnits", toolWorkUnits).put("localModelCalls", localModelCalls).put("cloudModelCalls", cloudModelCalls).put("idleInputTokens", idleInputTokens).put("idleOutputTokens", idleOutputTokens).put("idleCloudCostUsd", idleCloudCostUsd).put("idleWallTimeMs", idleWallTimeMs)
private fun telemetry(j: JSONObject) = IdleTelemetry(j.optLong("runtimeEvaluations"), j.optLong("intentStateTransitions"), j.optLong("environmentalEvents"), j.optLong("intentsReranked"), j.optLong("deterministicWorkUnits"), j.optLong("toolWorkUnits"), j.optLong("localModelCalls"), j.optLong("cloudModelCalls"), j.optLong("idleInputTokens"), j.optLong("idleOutputTokens"), j.optDouble("idleCloudCostUsd"), j.optLong("idleWallTimeMs"))
