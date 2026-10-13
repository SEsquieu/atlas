package com.grinningfrog.atlas.workspace

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WorkspaceCapabilityCatalogTest {
    @Test fun catalogMembershipAndPropertiesMatchValidatorTables() {
        val catalog = WorkspaceCapabilityCatalog.describe(full = true)
        assertEquals(1, catalog.getInt("catalog_version"))
        val formats = catalog.getJSONArray("formats")
        val v1 = formats.getJSONObject(0); val v2 = formats.getJSONObject(1)
        fun strings(array: JSONArray) = (0 until array.length()).map(array::getString)
        assertEquals(WorkspaceDefinitionValidator.supportedComponents(), strings(v1.getJSONArray("component_types")))
        assertEquals(WorkspaceRuntimeV2.supportedComponents(), strings(v2.getJSONArray("component_types")))
        assertEquals(WorkspaceRuntimeV2.supportedSteps(), strings(v2.getJSONArray("action_steps")))
        WorkspaceRuntimeV2.supportedComponents().forEach { component ->
            assertEquals(WorkspaceRuntimeV2.supportedComponentProperties(component), strings(v2.getJSONObject("component_properties").getJSONArray(component)).toSet())
        }
    }
    @Test fun missingV2CanvasReportsV1AlternativeAndNativeExample() {
        val missing = WorkspaceCapabilityCatalog.describe(WorkspaceRuntimeV2.FORMAT, "canvas").getJSONArray("formats").getJSONObject(0)
        assertFalse(missing.getBoolean("supported"))
        assertEquals(WorkspaceDefinitionValidator.FORMAT, missing.getJSONArray("alternative_formats").getString(0))
        val supported = WorkspaceCapabilityCatalog.describe(WorkspaceDefinitionValidator.FORMAT, "canvas", examples = true).getJSONArray("formats").getJSONObject(0)
        assertTrue(supported.getBoolean("supported"))
        assertEquals("native", supported.getJSONObject("freehand_canvas").getString("support"))
        val example = supported.getJSONArray("examples").getJSONObject(0)
        assertTrue(WorkspaceDefinitionValidator.validate(example.toString()).valid)
        assertEquals("canvas", example.getJSONArray("components").getJSONObject(0).getString("type"))
    }
    @Test fun showHideRecipeValidatesAndSimulatesWithoutAdvertisingThemeSupport() {
        val format = WorkspaceCapabilityCatalog.describe(WorkspaceRuntimeV2.FORMAT, "section", examples = true).getJSONArray("formats").getJSONObject(0)
        assertEquals("composable", format.getJSONObject("collapsible_sections").getString("support"))
        assertEquals("unsupported", format.getJSONObject("theme_tokens").getString("support"))
        val example = format.getJSONArray("examples").getJSONObject(0)
        val simulation = WorkspaceRuntimeV2.simulate(example)
        assertTrue(simulation.failures.joinToString(), simulation.passed)
        assertEquals(2, simulation.testsRun)
    }
    @Test fun unknownFormatFailsAndUnknownComponentRemainsAnHonestNegative() {
        assertThrows(IllegalArgumentException::class.java) { WorkspaceCapabilityCatalog.describe("future") }
        val result = WorkspaceCapabilityCatalog.describe(component = "theme_editor").getJSONArray("formats")
        assertEquals(2, result.length())
        for (i in 0 until result.length()) assertFalse(result.getJSONObject(i).getBoolean("supported"))
        val root = WorkspaceCapabilityCatalog.collapsibleExample()
        root.getJSONArray("views").getJSONObject(0).getJSONArray("components").getJSONObject(1).put("theme_token", "green")
        assertTrue(WorkspaceRuntimeV2.validate(root).errors.any { it.contains("unsupported property") })
    }
}
