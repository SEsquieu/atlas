package com.grinningfrog.atlas.workspace.visual

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WorkspaceVisualLimitsTest {
    @Test fun displayBudgetsRejectOverflowAndPathologicalDimensions() {
        WorkspaceVisualLimits.dimensions(1080,1800,420)
        for(size in listOf(4096 to 4096, Int.MAX_VALUE to Int.MAX_VALUE, 63 to 800, 1000 to 0))
            assertTrue(runCatching {WorkspaceVisualLimits.dimensions(size.first,size.second,420)}.isFailure)
        assertTrue(runCatching {WorkspaceVisualLimits.dimensions(1080,1800,0)}.isFailure)
    }
    @Test fun handlesCannotCrossTaskOwners() {
        WorkspaceVisualLimits.owns("session:task","session:task")
        assertTrue(runCatching {WorkspaceVisualLimits.owns("session:task","session:other")}.isFailure)
    }
    @Test fun rejectArbitraryCodeOsGesturesAndFalseCriterionEvidence() {
        for(step in listOf(
            JSONObject("""{"action":"evaluate","value":"document.body.remove()"}"""),
            JSONObject("""{"action":"tap","value":{"x":1,"y":2,"display_id":0}}"""),
            JSONObject("""{"action":"click","selector":"#start","criterion":"game starts"}"""),
            JSONObject("""{"action":"swipe","value":{"x":1,"y":2,"to_x":3,"to_y":4,"duration_ms":90000}}"""),
            JSONObject("""{"action":"assert_attribute","selector":"#start","value":{"name":"onclick","equals":true}}"""))) {
            assertTrue(step.toString(),runCatching {WorkspaceVisualInteractions.validate(step)}.isFailure)
        }
    }
    @Test fun countsAndAttributesUseExistingNormalization() {
        val count=JSONObject("""{"action":"assert_count","selector":"button","value":2,"criterion":"two controls"}""")
        WorkspaceVisualInteractions.validate(count);assertEquals("2",count.getString("value"))
        val attribute=JSONObject("""{"action":"assert_attribute","selector":"button","value":{"name":"aria-pressed","equals":"true"}}""")
        WorkspaceVisualInteractions.validate(attribute)
        assertEquals("true",JSONObject(attribute.getString("value")).getString("equals"))
        WorkspaceVisualInteractions.validate(JSONObject("""{"action":"tap","value":{"x":22,"y":40}}"""))
    }
}
