package com.grinningfrog.atlas.workspace

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WorkspaceChartTest {
    private fun chart(root: JSONObject) = root.getJSONArray("views").getJSONObject(0).getJSONArray("components").getJSONObject(0)
    @Test fun catalogChartIsValidAndSignedValuesShareAZeroInclusiveScale() {
        val root = WorkspaceCapabilityCatalog.chartExample()
        assertTrue(WorkspaceRuntimeV2.validate(root).errors.joinToString(), WorkspaceRuntimeV2.validate(root).valid)
        val points = WorkspaceChart.points(chart(root), emptyMap())
        val scale = WorkspaceChart.scale(points)
        assertEquals(-4.0, scale.minimum, 0.0); assertEquals(12.0, scale.maximum, 0.0)
        assertEquals(0.25f, scale.fraction(0.0), 0.0001f)
        assertEquals(0f, scale.fraction(-4.0), 0f); assertEquals(1f, scale.fraction(12.0), 0f)
        assertTrue(WorkspaceChart.scale(listOf(ChartPoint("a", "a", -Double.MAX_VALUE), ChartPoint("b", "b", Double.MAX_VALUE))).fraction(0.0).isFinite())
        assertTrue(WorkspaceChart.scale(emptyList()).fraction(0.0).isFinite())
    }
    @Test fun invalidDataAndAmbiguousBindingsAreRejected() {
        val root = WorkspaceCapabilityCatalog.chartExample(); val component = chart(root)
        component.put("collection", "missing")
        assertFalse(WorkspaceRuntimeV2.validate(root).valid)
        component.remove("collection"); component.put("points", JSONArray().put(JSONObject().put("label", "Bad").put("value", "12")))
        assertFalse(WorkspaceRuntimeV2.validate(root).valid)
        component.put("points", JSONArray((0..50).map { JSONObject().put("label", "$it").put("value", it) }))
        assertFalse(WorkspaceRuntimeV2.validate(root).valid)
    }
    @Test fun collectionOrderingAndEditsFeedTheSameDiagnosticProjection() {
        val component = JSONObject("""{"id":"chart","type":"chart","collection":"data","label_field":"name","value_field":"amount","sort_by":"amount","sort_direction":"ascending","limit":2}""")
        val rows = listOf(JSONObject("""{"_id":"a","name":"A","amount":8}"""), JSONObject("""{"_id":"b","name":"B","amount":-2}"""), JSONObject("""{"_id":"c","name":"C","amount":4}"""))
        assertEquals(listOf("b", "c"), WorkspaceChart.points(component, mapOf("data" to rows)).map { it.id })
        rows[0].put("amount", -5)
        val diagnostics = WorkspaceChart.diagnostics(component, mapOf("data" to rows))
        assertEquals("a", diagnostics.getJSONArray("points").getJSONObject(0).getString("id"))
        assertEquals(-5.0, diagnostics.getDouble("minimum"), 0.0)
    }
    @Test fun legacySectionFullCatalogIncludesRendererContractAndValidExample() {
        val contract = WorkspaceCapabilityCatalog.describe(WorkspaceDefinitionValidator.FORMAT, "section", full = true, examples = true).getJSONArray("formats").getJSONObject(0)
        assertTrue(contract.getJSONObject("component_properties").getJSONArray("section").toString().contains("children"))
        assertTrue(WorkspaceDefinitionValidator.validate(contract.getJSONArray("examples").getJSONObject(0).toString()).valid)
    }
}
