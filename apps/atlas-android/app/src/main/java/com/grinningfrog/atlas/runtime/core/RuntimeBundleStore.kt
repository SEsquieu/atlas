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
    @Synchronized private fun state(): JSONObject = if (stateFile.baseFile.exists()) JSONObject(stateFile.openRead().bufferedReader().use { it.readText() }) else JSONObject()
    private fun write(file: AtomicFile, text: String) {
        val stream = file.startWrite()
        try { stream.write(text.toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }
    @Synchronized fun selected(): String = state().optString("selected", "native")
    @Synchronized fun status(): JSONObject = state().put("selected", selected()).put("builtin_id", RuntimeBundle.parse(builtin, builtin = true).id)
    @Synchronized fun selectBuiltin() {
        val bundle = RuntimeBundle.parse(builtin, builtin = true)
        val record = JSONObject().put("bundle", JSONObject(builtin)).put("bundled", true)
        val artifact = AtomicFile(File(root, bundle.id + ".json"))
        if (!artifact.baseFile.exists()) write(artifact, record.toString())
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
    @Synchronized fun resolve(id: String): RuntimeBundle {
        require(id.matches(Regex("[a-f0-9]{64}"))) { "Invalid pinned runtime" }
        val record = JSONObject(File(root, "$id.json").readText())
        return RuntimeBundle.parse(record.getJSONObject("bundle").toString(), record.optString("trusted_key"), builtin = record.optBoolean("bundled")).also { require(it.id == id) }
    }
}
