package com.grinningfrog.atlas.workspace.application

import android.content.ContentValues
import com.grinningfrog.atlas.data.AtlasDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Executes immutable pure modules without binding live records, grants, or presentation services. */
internal class WorkspaceCandidateChecks(
    private val database: AtlasDatabase,
    private val execute: suspend (JSONObject, String, Any?) -> JSONObject,
) {
    suspend fun test(workspaceId: String, realmId: String, candidateId: String, committed: (JSONObject) -> Unit = {}): JSONObject = withTimeout(20_000L) {
        val repository = WorkspacePackageRepository(database)
        val data = WorkspaceApplicationData(database)
        val stored = repository.inspect(workspaceId, realmId, candidateId)
        val base = stored.getString("base_revision_id")
        require(database.loadComposableWorkspace(workspaceId)!!.liveRevisionId == base) { "REVISION_CONFLICT" }
        val version = data.version(workspaceId)
        val bundle = stored.getJSONObject("bundle")
        val manifest = bundle.getJSONObject("manifest")
        val tests = manifest.getJSONArray("tests")
        require(tests.length() in 1..16) { "TEST_SUITE_LIMIT: require 1 to 16 fixtures per run" }
        val reports = JSONArray()
        val covered = mutableSetOf<String>()
        for (index in 0 until tests.length()) {
            val fixture = tests.getJSONObject(index)
            val name = fixture.getString("export")
            val report = JSONObject().put("name", fixture.getString("name")).put("export", name)
            try {
                // Copy per invocation: neither a worker nor a test double may alter the next fixture.
                val response = execute(JSONObject(bundle.toString()), name,
                    WorkspaceApplicationContract.parseObject("{\"value\":${WorkspaceApplicationContract.canonical(fixture.get("input"))}}").get("value"))
                val ok = response.optBoolean("ok") && response.has("value")
                if (ok) WorkspaceJsonSchema.validate(manifest.getJSONObject("exports").getJSONObject(name).getJSONObject("output_schema"), response.get("value"))
                val passed = ok && WorkspaceApplicationContract.canonical(response.get("value")) == WorkspaceApplicationContract.canonical(fixture.get("expected"))
                report.put("passed", passed)
                if (passed) covered.add(name)
                if (!passed) {
                    report.put("code", if (ok) "ASSERTION_FAILED" else response.optString("code", "EXECUTION_FAILED"))
                    report.put("expected_preview", WorkspaceApplicationContract.canonical(fixture.get("expected")).take(512))
                    if (ok) report.put("actual_preview", WorkspaceApplicationContract.canonical(response.get("value")).take(512))
                    else report.put("worker_error", response.toString().take(1200))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                report.put("passed", false).put("code", "EXECUTION_FAILED").put("summary", (failure.message ?: "Worker failed").take(300))
            }
            reports.put(report)
        }
        val exports = manifest.getJSONObject("exports").keys().asSequence().toSet()
        val passed = (0 until reports.length()).all { reports.getJSONObject(it).getBoolean("passed") } && covered.containsAll(exports)
        val id = UUID.randomUUID().toString()
        val result = JSONObject().put("check_id", id).put("candidate_id", candidateId).put("content_hash", stored.getString("content_hash"))
            .put("kind", "pure_fixtures").put("passed", passed).put("tests_run", reports.length()).put("reports", reports)
            .put("untested_exports", JSONArray((exports - covered).sorted())).put("base_revision_id", base).put("data_version", version)
            .put("activation_authorized", false).put("evidence_scope", "Declared pure fixtures; not UI, permissions, migrations, or independent application correctness")
        val executionContext = currentCoroutineContext()
        executionContext.ensureActive()
        data.atomic {
            executionContext.ensureActive()
            val current = repository.inspect(workspaceId, realmId, candidateId)
            require(database.loadComposableWorkspace(workspaceId)!!.liveRevisionId == base) { "REVISION_CONFLICT" }
            require(data.version(workspaceId) == version) { "DATA_CONFLICT" }
            require(current.getString("content_hash") == stored.getString("content_hash")) { "PACKAGE_CORRUPT" }
            val count = rawQuery("SELECT COUNT(*) FROM workspace_application_checks WHERE workspace_id=?", arrayOf(workspaceId)).use { it.moveToFirst(); it.getInt(0) }
            require(count < 256) { "QUOTA_EXCEEDED: check retention" }
            insertOrThrow("workspace_application_checks", null, ContentValues().apply {
                put("check_id", id); put("workspace_id", workspaceId); put("candidate_id", candidateId); put("content_hash", stored.getString("content_hash"))
                put("kind", "pure_fixtures"); put("passed", if (passed) 1 else 0); put("base_revision_id", base); put("data_version", version)
                put("result_json", result.toString()); put("created_at", System.currentTimeMillis())
            })
            committed(result)
        }
        result
    }
}
