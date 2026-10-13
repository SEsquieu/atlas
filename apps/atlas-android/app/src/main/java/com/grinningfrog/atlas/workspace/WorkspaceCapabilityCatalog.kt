package com.grinningfrog.atlas.workspace

import com.grinningfrog.atlas.BuildConfig
import org.json.JSONArray
import org.json.JSONObject

/** Format membership and V2 property contracts come from the same tables used by validation. */
object WorkspaceCapabilityCatalog {
    const val VERSION = 1
    private val formats = listOf(WorkspaceDefinitionValidator.FORMAT, WorkspaceRuntimeV2.FORMAT)

    fun describe(format: String? = null, component: String? = null, full: Boolean = false, examples: Boolean = false): JSONObject {
        require(format == null || format in formats) { "Unknown workspace format: $format" }
        val result = JSONObject().put("catalog_version", VERSION).put("runtime_version", BuildConfig.VERSION_NAME)
            .put("authority", "installed_runtime_contract").put("read_only", true).put("authorization_granted", false)
            .put("note", "Format support differs. A missing V2 component does not mean it is missing in V1. Native support, compositions of existing primitives, and unsupported features are distinct.")
        val contracts = JSONArray()
        for (id in formats.filter { format == null || it == format }) {
            val components = if (id == WorkspaceRuntimeV2.FORMAT) WorkspaceRuntimeV2.supportedComponents() else WorkspaceDefinitionValidator.supportedComponents()
            val contract = JSONObject().put("format", id).put("component_types", JSONArray(components))
            if (component != null) {
                contract.put("component", component).put("supported", component in components)
                contract.put("alternative_formats", JSONArray(formats.filter { other -> other != id && component in (if (other == WorkspaceRuntimeV2.FORMAT) WorkspaceRuntimeV2.supportedComponents() else WorkspaceDefinitionValidator.supportedComponents()) }))
            }
            if (id == WorkspaceRuntimeV2.FORMAT) {
                contract.put("required_structure", "format,title,entry_view,state,collections,actions,views; views contain id/title/components; action steps use type")
                    .put("action_steps", JSONArray(WorkspaceRuntimeV2.supportedSteps())).put("expression_ops", JSONArray(WorkspaceRuntimeV2.supportedExpressions()))
                    .put("field_types", JSONArray(WorkspaceRuntimeV2.supportedFieldTypes()))
                    .put("collapsible_sections", JSONObject().put("support", "composable").put("recipe", "Boolean state + toggle action + section.visible:{var:state.expanded}; no dedicated accordion component"))
                    .put("theme_tokens", JSONObject().put("support", "unsupported").put("reason", "Workspace definitions cannot override the base app theme"))
                    .put("freehand_canvas", JSONObject().put("support", "unsupported").put("alternative_format", WorkspaceDefinitionValidator.FORMAT))
                    .put("layout", JSONObject().put("native", JSONArray().put("row").put("grid").put("section")).put("grid_columns", "1..6").put("arbitrary_style_properties", "unsupported"))
                    .put("limits", JSONObject().put("definition_bytes", WorkspaceRuntimeV2.MAX_DEFINITION_BYTES).put("components", WorkspaceRuntimeV2.MAX_COMPONENTS).put("action_steps", WorkspaceRuntimeV2.MAX_ACTION_STEPS))
                if (full || component != null) contract.put("component_properties", JSONObject().apply {
                    components.filter { component == null || it == component }.forEach { put(it, JSONArray(WorkspaceRuntimeV2.supportedComponentProperties(it).sorted())) }
                })
            } else {
                contract.put("required_structure", "format,title,components; optional description; no V2 state/actions/views contract")
                    .put("freehand_canvas", JSONObject().put("support", "native").put("controls", JSONArray().put("Save drawing").put("Clear"))
                        .put("input", "basic pointer-drag strokes").put("storage", "Saved strokes persist locally under the component id; saving is explicit")
                        .put("limitations", JSONArray().put("Fixed white canvas and black strokes").put("No dedicated pressure, eraser, color or palm-rejection controls").put("Saved record limit is 32 KiB")))
                    .put("theme_tokens", JSONObject().put("support", "unsupported"))
                if (component == "canvas") contract.put("component_properties", JSONObject().put("canvas", JSONArray().put("id").put("type").put("label")))
            }
            if (examples) {
                val candidates = if (id == WorkspaceRuntimeV2.FORMAT && (component == null || component in setOf("section", "button"))) listOf(collapsibleExample())
                    else if (id == WorkspaceDefinitionValidator.FORMAT && (component == null || component == "canvas")) listOf(canvasExample()) else emptyList()
                contract.put("examples", JSONArray(candidates))
            }
            contracts.put(contract)
        }
        return result.put("formats", contracts)
    }

    fun canvasExample() = JSONObject("""{"format":"atlas.workspace.v1","title":"Drawing pad","components":[{"id":"drawing","type":"canvas","label":"Draw here"}]}""")

    fun collapsibleExample() = JSONObject("""{
        "format":"atlas.workspace.v2","title":"Details","entry_view":"main",
        "state":{"expanded":{"type":"boolean","initial":true}},"collections":{},
        "actions":{"toggleDetails":{"steps":[{"type":"toggle","key":"expanded"}]}},
        "views":[{"id":"main","title":"Details","components":[
            {"id":"showDetails","type":"button","label":"Show / hide details","action":"toggleDetails"},
            {"id":"details","type":"section","label":"Details","visible":{"var":"state.expanded"},"children":[{"id":"text","type":"text","value":"Details are visible."}]}
        ]}],
        "tests":[{"name":"Hide details","action":"toggleDetails","assert":{"op":"eq","args":[{"var":"state.expanded"},false]}},
            {"name":"Show details again","actions":["toggleDetails","toggleDetails"],"assert":{"op":"eq","args":[{"var":"state.expanded"},true]}}]
    }""")
}
