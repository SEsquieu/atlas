package com.grinningfrog.atlas.workspace

import org.json.JSONArray
import org.json.JSONObject

/**
 * Deterministic, side-effect-free workspace language. Native/device effects never execute here;
 * those remain ToolHarness capabilities. This interpreter only produces bounded local mutations.
 */
object WorkspaceRuntimeV2 {
    const val FORMAT = "atlas.workspace.v2"
    const val MAX_DEFINITION_BYTES = 256 * 1024
    const val MAX_VIEWS = 32
    const val MAX_COMPONENTS = 500
    const val MAX_ACTION_STEPS = 64
    const val MAX_EXPRESSION_DEPTH = 16
    private val identifier = Regex("[A-Za-z][A-Za-z0-9_-]{0,63}")
    private val stateTypes = setOf("string", "integer", "decimal", "boolean", "timestamp", "enum")
    private val componentTypes = setOf("text", "status", "metric", "button", "input", "toggle", "list", "section", "divider", "progress")
    private val stepTypes = setOf("set", "increment", "toggle", "insert", "navigate", "sequence", "branch", "stop")
    private val expressionOps = setOf("eq", "ne", "gt", "gte", "lt", "lte", "and", "or", "not", "add", "subtract", "multiply", "divide", "concat", "if", "count")

    fun validate(root: JSONObject): WorkspaceValidationResult {
        val errors = mutableListOf<String>()
        if (root.toString().toByteArray().size > MAX_DEFINITION_BYTES) errors += "Definition exceeds 256 KiB"
        if (root.optString("format") != FORMAT) errors += "format must be $FORMAT"
        if (root.optString("title").isBlank()) errors += "title is required"

        val state = root.optJSONObject("state") ?: JSONObject()
        state.keys().forEach { key ->
            if (!key.matches(identifier)) errors += "Invalid state key: $key"
            val field = state.optJSONObject(key)
            if (field == null) errors += "state.$key must be an object"
            else {
                val type = field.optString("type")
                if (type !in stateTypes) errors += "Unsupported state type for $key: $type"
                if (!field.has("initial")) errors += "state.$key requires initial"
                else if (!valueMatchesType(field.opt("initial"), type, field.optJSONArray("values"))) errors += "state.$key initial value does not match $type"
            }
        }

        val collections = root.optJSONObject("collections") ?: JSONObject()
        collections.keys().forEach { name ->
            if (!name.matches(identifier)) errors += "Invalid collection name: $name"
            val fields = collections.optJSONObject(name)?.optJSONObject("fields")
            if (fields == null) errors += "collections.$name.fields is required"
            else fields.keys().forEach { field ->
                if (!field.matches(identifier)) errors += "Invalid field name: $name.$field"
                if (fields.optJSONObject(field)?.optString("type") !in stateTypes) errors += "Unsupported field type: $name.$field"
            }
        }

        val actions = root.optJSONObject("actions") ?: JSONObject()
        actions.keys().forEach { id ->
            if (!id.matches(identifier)) errors += "Invalid action id: $id"
            validateSteps(actions.optJSONObject(id)?.optJSONArray("steps"), "actions.$id", state, collections, errors, 0)
        }

        val views = root.optJSONArray("views")
        val viewIds = mutableSetOf<String>()
        var componentCount = 0
        if (views == null || views.length() == 0) errors += "views must contain at least one view"
        else {
            if (views.length() > MAX_VIEWS) errors += "Definition exceeds $MAX_VIEWS views"
            for (i in 0 until views.length()) {
                val view = views.optJSONObject(i)
                val id = view?.optString("id").orEmpty()
                if (!id.matches(identifier)) errors += "Invalid view id: ${id.ifBlank { "<blank>" }}"
                else if (!viewIds.add(id)) errors += "Duplicate view id: $id"
                componentCount += validateComponents(view?.optJSONArray("components"), "views[$i]", actions, state, collections, errors, 0)
            }
        }
        if (componentCount > MAX_COMPONENTS) errors += "Definition exceeds $MAX_COMPONENTS components"
        val entry = root.optString("entry_view")
        if (entry.isBlank() || entry !in viewIds) errors += "entry_view must reference an existing view"

        val tests = root.optJSONArray("tests") ?: JSONArray()
        if (tests.length() > 64) errors += "Definition exceeds 64 tests"
        for (i in 0 until tests.length()) {
            val test = tests.optJSONObject(i)
            if (test?.optString("name").isNullOrBlank()) errors += "tests[$i].name is required"
            val action = test?.optString("action").orEmpty()
            if (action.isNotBlank() && !actions.has(action)) errors += "tests[$i] references unknown action: $action"
            test?.opt("assert")?.let { validateExpression(it, "tests[$i].assert", state, collections, errors, 0) }
        }
        return WorkspaceValidationResult(errors.isEmpty(), errors.distinct())
    }

    fun initialState(root: JSONObject): JSONObject = JSONObject().also { result ->
        val state = root.optJSONObject("state") ?: JSONObject()
        state.keys().forEach { key -> result.put(key, deepCopy(state.getJSONObject(key).opt("initial"))) }
        result.put("_view", root.optString("entry_view"))
    }

    fun evaluate(expression: Any?, state: JSONObject, records: Map<String, List<JSONObject>> = emptyMap(), depth: Int = 0): Any? {
        require(depth <= MAX_EXPRESSION_DEPTH) { "Expression depth exceeded" }
        if (expression == null || expression == JSONObject.NULL || expression !is JSONObject) return expression
        expression.optString("var").takeIf(String::isNotBlank)?.let { path ->
            return when {
                path.startsWith("state.") -> state.opt(path.removePrefix("state."))
                path.startsWith("count.") -> records[path.removePrefix("count.")]?.size ?: 0
                else -> null
            }
        }
        val op = expression.optString("op")
        val args = expression.optJSONArray("args") ?: JSONArray()
        fun arg(index: Int) = evaluate(args.opt(index), state, records, depth + 1)
        fun number(value: Any?) = (value as? Number)?.toDouble() ?: value.toString().toDoubleOrNull() ?: 0.0
        fun truth(value: Any?) = value as? Boolean ?: (value != null && value != false && value != 0 && value != "")
        return when (op) {
            "eq" -> arg(0).jsonEqual(arg(1)); "ne" -> !arg(0).jsonEqual(arg(1))
            "gt" -> number(arg(0)) > number(arg(1)); "gte" -> number(arg(0)) >= number(arg(1))
            "lt" -> number(arg(0)) < number(arg(1)); "lte" -> number(arg(0)) <= number(arg(1))
            "and" -> (0 until args.length()).all { truth(arg(it)) }; "or" -> (0 until args.length()).any { truth(arg(it)) }
            "not" -> !truth(arg(0)); "add" -> number(arg(0)) + number(arg(1)); "subtract" -> number(arg(0)) - number(arg(1))
            "multiply" -> number(arg(0)) * number(arg(1)); "divide" -> number(arg(0)) / number(arg(1))
            "concat" -> (0 until args.length()).joinToString("") { arg(it)?.toString().orEmpty() }
            "if" -> if (truth(arg(0))) arg(1) else arg(2)
            "count" -> records[arg(0)?.toString()]?.size ?: 0
            else -> expression.opt("value")
        }
    }

    fun executeAction(root: JSONObject, actionId: String, state: JSONObject, records: MutableMap<String, MutableList<JSONObject>> = mutableMapOf()): WorkspaceExecutionResult {
        val validation = validate(root)
        require(validation.valid) { validation.errors.joinToString("; ") }
        val action = root.getJSONObject("actions").optJSONObject(actionId) ?: error("Unknown action: $actionId")
        val next = JSONObject(state.toString())
        val mutations = mutableListOf<WorkspaceMutation>()
        var stopped = false
        var steps = 0
        fun run(source: JSONArray, depth: Int) {
            require(depth <= 8) { "Action nesting exceeded" }
            for (i in 0 until source.length()) {
                if (stopped) return
                require(++steps <= MAX_ACTION_STEPS) { "Action step budget exceeded" }
                val step = source.getJSONObject(i)
                when (step.getString("type")) {
                    "set" -> {
                        val key = step.getString("key"); val value = evaluate(step.opt("value"), next, records)
                        requireStateValue(root, key, value)
                        next.put(key, value); mutations += WorkspaceMutation.SetState(key, value)
                    }
                    "increment" -> {
                        val key = step.getString("key"); val value = next.optDouble(key, 0.0) + (evaluate(step.opt("by"), next, records) as? Number ?: 1).toDouble()
                        val normalized: Number = if (value % 1.0 == 0.0) value.toLong() else value
                        requireStateValue(root, key, normalized)
                        next.put(key, normalized); mutations += WorkspaceMutation.SetState(key, normalized)
                    }
                    "toggle" -> { val key = step.getString("key"); val value = !next.optBoolean(key); next.put(key, value); mutations += WorkspaceMutation.SetState(key, value) }
                    "insert" -> {
                        val collection = step.getString("collection"); val data = JSONObject()
                        val sourceData = step.getJSONObject("data")
                        sourceData.keys().forEach { key -> data.put(key, evaluate(sourceData.opt(key), next, records)) }
                        requireRecord(root, collection, data)
                        records.getOrPut(collection) { mutableListOf() }.add(data); mutations += WorkspaceMutation.InsertRecord(collection, data)
                    }
                    "navigate" -> {
                        val view = step.getString("view")
                        require((0 until root.getJSONArray("views").length()).any { root.getJSONArray("views").optJSONObject(it)?.optString("id") == view }) { "Unknown view: $view" }
                        next.put("_view", view); mutations += WorkspaceMutation.Navigate(view)
                    }
                    "sequence" -> run(step.optJSONArray("steps") ?: JSONArray(), depth + 1)
                    "branch" -> run(if (truth(evaluate(step.opt("if"), next, records))) step.optJSONArray("then") ?: JSONArray() else step.optJSONArray("else") ?: JSONArray(), depth + 1)
                    "stop" -> stopped = true
                }
            }
        }
        run(action.getJSONArray("steps"), 0)
        return WorkspaceExecutionResult(next, mutations)
    }

    fun simulate(root: JSONObject): WorkspaceSimulationResult {
        val validation = validate(root)
        if (!validation.valid) return WorkspaceSimulationResult(false, validation.errors, 0)
        val failures = mutableListOf<String>()
        val tests = root.optJSONArray("tests") ?: JSONArray()
        for (i in 0 until tests.length()) {
            val test = tests.getJSONObject(i); val state = initialState(root); val records = mutableMapOf<String, MutableList<JSONObject>>()
            runCatching {
                test.optString("action").takeIf(String::isNotBlank)?.let { action ->
                    val result = executeAction(root, action, state, records); result.state.keys().forEach { state.put(it, result.state.opt(it)) }
                }
                if (!truth(evaluate(test.opt("assert"), state, records))) failures += "${test.optString("name", "test-$i")}: assertion failed"
            }.onFailure { failures += "${test.optString("name", "test-$i")}: ${it.message}" }
        }
        return WorkspaceSimulationResult(failures.isEmpty(), failures, tests.length())
    }

    private fun validateComponents(array: JSONArray?, path: String, actions: JSONObject, state: JSONObject, collections: JSONObject, errors: MutableList<String>, depth: Int): Int {
        if (array == null) { errors += "$path.components is required"; return 0 }
        if (depth > 12) { errors += "$path component nesting exceeds 12"; return 0 }
        var count = 0
        for (i in 0 until array.length()) {
            count++
            val component = array.optJSONObject(i)
            if (component == null) {
                errors += "$path.components[$i] must be an object"
                continue
            }
            val type = component.optString("type"); val id = component.optString("id")
            if (type !in componentTypes) errors += "Unsupported component type: ${type.ifBlank { "<blank>" }}"
            if (!id.matches(identifier)) errors += "Invalid component id: ${id.ifBlank { "<blank>" }}"
            component.optString("action").takeIf(String::isNotBlank)?.let { if (!actions.has(it)) errors += "$path.$id references unknown action: $it" }
            component.optString("binding").takeIf(String::isNotBlank)?.let { if (!state.has(it)) errors += "$path.$id references unknown state: $it" }
            component.optString("collection").takeIf(String::isNotBlank)?.let { if (!collections.has(it)) errors += "$path.$id references unknown collection: $it" }
            listOf("value", "visible", "enabled").forEach { key -> component.opt(key)?.takeIf { it is JSONObject }?.let { validateExpression(it, "$path.$id.$key", state, collections, errors, 0) } }
            if (component.has("children")) count += validateComponents(component.optJSONArray("children"), "$path.$id", actions, state, collections, errors, depth + 1)
        }
        return count
    }

    private fun validateSteps(array: JSONArray?, path: String, state: JSONObject, collections: JSONObject, errors: MutableList<String>, depth: Int) {
        if (array == null) { errors += "$path.steps is required"; return }
        if (array.length() > MAX_ACTION_STEPS) errors += "$path exceeds $MAX_ACTION_STEPS steps"
        if (depth > 8) { errors += "$path nesting exceeds 8"; return }
        for (i in 0 until array.length()) {
            val step = array.optJSONObject(i)
            if (step == null) {
                errors += "$path.steps[$i] must be an object"
                continue
            }
            val type = step.optString("type")
            if (type !in stepTypes) errors += "$path.steps[$i] has unsupported type: $type"
            if (type in setOf("set", "increment", "toggle") && !state.has(step.optString("key"))) errors += "$path.steps[$i] references unknown state: ${step.optString("key")}" 
            if (type == "insert" && !collections.has(step.optString("collection"))) errors += "$path.steps[$i] references unknown collection: ${step.optString("collection")}" 
            if (type == "branch") validateExpression(step.opt("if"), "$path.steps[$i].if", state, collections, errors, 0)
            if (type == "sequence") validateSteps(step.optJSONArray("steps"), "$path.steps[$i]", state, collections, errors, depth + 1)
            if (type == "branch") { validateSteps(step.optJSONArray("then") ?: JSONArray(), "$path.steps[$i].then", state, collections, errors, depth + 1); validateSteps(step.optJSONArray("else") ?: JSONArray(), "$path.steps[$i].else", state, collections, errors, depth + 1) }
        }
    }

    private fun validateExpression(value: Any?, path: String, state: JSONObject, collections: JSONObject, errors: MutableList<String>, depth: Int) {
        if (depth > MAX_EXPRESSION_DEPTH) { errors += "$path exceeds expression depth $MAX_EXPRESSION_DEPTH"; return }
        val expression = value as? JSONObject ?: return
        expression.optString("var").takeIf(String::isNotBlank)?.let { variable ->
            if (variable.startsWith("state.") && !state.has(variable.removePrefix("state."))) errors += "$path references unknown state: $variable"
            if (variable.startsWith("count.") && !collections.has(variable.removePrefix("count."))) errors += "$path references unknown collection: $variable"
            return
        }
        val op = expression.optString("op")
        if (op !in expressionOps && !expression.has("value")) errors += "$path uses unsupported expression op: $op"
        val args = expression.optJSONArray("args") ?: return
        for (i in 0 until args.length()) validateExpression(args.opt(i), "$path.args[$i]", state, collections, errors, depth + 1)
    }

    private fun valueMatchesType(value: Any?, type: String, enumValues: JSONArray?): Boolean = when (type) {
        "string" -> value is String; "integer", "timestamp" -> value is Int || value is Long
        "decimal" -> value is Number; "boolean" -> value is Boolean
        "enum" -> value is String && enumValues != null && (0 until enumValues.length()).any { enumValues.optString(it) == value }
        else -> false
    }

    private fun requireStateValue(root: JSONObject, key: String, value: Any?) {
        val schema = root.getJSONObject("state").getJSONObject(key)
        require(valueMatchesType(value, schema.getString("type"), schema.optJSONArray("values"))) { "Value for $key does not match ${schema.getString("type")}" }
    }

    private fun requireRecord(root: JSONObject, collection: String, data: JSONObject) {
        val fields = root.getJSONObject("collections").getJSONObject(collection).getJSONObject("fields")
        data.keys().forEach { require(fields.has(it)) { "Unknown field: $collection.$it" } }
        fields.keys().forEach { key ->
            val schema = fields.getJSONObject(key)
            if (!schema.optBoolean("optional") || data.has(key)) require(data.has(key) && valueMatchesType(data.opt(key), schema.getString("type"), schema.optJSONArray("values"))) { "Invalid field: $collection.$key" }
        }
    }

    private fun truth(value: Any?) = value as? Boolean ?: (value != null && value != false && value != 0 && value != "")
    private fun deepCopy(value: Any?): Any? = when (value) { is JSONObject -> JSONObject(value.toString()); is JSONArray -> JSONArray(value.toString()); else -> value }
    private fun Any?.jsonEqual(other: Any?) = when { this is Number && other is Number -> this.toDouble() == other.toDouble(); else -> this == other || this?.toString() == other?.toString() }
}

sealed interface WorkspaceMutation {
    data class SetState(val key: String, val value: Any?) : WorkspaceMutation
    data class InsertRecord(val collection: String, val data: JSONObject) : WorkspaceMutation
    data class Navigate(val view: String) : WorkspaceMutation
}

data class WorkspaceExecutionResult(val state: JSONObject, val mutations: List<WorkspaceMutation>)
data class WorkspaceSimulationResult(val passed: Boolean, val failures: List<String>, val testsRun: Int)
