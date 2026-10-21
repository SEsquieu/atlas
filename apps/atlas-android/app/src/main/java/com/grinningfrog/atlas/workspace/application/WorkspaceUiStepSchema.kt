package com.grinningfrog.atlas.workspace.application

import org.json.JSONArray
import org.json.JSONObject

/** Model-facing shapes mirror the executor; historical string values remain readable. */
internal object WorkspaceUiStepSchema {
    private val actions = listOf("click","input","select","set_checked","drag","drag_cancel","reload")
    private val assertions = listOf("assert_attribute","assert_text_exact","assert_text","wait_text","assert_value","assert_visible","assert_hidden","assert_absent","assert_count","assert_record")
    fun parameters(): JSONObject {
        fun shape(names: List<String>, assertion: Boolean): JSONObject {
            val properties=JSONObject().put("action",JSONObject().put("type","string").put("enum",JSONArray(names)))
                .put("selector",JSONObject().put("type","string").put("maxLength",128))
                .put("value",JSONObject().put("anyOf",JSONArray()
                    .put(JSONObject().put("type","string").put("maxLength",2000))
                    .put(JSONObject().put("type","object"))))
            if(assertion) properties.put("criterion",JSONObject().put("type","string").put("minLength",1).put("maxLength",200))
            return JSONObject().put("type","object").put("properties",properties)
                .put("required",JSONArray(listOf("action","selector"))).put("additionalProperties",false)
        }
        return JSONObject().put("type","object").put("additionalProperties",false)
            .put("required",JSONArray(listOf("workspace_id","candidate_id","steps")))
            .put("properties",JSONObject()
                .put("workspace_id",JSONObject().put("type","string"))
                .put("candidate_id",JSONObject().put("type","string"))
                .put("verify_storage",JSONObject().put("type","boolean"))
                .put("random_values",JSONObject().put("type","array").put("minItems",1).put("maxItems",32).put("items",JSONObject().put("type","number").put("minimum",0).put("exclusiveMaximum",1)))
                .put("steps",JSONObject().put("type","array").put("minItems",1).put("maxItems",32)
                    .put("items",JSONObject().put("anyOf",JSONArray().put(shape(actions,false)).put(shape(assertions,true))))))
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
