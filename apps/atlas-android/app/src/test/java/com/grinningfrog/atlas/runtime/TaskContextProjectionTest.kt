package com.grinningfrog.atlas.runtime

import com.grinningfrog.atlas.model.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TaskContextProjectionTest {
    private fun task(operations: Int): JSONObject {
        val check=JSONObject().put("operation_id","check-exact").put("candidate_id","candidate-exact")
            .put("content_hash","hash-exact").put("passed",false).put("host_failure_count",2)
            .put("criterion_evidence",JSONArray(listOf("x".repeat(12000))))
        return JSONObject().put("task_id","task-exact").put("objective","Finish existing candidate without scrolling")
            .put("completion_requirement",JSONObject().put("delivery","activation").put("require_pixels",true))
            .put("acceptance",JSONObject().put("criteria",JSONArray(listOf("No scrolling", "Random seed"))))
            .put("checkpoint",JSONObject().put("completed_operations",JSONArray((0 until operations).map { "old-$it" }))
                .put("unresolved_operation_ids",JSONArray(listOf("unknown-operation"))))
            .put("outstanding_operations",JSONArray(listOf(JSONObject().put("operation_id","unknown-operation").put("status","UNKNOWN"))))
            .put("work_state",JSONObject().put("latest_candidate",JSONObject().put("candidate_id","candidate-exact"))
                .put("delivery_status","staged_not_activated").put("latest_checks",JSONArray(listOf(check))))
            .put("recent_operations",JSONArray((0 until 20).map { i -> JSONObject(check.toString()).put("operation_id","op-$i") }))
    }

    @Test fun compactTaskRetainsObligationsNegativeEvidenceAndExactBindingsWithoutMutatingAudit() {
        val original=task(10000); val audit=original.toString()
        val core=TaskContextProjection.sections(original,"instructions").first().text
        assertTrue(core.length<12000)
        for (fact in listOf("candidate-exact","hash-exact","unknown-operation","UNKNOWN","staged_not_activated","require_pixels","No scrolling","Random seed","\"passed\":false","\"host_failure_count\":2")) assertTrue(fact,core.contains(fact))
        assertFalse(core.contains("old-9999"))
        assertTrue(core.contains("omitted_fields")); assertTrue(core.contains("atlas_tool_result_read"))
        assertEquals(audit,original.toString())
    }

    @Test fun sessionFailureShapeFitsWholePacketAcrossHundredsOfContinuationCheckpoints() {
        val request=InferenceRequest(sessionId="s",capability=RouteCapability.REASONING,systemPrompt="p".repeat(17000),userText="Continue",
            messages=listOf(InferenceMessage(MessageRole.USER,"m".repeat(22539))),
            tools=listOf(ToolDefinition("workspace_visual_test","t".repeat(37478),"{}",ToolRisk.READ_ONLY)),
            provenance=InferenceProvenance(domain=InferenceDomain.INTERACTIVE,purpose=InferencePurpose.USER_RESPONSE,userInitiated=true))
        var largest=0
        for (slices in 1..200) {
            val sections=TaskContextProjection.sections(task(slices*50),"Never repeat unknown effects") +
                listOf(PacketSection("source_carry","s".repeat(8000)),PacketSection("diagnostics","d".repeat(4000)))
            val prepared=InferencePacketCompiler.prepare(request,sections)
            largest=maxOf(largest,prepared.diagnostics.getInt("text_characters"))
            assertTrue(prepared.request.systemPrompt.contains("unknown-operation"))
            assertTrue(largest+8192<=128000)
        }
        println("PACKET_RECOVERY_REGRESSION max_text_characters=$largest slices=200; synthetic fixture, not hardware latency")
    }
}
