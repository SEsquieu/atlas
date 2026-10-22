package com.grinningfrog.atlas.runtime.core

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

/** Content addressed bundles are retained for pinned tasks. No arbitrary paths or executable DEX. */
class RuntimeBundleStore(context: Context) {
    private val root = File(context.filesDir, "runtime-core").apply { mkdirs() }
    private val stateFile = AtomicFile(File(root, "selection.json"))
    private val builtin = context.assets.open("runtime/builtin.json").bufferedReader().use { it.readText() }
    @Synchronized private fun state(): JSONObject = try {
        boundedRuntimeObject(stateFile.openRead().use { it.readBounded(4096) }, 4096)
    } catch (_: java.io.FileNotFoundException) { JSONObject() }
      catch (_: Exception) { JSONObject().put("selected", "native").put("selection_error", "Runtime selection could not be read; Native is selected for new tasks") }
    private fun write(file: AtomicFile, text: String) {
        val stream = file.startWrite()
        try { stream.write(text.toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }
    @Synchronized fun selected(): String = state().optString("selected", "native")
    @Synchronized fun status(): JSONObject = state().put("selected", selected()).put("builtin_id", RuntimeBundle.parse(builtin, builtin = true).id)
    @Synchronized fun builtinSnapshot(): RuntimeBundle {
        val bundle = RuntimeBundle.parse(builtin, builtin = true)
        val record = JSONObject().put("bundle", JSONObject(builtin)).put("bundled", true)
        val artifact = AtomicFile(File(root, bundle.id + ".json"))
        if (!artifact.baseFile.exists()) write(artifact, record.toString())
        return bundle
    }
    @Synchronized fun selectBuiltin() {
        val bundle = builtinSnapshot()
        write(stateFile, state().put("selected", bundle.id).put("version", bundle.version).toString())
    }
    @Synchronized fun selectNative() { write(stateFile, state().put("selected", "native").toString()) }
    @Synchronized fun install(raw: String, trustedKey: String): RuntimeBundle {
        val bundle = RuntimeBundle.parse(raw, trustedKey)
        val old = state()
        // Persist the trust material with this verified artifact; changing publisher cannot invalidate pinned tasks.
        val record = JSONObject().put("bundle", JSONObject(raw)).put("trusted_key", trustedKey.trim())
        write(AtomicFile(File(root, bundle.id + ".json")), record.toString())
        write(stateFile, old.put("selected", bundle.id).put("version", bundle.version).toString())
        return bundle
    }
    @Synchronized fun saveProposal(call: com.grinningfrog.atlas.model.AtlasToolCall, parentId: String, bundle: RuntimeBundle, reason: String, qualification: JSONObject): JSONObject {
        require(qualification.getBoolean("passed") && qualification.getString("runtime_id") == bundle.id && qualification.getInt("verification_version") == 1)
        val id = java.security.MessageDigest.getInstance("SHA-256").digest(call.id.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val record = JSONObject().put("proposal_id", id).put("session_id", call.sessionId).put("parent_runtime_id", parentId)
            .put("runtime_id", bundle.id).put("version", bundle.version).put("source_sha256", bundle.sha256).put("source", bundle.source)
            .put("reason", reason).put("qualification", qualification).put("operation_id", call.id)
        val file = AtomicFile(File(root, "proposal-$id.json"))
        if (file.baseFile.exists()) {
            val previous = proposal(id, call.sessionId)
            require(previous.getString("runtime_id") == bundle.id && previous.getString("parent_runtime_id") == parentId) { "PROPOSAL_CONFLICT" }
            return previous
        }
        write(file, record.toString())
        return record
    }
    @Synchronized fun proposal(id: String, sessionId: String): JSONObject {
        require(id.matches(Regex("[a-f0-9]{64}"))) { "Invalid runtime proposal ID" }
        val record = AtomicFile(File(root, "proposal-$id.json")).openRead().use { boundedRuntimeObject(it.readBounded(RuntimeBundle.MAX_BYTES * 2), RuntimeBundle.MAX_BYTES * 2) }
        require(record.getString("session_id") == sessionId && record.getString("proposal_id") == id) { "Runtime proposal belongs to another session" }
        val bundle = RuntimeBundle.local(record.getString("version"), record.getString("source"))
        require(bundle.id == record.getString("runtime_id") && bundle.sha256 == record.getString("source_sha256")) { "Runtime proposal corrupt" }
        val qualification = record.getJSONObject("qualification")
        require(qualification.getBoolean("passed") && qualification.getString("runtime_id") == bundle.id && qualification.getInt("verification_version") == 1) { "Runtime qualification missing" }
        return record
    }
    @Synchronized fun activateProposal(call: com.grinningfrog.atlas.model.AtlasToolCall, id: String, expectedSelected: String): JSONObject {
        require(call.status == com.grinningfrog.atlas.model.ToolCallStatus.APPROVED && call.requiresConfirmation) { "USER_CONFIRMATION_REQUIRED" }
        val record = proposal(id, call.sessionId)
        val bundle = RuntimeBundle.local(record.getString("version"), record.getString("source"))
        require(selected() == expectedSelected || selected() == bundle.id) { "RUNTIME_SELECTION_CHANGED: reread status and request confirmation again" }
        val artifact = AtomicFile(File(root, bundle.id + ".json"))
        if (!artifact.baseFile.exists()) write(artifact, JSONObject().put("bundle", JSONObject().put("format", "atlas.runtime.v1").put("bridge", 1)
            .put("version", bundle.version).put("sha256", bundle.sha256).put("source", bundle.source)).put("locally_approved", true)
            .put("approval_operation_id", call.id).put("parent_runtime_id", record.getString("parent_runtime_id")).toString())
        resolve(bundle.id)
        write(stateFile, state().put("selected", bundle.id).put("version", bundle.version).toString())
        return JSONObject().put("ok", true).put("proposal_id", id).put("parent_runtime_id", record.getString("parent_runtime_id")).put("selected_runtime_id", bundle.id).put("version", bundle.version)
            .put("selected_for_new_tasks", true).put("current_task_changed", false).put("native_code_changed", false)
    }
    @Synchronized fun resolve(id: String): RuntimeBundle {
        require(id.matches(Regex("[a-f0-9]{64}"))) { "Invalid pinned runtime" }
        val record = File(root, "$id.json").inputStream().use { boundedRuntimeObject(it.readBounded(RuntimeBundle.MAX_BYTES * 2), RuntimeBundle.MAX_BYTES * 2) }
        return RuntimeBundle.parse(record.getJSONObject("bundle").toString(), record.optString("trusted_key"), builtin = record.optBoolean("bundled") || (record.optBoolean("locally_approved") && record.optString("approval_operation_id").isNotBlank())).also { require(it.id == id) }
    }
}

private fun java.io.InputStream.readBounded(limit: Int): String {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        require(output.size() + count <= limit) { "Stored runtime record too large" }
        output.write(buffer, 0, count)
    }
    return output.toString("UTF-8")
}
