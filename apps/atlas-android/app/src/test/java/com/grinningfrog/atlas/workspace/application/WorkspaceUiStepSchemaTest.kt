package com.grinningfrog.atlas.workspace.application

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WorkspaceUiStepSchemaTest {
    @Test fun modelSchemaSeparatesActionsFromAssertions() {
        val variants=WorkspaceUiStepSchema.parameters().getJSONObject("properties").getJSONObject("steps").getJSONObject("items").getJSONArray("anyOf")
        val action=variants.getJSONObject(0).getJSONObject("properties")
        val assertion=variants.getJSONObject(7).getJSONObject("properties")
        assertFalse(action.has("criterion"));assertTrue(assertion.has("criterion"))
        assertTrue(action.getJSONObject("action").getJSONArray("enum").toString().contains("click"))
        assertFalse(action.getJSONObject("action").getJSONArray("enum").toString().contains("assert_count"))
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
