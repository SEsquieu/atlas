package com.grinningfrog.atlas.workspace.application

import org.json.JSONArray
import org.json.JSONObject

/** Model-facing shapes mirror the executor; historical string values remain readable. */
internal object WorkspaceUiStepSchema {
    private val actions = listOf("click","input","select","set_checked","drag","drag_cancel","reload")
    private val assertions = listOf("assert_attribute","assert_text_exact","assert_text","wait_text","assert_value","assert_visible","assert_hidden","assert_absent","assert_count","assert_record")
    fun parameters(): JSONObject {
        fun shape(action: String): JSONObject {
            val properties = JSONObject().put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf(action))))
                .put("selector", JSONObject().put("type", "string").put("maxLength", 128))
            val stringValue = JSONObject().put("type", "string").put("maxLength", 2000)
            val objectFields = when (action) {
                "drag", "drag_cancel" -> JSONObject()
                    .put("dx", JSONObject().put("type", "number").put("minimum", -1000).put("maximum", 1000))
                    .put("dy", JSONObject().put("type", "number").put("minimum", -1000).put("maximum", 1000))
                    .put("steps", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 20))
                "assert_attribute" -> JSONObject().put("name", JSONObject().put("type", "string"))
                    .put("equals", JSONObject().put("type", "string"))
                "assert_record" -> JSONObject().put("collection", JSONObject().put("type", "string"))
                    .put("data", JSONObject().put("type", "object"))
                else -> null
            }
            if (objectFields != null) {
                val required = when (action) {
                    "drag", "drag_cancel" -> listOf("dx", "dy")
                    "assert_attribute" -> listOf("name", "equals")
                    else -> listOf("collection", "data")
                }
                properties.put("value", JSONObject().put("anyOf", JSONArray().put(stringValue).put(
                    JSONObject().put("type", "object").put("properties", objectFields)
                        .put("required", JSONArray(required)).put("additionalProperties", false))))
            } else if (action !in setOf("click", "reload", "assert_visible", "assert_hidden", "assert_absent")) {
                if (action == "set_checked") stringValue.put("enum", JSONArray(listOf("true", "false")))
                properties.put("value", stringValue)
            }
            if (action in assertions) properties.put("criterion", JSONObject().put("type", "string").put("minLength", 1).put("maxLength", 200))
            val required = mutableListOf("action", "selector")
            if (properties.has("value")) required += "value"
            return JSONObject().put("type", "object").put("properties", properties)
                .put("required", JSONArray(required)).put("additionalProperties", false)
        }
        return JSONObject().put("type","object").put("additionalProperties",false)
            .put("required",JSONArray(listOf("workspace_id","candidate_id","steps")))
            .put("properties",JSONObject()
                .put("workspace_id",JSONObject().put("type","string"))
                .put("candidate_id",JSONObject().put("type","string"))
                .put("verify_storage",JSONObject().put("type","boolean"))
                .put("random_values",JSONObject().put("type","array").put("minItems",1).put("maxItems",32).put("items",JSONObject().put("type","number").put("minimum",0).put("exclusiveMaximum",1)))
                .put("steps",JSONObject().put("type","array").put("minItems",1).put("maxItems",32)
                    .put("items",JSONObject().put("anyOf",JSONArray((actions + assertions).map(::shape))))))
    }
    fun normalize(step: JSONObject, index: Int) {
        val value=step.opt("value")
        if(value is JSONObject) {
            require(step.optString("action") in setOf("drag","drag_cancel","assert_attribute","assert_record")) {
                "steps[$index].value: object values are supported only for drag, drag_cancel, assert_attribute and assert_record"
            }
            step.put("value",value.toString())
        }
    }
}
