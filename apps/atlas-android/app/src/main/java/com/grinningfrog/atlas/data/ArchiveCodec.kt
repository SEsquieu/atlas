package com.grinningfrog.atlas.data

import com.grinningfrog.atlas.workspace.WorkspaceDefinitionValidator
import com.grinningfrog.atlas.workspace.WorkspaceRuntimeV2
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Parse and validate before touching SQLite. ZIP entries are never extracted to filesystem paths. */
data class ArchivePreview(val kind: String, val sourceId: String, val name: String, val root: JSONObject, val digest: String, val warnings: List<String>, val itemCount: Int)
data class ArchiveImportResult(val kind: String, val id: String, val duplicate: Boolean, val warnings: List<String>)

object ArchiveCodec {
    const val MAX_BYTES = 32 * 1024 * 1024
    const val MAX_ROWS = 30_000
    private fun readBounded(input: InputStream): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) { val n = input.read(buffer); if (n < 0) break; require(output.size() + n <= MAX_BYTES) { "Archive exceeds the 32 MiB import limit" }; output.write(buffer, 0, n) }
        return output.toByteArray()
    }
    fun read(input: InputStream): ArchivePreview {
        val bytes = readBounded(input)
        val json = if (bytes.size >= 2 && bytes[0] == 80.toByte() && bytes[1] == 75.toByte()) {
            var session: ByteArray? = null
            var total = 0
            val names = mutableSetOf<String>()
            ZipInputStream(bytes.inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    require(names.size < 100) { "Too many archive entries" }
                    require(names.add(entry.name)) { "Duplicate ZIP entry: ${entry.name}" }
                    require(!entry.name.startsWith("/") && !entry.name.contains("\\") && entry.name.split('/').none { it == ".." }) { "Unsafe ZIP entry name" }
                    val data = readBounded(zip); total += data.size
                    require(total <= MAX_BYTES) { "Expanded archive exceeds 32 MiB" }
                    if (entry.name == "session.json") session = data
                    zip.closeEntry(); entry = zip.nextEntry
                }
            }
            requireNotNull(session) { "Session ZIP must contain session.json" }
        } else bytes
        return preview(JSONObject(json.toString(Charsets.UTF_8)))
    }
    fun preview(root: JSONObject): ArchivePreview {
        val warnings = mutableListOf<String>()
        val kind: String; val source: String; val name: String; val count: Int
        when (root.getString("format")) {
            "atlas.workspace.bundle.v1" -> {
                kind = "workspace"
                val workspace = root.getJSONObject("workspace")
                source = workspace.getString("id"); name = workspace.getString("name")
                com.grinningfrog.atlas.workspace.WorkspaceStatus.valueOf(workspace.getString("status"))
                val definition = root.getJSONObject("revision").getJSONObject("definition")
                val validation = WorkspaceDefinitionValidator.validate(definition.toString())
                require(validation.valid) { "Workspace definition: ${validation.errors.joinToString("; ")}" }
                val records = root.getJSONArray("records"); require(records.length() <= MAX_ROWS) { "Too many workspace records" }
                val ids = mutableSetOf<String>()
                for (i in 0 until records.length()) {
                    val record = records.getJSONObject(i); require(ids.add(record.getString("id"))) { "Duplicate workspace record ID" }
                    val data = record.getJSONObject("data"); val collection = record.getString("collection")
                    if (collection == "AtlasRuntimeState") {
                        require(definition.optString("format") == WorkspaceRuntimeV2.FORMAT) { "Runtime state requires a v2 definition" }
                        val state = data.getJSONObject("state")
                        val views = definition.getJSONArray("views")
                        require(!state.has("_view") || (0 until views.length()).any { views.getJSONObject(it).getString("id") == state.getString("_view") }) { "Saved navigation references a missing view" }
                        val migration = WorkspaceRuntimeV2.migrateState(definition, state)
                        require(migration.resetKeys.isEmpty() && migration.removedKeys.isEmpty() && migration.addedKeys.isEmpty()) { "Saved runtime state does not match the workspace schema" }
                    } else if (definition.optString("format") == WorkspaceRuntimeV2.FORMAT) {
                        WorkspaceRuntimeV2.validateRecord(definition, collection, data)
                    }
                }
                if (!root.has("completeness")) warnings += "Legacy export: completeness of collection records cannot be verified."
                warnings += "Capability grants and credentials are excluded. Only the exported live revision is restored."
                count = records.length()
            }
            "atlas.session.v1" -> {
                kind = "session"
                val sessions = root.getJSONArray("session"); require(sessions.length() == 1) { "Archive must contain exactly one session" }
                val session = sessions.getJSONObject(0); source = session.getString("session_id"); name = session.getString("name")
                for (required in listOf("turns", "messages", "memory", "summaries", "toolCalls")) require(root.optJSONArray(required) != null) { "Missing required $required array" }
                val turns = rows(root, "turns"); val turnIds = uniqueIds(turns, "turn_id", source)
                for (i in 0 until turns.length()) {
                    val row = turns.getJSONObject(i)
                    com.grinningfrog.atlas.model.TurnStatus.valueOf(row.getString("status"))
                    row.getString("trigger"); row.getLong("created_at")
                }
                val messages = rows(root, "messages"); uniqueIds(messages, "message_id", source)
                val sequences = mutableSetOf<Long>()
                for (i in 0 until messages.length()) {
                    val m = messages.getJSONObject(i)
                    require(m.getString("turn_id") in turnIds) { "Message references a missing turn" }
                    require(sequences.add(m.getLong("sequence"))) { "Duplicate message sequence" }
                    require(m.getString("role") in setOf("USER", "ASSISTANT", "TOOL")) { "Unknown message role" }
                    com.grinningfrog.atlas.model.MessageKind.valueOf(m.getString("kind"))
                    com.grinningfrog.atlas.model.DeliveryStatus.valueOf(m.optString("delivery_status", "NOT_APPLICABLE"))
                    m.getString("content"); m.getLong("created_at")
                }
                for (key in listOf("toolCalls", "memory", "summaries", "events", "observations", "clarifications", "speechSegments")) rows(root, key)
                val calls = rows(root, "toolCalls")
                uniqueIds(calls, "tool_call_id", source)
                for (i in 0 until calls.length()) {
                    val row = calls.getJSONObject(i)
                    require(row.getString("turn_id") in turnIds) { "Tool call references a missing turn" }
                    com.grinningfrog.atlas.model.ToolCallStatus.valueOf(row.getString("status"))
                    com.grinningfrog.atlas.model.ToolRisk.valueOf(row.getString("risk"))
                    row.getString("name"); row.getString("arguments_json"); row.getLong("created_at"); row.getLong("updated_at")
                }
                val checkpoints = rows(root, "summaries")
                require(checkpoints.length() <= 1) { "Multiple session checkpoints" }
                if (checkpoints.length() == 1) {
                    val checkpoint = checkpoints.getJSONObject(0)
                    require(checkpoint.getString("session_id") == source) { "Cross-session checkpoint" }
                    val cutoff = checkpoint.getLong("through_message_sequence")
                    if (cutoff !in sequences || checkpoint.getString("summary").length > 8000) warnings += "Checkpoint will be discarded: invalid cutoff or oversized summary. Full transcript is retained."
                }
                val memory = rows(root, "memory"); uniqueIds(memory, "memory_id", source)
                for (i in 0 until memory.length()) {
                    val m = memory.getJSONObject(i)
                    require(m.getString("kind") in setOf("WORKING", "TASK", "ENVIRONMENT", "DURABLE")) { "Unknown memory kind" }
                    require(m.getString("status") in setOf("ACTIVE", "SUPERSEDED", "FORGOTTEN")) { "Unknown memory status" }
                    m.getString("content"); require(m.getDouble("confidence").let { it.isFinite() && it in 0.0..1.0 }) { "Invalid memory confidence" }
                }
                val embedded = root.optJSONArray("workspaces") ?: JSONArray()
                require(embedded.length() <= 100) { "Too many embedded workspaces" }
                for (i in 0 until embedded.length()) {
                    val bundle = embedded.getJSONObject(i)
                    require(bundle.optString("format") == "atlas.workspace.bundle.v1") { "Embedded entry must be a workspace" }
                    preview(bundle)
                }
                warnings += "Session opens paused; pending turns are interrupted and tool calls cannot replay."
                warnings += "Provider continuation tokens, live observations, permissions, and active clarification controls are not restored."
                warnings += "Imported memory is session-scoped; expired memories remain expired."
                warnings += "Images/audio are not included. Old events and tool outcomes are historical; observation metadata remains in the exported audit."
                if (embedded.length() == 0 && !session.isNull("active_composable_workspace_id")) warnings += "Import the matching workspace separately to reconnect it."
                count = messages.length()
            }
            else -> error("Unsupported Atlas archive format")
        }
        require(source.isNotBlank() && source.length <= 200 && name.isNotBlank() && name.length <= 200) { "Invalid archive identity" }
        val canonical = JSONObject(root.toString()).apply { remove("exportedAtMs"); remove("exported_at_ms") }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonicalJson(canonical).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return ArchivePreview(kind, source, name, JSONObject(root.toString()), digest, warnings, count)
    }
    private fun canonicalJson(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonicalJson(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonicalJson(value.get(it)) }
        is String -> JSONObject.quote(value)
        null, JSONObject.NULL -> "null"
        else -> value.toString()
    }
    fun rows(root: JSONObject, key: String): JSONArray = (root.optJSONArray(key) ?: JSONArray()).also { require(it.length() <= MAX_ROWS) { "Too many $key rows" } }
    private fun uniqueIds(rows: JSONArray, key: String, sessionId: String): Set<String> {
        val ids = mutableSetOf<String>()
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i); require(row.optString("session_id", sessionId) == sessionId) { "Cross-session record in $key" }
            require(row.getString(key).let { it.isNotBlank() && ids.add(it) }) { "Missing or duplicate $key" }
        }
        return ids
    }
}
