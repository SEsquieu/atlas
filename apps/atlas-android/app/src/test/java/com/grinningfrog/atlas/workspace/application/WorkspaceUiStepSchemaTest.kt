package com.grinningfrog.atlas.workspace.application

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WorkspaceUiStepSchemaTest {
    @Test fun modelSchemaSeparatesActionsFromAssertions() {
        val variants=WorkspaceUiStepSchema.parameters().getJSONObject("properties").getJSONObject("steps").getJSONObject("items").getJSONArray("anyOf")
        val action=variants.getJSONObject(0).getJSONObject("properties")
        val assertion=(0 until variants.length()).map {variants.getJSONObject(it)}.first {it.getJSONObject("properties").getJSONObject("action").getJSONArray("enum").getString(0)=="assert_count"}.getJSONObject("properties")
        assertFalse(action.has("criterion"));assertTrue(assertion.has("criterion"))
        assertTrue(action.getJSONObject("action").getJSONArray("enum").toString().contains("click"))
        assertFalse(action.getJSONObject("action").getJSONArray("enum").toString().contains("assert_count"))
    }
    @Test fun exactCountsNormalizeOrRejectBeforeExecutionWithActionableRecovery() {
        for(value in listOf<Any>(0,1,1.0,"0001","1000000")) {
            val step=JSONObject().put("action","assert_count").put("value",value)
            WorkspaceUiStepSchema.normalize(step,2)
            assertTrue(step.getString("value").toInt() in 0..1000000)
        }
        for(value in listOf<Any>("at least one","",-1,1.5,JSONObject.NULL,"1000001",JSONObject())) {
            val error=runCatching {WorkspaceUiStepSchema.normalize(JSONObject().put("action","assert_count").put("value",value),2)}.exceptionOrNull()
            assertTrue(error is com.grinningfrog.atlas.runtime.StructuredToolRejection)
            assertTrue(error!!.message!!.contains("steps[2]"))
        }
    }
    @Test fun nativeObjectsAndHistoricalStringsNormalizeToTheSameExecutorValue() {
        val objectValue=JSONObject().put("name","class").put("equals","cell path")
        val native=JSONObject().put("action","assert_attribute").put("value",objectValue)
        val historical=JSONObject().put("action","assert_attribute").put("value",objectValue.toString())
        WorkspaceUiStepSchema.normalize(native,3);WorkspaceUiStepSchema.normalize(historical,3)
        assertEquals(historical.toString(),native.toString())
        val invalid=JSONObject().put("action","input").put("value",JSONObject())
        val failure=runCatching {WorkspaceUiStepSchema.normalize(invalid,4)}.exceptionOrNull()!!
        assertTrue(failure.message!!.startsWith("steps[4].value:"))
    }
}
