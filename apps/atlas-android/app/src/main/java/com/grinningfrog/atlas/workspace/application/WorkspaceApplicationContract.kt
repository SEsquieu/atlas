package com.grinningfrog.atlas.workspace.application

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** This is the package contract, not evidence that an execution substrate is installed. */
object WorkspaceApplicationContract {
    const val FORMAT = "atlas.workspace.web.v1"
    const val API_VERSION = 1
    const val MAX_PACKAGE_BYTES = 1024 * 1024
    const val MAX_FILES = 128
    const val MAX_FILE_BYTES = 256 * 1024
    const val MAX_MESSAGE_BYTES = 64 * 1024
    const val MAX_JSON_DEPTH = 32
    private val path = Regex("[A-Za-z0-9_-]+(?:/[A-Za-z0-9_.-]+)*\\.[A-Za-z0-9]+")
    private val extensions = setOf("html", "css", "js", "json", "txt", "svg")
    private val identifier = Regex("[A-Za-z][A-Za-z0-9_]{0,63}")

    fun validPath(value: String): Boolean = value.length <= 200 && value.matches(path) &&
        value.split('/').none { it == "." || it == ".." || it.startsWith('.') } &&
        value.substringAfterLast('.').lowercase() in extensions && !value.startsWith("atlas/")

    /** Rejects values that would change meaning across Kotlin, JavaScript, JSON and SQLite. */
    fun checkJson(value: Any?, depth: Int = 0) {
        require(depth <= MAX_JSON_DEPTH) { "JSON nesting exceeds $MAX_JSON_DEPTH" }
        when (value) {
            null, JSONObject.NULL -> Unit
            is String -> {
                var i = 0
                while (i < value.length) {
                    val c = value[i++]
                    if (Character.isHighSurrogate(c)) require(i < value.length && Character.isLowSurrogate(value[i++])) { "Unpaired Unicode surrogate" }
                    else require(!Character.isLowSurrogate(c)) { "Unpaired Unicode surrogate" }
                }
            }
            is Boolean -> Unit
            is Number -> {
                val number = value.toDouble()
                require(number.isFinite() && kotlin.math.abs(number) <= 9_007_199_254_740_991.0) { "Unsafe JSON number" }
            }
            is JSONObject -> value.keys().asSequence().forEach { checkJson(value.get(it), depth + 1) }
            is JSONArray -> for (i in 0 until value.length()) checkJson(value.get(i), depth + 1)
            else -> error("Value is not strict JSON")
        }
    }

    fun parseObject(raw: String, maxBytes: Int = MAX_MESSAGE_BYTES): JSONObject {
        require(raw.toByteArray(Charsets.UTF_8).size <= maxBytes) { "QUOTA_EXCEEDED" }
        var quoted = false
        var escaped = false
        var depth = 0
        for (c in raw) {
            if (quoted) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') quoted = false
            } else when (c) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= MAX_JSON_DEPTH) { "JSON nesting exceeds $MAX_JSON_DEPTH" } }
                '}', ']' -> { depth--; require(depth >= 0) { "Malformed JSON" } }
            }
        }
        require(!quoted && depth == 0) { "Malformed JSON" }
        return JSONObject(raw).also { checkJson(it) }
    }

    fun exactKeys(value: JSONObject, allowed: Set<String>, required: Set<String> = emptySet()) {
        require(value.keys().asSequence().all { it in allowed }) { "Unknown fields: ${value.keys().asSequence().filter { it !in allowed }.toList()}" }
        require(required.all { value.has(it) && !value.isNull(it) }) { "Missing required fields: ${required.filter { !value.has(it) || value.isNull(it) }}" }
    }

    fun canonical(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        is Boolean, is Number -> value.toString()
        else -> error("Value is not strict JSON")
    }

    fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun validate(bundle: JSONObject): List<String> = runCatching {
        require(bundle.toString().toByteArray(Charsets.UTF_8).size <= MAX_PACKAGE_BYTES) { "Package exceeds 1 MiB staging limit" }
        checkJson(bundle)
        exactKeys(bundle, setOf("manifest", "files"), setOf("manifest", "files"))
        val manifest = bundle.getJSONObject("manifest")
        exactKeys(manifest, setOf("format", "title", "host_api", "entry", "data_schema_version", "capabilities", "exports", "tests", "dependencies"),
            setOf("format", "title", "host_api", "entry", "data_schema_version", "capabilities", "exports", "tests", "dependencies"))
        require(manifest.get("format") == FORMAT) { "format must be $FORMAT" }
        require(manifest.get("title") is String && manifest.getString("title").length in 1..120) { "Invalid package title" }
        require(manifest.get("host_api") is Number && manifest.getDouble("host_api") == API_VERSION.toDouble()) { "UNSUPPORTED_HOST_VERSION" }
        val schemaVersion = manifest.get("data_schema_version")
        require(schemaVersion is Number && schemaVersion.toDouble() == schemaVersion.toInt().toDouble() && schemaVersion.toInt() in 1..1_000_000) { "Invalid data_schema_version" }
        val files = bundle.getJSONObject("files")
        val paths = files.keys().asSequence().toList()
        require(paths.size in 1..MAX_FILES) { "Package requires 1..$MAX_FILES files" }
        require(paths.map { it.lowercase(java.util.Locale.ROOT) }.distinct().size == paths.size) { "Case-colliding package paths" }
        for (name in paths) {
            require(validPath(name)) { "Invalid package path: $name" }
            require(files.get(name) is String) { "Files must be UTF-8 text" }
            require(files.getString(name).toByteArray(Charsets.UTF_8).size <= MAX_FILE_BYTES) { "File exceeds 256 KiB: $name" }
            require(!files.getString(name).contains('\u0000')) { "NUL in text file: $name" }
        }
        val entry = manifest.getString("entry")
        require(validPath(entry) && entry.endsWith(".html") && files.has(entry)) { "Entry must identify a packaged HTML file" }
        val capabilities = manifest.getJSONArray("capabilities")
        val requested = mutableSetOf<String>()
        for (i in 0 until capabilities.length()) {
            val cap = capabilities.getJSONObject(i)
            exactKeys(cap, setOf("name", "operations", "resources"), setOf("name", "operations", "resources"))
            require(cap.get("name") is String && cap.getString("name").matches(Regex("[a-z][a-z0-9_.]{0,63}"))) { "Invalid capability name" }
            require(requested.add(cap.getString("name"))) { "Duplicate capability request" }
            for (key in listOf("operations", "resources")) {
                val scopes = cap.getJSONArray(key)
                require(scopes.length() in 1..64) { "Capability $key requires 1..64 exact scopes" }
                for (j in 0 until scopes.length()) require(scopes.get(j) is String && scopes.getString(j).length in 1..200 && scopes.getString(j) != "*") { "Invalid capability scope" }
            }
        }
        val exports = manifest.getJSONObject("exports")
        require(exports.length() <= 64) { "Too many exports" }
        for (name in exports.keys()) {
            require(name.matches(identifier) && !name.startsWith("atlas_")) { "Invalid export name" }
            val export = exports.getJSONObject(name)
            exactKeys(export, setOf("module", "function", "input_schema", "output_schema", "mode"), setOf("module", "function", "input_schema", "output_schema", "mode"))
            require(export.getString("mode") == "pure") { "Only pure exports are supported by contract v1" }
            require(export.getString("function").matches(identifier)) { "Invalid export function" }
            require(export.getString("module").endsWith(".js") && files.has(export.getString("module"))) { "Export module is missing" }
            WorkspaceJsonSchema.check(export.getJSONObject("input_schema"))
            WorkspaceJsonSchema.check(export.getJSONObject("output_schema"))
        }
        val tests = manifest.getJSONArray("tests")
        require(tests.length() <= 128) { "Too many tests" }
        for (i in 0 until tests.length()) {
            val test = tests.getJSONObject(i)
            exactKeys(test, setOf("name", "export", "input", "expected"), setOf("name", "export"))
            require(test.has("input") && test.has("expected")) { "Missing fixture input or expected" }
            require(test.getString("name").length in 1..120 && exports.has(test.getString("export"))) { "Invalid test export or name" }
            val export = exports.getJSONObject(test.getString("export"))
            WorkspaceJsonSchema.validate(export.getJSONObject("input_schema"), test.get("input"))
            WorkspaceJsonSchema.validate(export.getJSONObject("output_schema"), test.get("expected"))
        }
        val dependencies = manifest.getJSONArray("dependencies")
        require(dependencies.length() <= 64) { "Too many dependencies" }
        for (i in 0 until dependencies.length()) {
            val dep = dependencies.getJSONObject(i)
            exactKeys(dep, setOf("path", "sha256", "source", "license"), setOf("path", "sha256", "source", "license"))
            val name = dep.getString("path")
            require(files.has(name) && dep.getString("sha256") == hash(files.getString(name))) { "Dependency hash mismatch" }
            require(dep.getString("source").length in 1..500 && dep.getString("license").length in 1..200) { "Dependency provenance required" }
        }
        emptyList<String>()
    }.getOrElse { listOf(it.message ?: "Invalid package") }

    fun manifestSummary(manifest: JSONObject): JSONObject = JSONObject()
        .put("format", manifest.optString("format")).put("title", manifest.optString("title")).put("entry", manifest.optString("entry"))
        .put("host_api", manifest.optInt("host_api")).put("data_schema_version", manifest.optInt("data_schema_version"))
        .put("export_names", JSONArray(manifest.optJSONObject("exports")?.keys()?.asSequence()?.take(16)?.toList().orEmpty()))
        .put("exports_count", manifest.optJSONObject("exports")?.length() ?: 0).put("tests_count", manifest.optJSONArray("tests")?.length() ?: 0)
        .put("manifest_source_path", "atlas/manifest.json").put("is_summary", true)

    fun descriptor(): JSONObject = JSONObject().put("format", FORMAT).put("api_version", API_VERSION)
        .put("authoring_status", "alpha_application_host").put("execution_available", true).put("activation_available", true)
        .put("minimum_android_api", 29).put("reason", "Local package execution with workspace-scoped foreground storage only; startup requires the supported contained WebView features. Foreground BLE is available through explicit manifest declarations, native workspace consent and Android permissions. Network, credentials, schema-version migrations and persistent jobs are unavailable.")
        .put("limits", JSONObject().put("package_bytes", MAX_PACKAGE_BYTES).put("files", MAX_FILES).put("file_bytes", MAX_FILE_BYTES)
            .put("message_bytes", MAX_MESSAGE_BYTES).put("json_depth", MAX_JSON_DEPTH).put("measurement_status", "provisional_staging_limits"))
        .put("presentation_contract", JSONObject()
            .put("scripts", "Use packaged external ES modules and addEventListener; inline scripts/handlers and eval are blocked by CSP. Import host/data from /atlas/host.js; do not author reserved atlas/ paths.")
            .put("startup", "Install the UI and handlers, then await host.ready(). Preview has storage_authorized=false; skip live data access there but still signal readiness. Handle host failures explicitly.")
            .put("assets", "Local fetch of exact packaged paths is supported. No external requests, browser storage, sockets, direct browser device APIs or persistent jobs. Use the declared BLE host adapter for Bluetooth LE. Use the host data API for durable records.")
            .put("updates", "Stage changed files against the inspected live base; existing package files are copied automatically. Immutable candidate IDs and revision_id pin reads. Activation reloads code and preserves records, not arbitrary JS heap state.")
            .put("export_calling_convention", "Every pure export receives exactly ONE JSON input argument. For example export function transition(input) { const {state,action}=input; ... }; browser callers must also use transition({state,action}). Update every caller when changing a shared function signature.")
            .put("storage", "Use data.query({collection,limit}) -> {records:[{id,data}],data_version,next_offset}. data.prepare({expected_data_version,request_key,mutations:[{kind:'create',collection,data}]}) -> {operation_id,...}; await data.commit(operation_id). Updates require {kind:'update',collection,record_id,data}; deletes require {kind:'delete',collection,record_id}. Query after commit to recover IDs. No id alias is accepted.")
            .put("verification", "Run 1 to 16 fixtures with every pure export covered; tests prove only those fixtures. Device packages also require workspace_package_ui_test with DOM steps exercising every declared BLE operation and notification draining. Simulation verifies only those controls with fake hardware. Activation additionally proves startup readiness, not physical device effects or save/resume. Inspect visible console diagnostics and workspace pixels. Never claim interaction or persistence verification from pure fixtures alone."))
        .put("capability_registry", WorkspaceCapabilityRegistry.describe())
        .put("device_capabilities", WorkspaceBleContract.descriptor())
        .put("schema", WorkspaceJsonSchema.describe())
        .put("manifest_contract", JSONObject()
            .put("required_fields", JSONArray(listOf("format", "title", "host_api", "entry", "data_schema_version", "capabilities", "exports", "tests", "dependencies")))
            .put("unknown_fields", "rejected")
            .put("capability_request", "{name:string,operations:exact-string[],resources:exact-string[]}; no wildcard or automatic grant")
            .put("export", "{module:packaged-js-path,function:identifier,input_schema:closed-schema,output_schema:closed-schema,mode:pure}")
            .put("test", "{name:string,export:export-key,input:JSON,expected:JSON}; structural validation does not run it")
            .put("dependency", "{path:packaged-path,sha256:exact-file-hash,source:provenance,license:license-identifier}")
            .put("file_formats", JSONArray(extensions.sorted())).put("files", "path -> UTF-8 text; reserved atlas/ paths cannot be authored"))
        .put("example_bundle", example())

    fun example(): JSONObject = JSONObject().put("manifest", JSONObject()
        .put("format", FORMAT).put("title", "My application").put("host_api", API_VERSION).put("entry", "ui/index.html")
        .put("data_schema_version", 1).put("capabilities", JSONArray()).put("exports", JSONObject().put("health", JSONObject()
            .put("module", "tools/health.js").put("function", "health").put("mode", "pure")
            .put("input_schema", JSONObject().put("type", "null")).put("output_schema", JSONObject().put("type", "boolean"))))
        .put("tests", JSONArray().put(JSONObject().put("name", "startup helper").put("export", "health").put("input", JSONObject.NULL).put("expected", true))).put("dependencies", JSONArray()))
        .put("files", JSONObject().put("ui/index.html", "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><link rel=\"stylesheet\" href=\"./style.css\"></head><body><main><h1>Notes</h1><form id=\"entry\"><input id=\"text\" maxlength=\"1000\" placeholder=\"New note\"><button>Add</button></form><p id=\"status\"></p><ul id=\"notes\"></ul></main><script type=\"module\" src=\"./app.js\"></script></body></html>")
            .put("ui/style.css", "body{background:#101010;color:#fafafa;font:16px sans-serif;margin:0}main{padding:1rem}input,button{font:inherit;padding:.7rem;border-radius:.5rem;border:1px solid #454545}input{max-width:60%;background:#222;color:white}button{background:#a8ff00}li{padding:.5rem 0}")
            .put("tools/health.js", "export function health(){return true;}")
            .put("ui/app.js", """
                import {host,data} from '/atlas/host.js';
                const status=document.querySelector('#status'), notes=document.querySelector('#notes'), form=document.querySelector('#entry');
                const info=await host.describe();
                async function refresh(){const page=await data.query({collection:'notes',limit:100});notes.replaceChildren();for(const row of page.records){const item=document.createElement('li');item.textContent=row.data.text;notes.append(item);}return page.data_version;}
                form.hidden=!info.storage_authorized;
                if(info.storage_authorized){
                  await refresh();
                  form.addEventListener('submit',async event=>{event.preventDefault();const text=document.querySelector('#text');if(!text.value.trim())return;
                    try{const version=await refresh(), request_key=Date.now().toString(36)+Math.random().toString(36).slice(2);const prepared=await data.prepare({expected_data_version:version,request_key,mutations:[{kind:'create',collection:'notes',data:{text:text.value.trim()}}]});await data.commit(prepared.operation_id);text.value='';await refresh();status.textContent='Saved';}catch(error){status.textContent=error.code||error.message;}
                  });
                }
                await host.ready();
            """.trimIndent()))

}

/** Closed subset, deliberately rejecting unsupported keywords rather than silently ignoring them. */
object WorkspaceJsonSchema {
    private val types = setOf("object", "array", "string", "integer", "number", "boolean", "null")
    fun describe() = JSONObject().put("types", JSONArray(types.sorted())).put("keywords", JSONArray(listOf("type", "properties", "required", "additionalProperties", "items", "maxItems", "maxLength", "enum")))

    fun check(schema: JSONObject, depth: Int = 0) {
        require(depth <= 16) { "Schema nesting exceeds 16" }
        WorkspaceApplicationContract.exactKeys(schema, setOf("type", "properties", "required", "additionalProperties", "items", "maxItems", "maxLength", "enum"), setOf("type"))
        val type = schema.get("type")
        require(type is String && type in types) { "Unsupported schema type" }
        if (type == "object") {
            require(schema.opt("additionalProperties") == false) { "Object schemas require additionalProperties:false" }
            val properties = schema.getJSONObject("properties")
            require(properties.length() <= 128) { "Too many schema properties" }
            properties.keys().asSequence().forEach { check(properties.getJSONObject(it), depth + 1) }
            val required = schema.getJSONArray("required")
            for (i in 0 until required.length()) require(required.get(i) is String && properties.has(required.getString(i))) { "Invalid required field" }
        } else require(listOf("properties", "required", "additionalProperties").none { schema.has(it) }) { "Object keyword on non-object schema" }
        if (type == "array") {
            check(schema.getJSONObject("items"), depth + 1)
            require(schema.has("maxItems")) { "Arrays require maxItems" }
        } else require(!schema.has("items") && !schema.has("maxItems")) { "Array keyword on non-array schema" }
        if (schema.has("maxLength")) require(type == "string") { "maxLength requires string" }
        for (key in listOf("maxItems", "maxLength")) if (schema.has(key)) {
            val value = schema.get(key)
            require(value is Number && value.toDouble() == value.toInt().toDouble() && value.toInt() in 0..10_000) { "Invalid $key" }
        }
        if (schema.has("enum")) {
            val values = schema.getJSONArray("enum")
            require(values.length() in 1..128) { "Invalid enum" }
            for (i in 0 until values.length()) {
                WorkspaceApplicationContract.checkJson(values.get(i))
                validate(JSONObject(schema.toString()).apply { remove("enum") }, values.get(i))
            }
        }
    }

    fun validate(schema: JSONObject, value: Any?, depth: Int = 0) {
        require(depth <= 32) { "Value nesting exceeds 32" }
        WorkspaceApplicationContract.checkJson(value)
        val valid = when (schema.getString("type")) {
            "object" -> value is JSONObject
            "array" -> value is JSONArray
            "string" -> value is String
            "integer" -> value is Number && value.toDouble() % 1.0 == 0.0
            "number" -> value is Number
            "boolean" -> value is Boolean
            "null" -> value == null || value == JSONObject.NULL
            else -> false
        }
        require(valid) { "Schema type mismatch: expected ${schema.getString("type")}" }
        if (value is JSONObject) {
            val props = schema.getJSONObject("properties")
            val required = schema.getJSONArray("required")
            require(value.keys().asSequence().all { props.has(it) }) { "Unknown object field" }
            for (i in 0 until required.length()) require(value.has(required.getString(i))) { "Missing required field" }
            value.keys().asSequence().forEach { validate(props.getJSONObject(it), value.get(it), depth + 1) }
        }
        if (value is JSONArray) {
            require(value.length() <= schema.getInt("maxItems")) { "Array exceeds maxItems" }
            for (i in 0 until value.length()) validate(schema.getJSONObject("items"), value.get(i), depth + 1)
        }
        if (value is String && schema.has("maxLength")) require(value.length <= schema.getInt("maxLength")) { "String exceeds maxLength" }
        if (schema.has("enum")) require((0 until schema.getJSONArray("enum").length()).any {
            WorkspaceApplicationContract.canonical(schema.getJSONArray("enum").get(it)) == WorkspaceApplicationContract.canonical(value)
        }) { "Value is outside enum" }
    }
}
