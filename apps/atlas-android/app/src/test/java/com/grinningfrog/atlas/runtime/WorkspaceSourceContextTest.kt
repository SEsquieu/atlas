package com.grinningfrog.atlas.runtime

import com.grinningfrog.atlas.model.*
import com.grinningfrog.atlas.workspace.application.WorkspaceApplicationContract
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WorkspaceSourceContextTest {
    private fun read(id:String, workspace:String="w", source:String="current", hashSource:String=source, start:Int=0): AtlasToolCall {
        val receipt=JSONObject().put("workspace_id",workspace).put("candidate_id","old-candidate").put("path","ui/app.js")
            .put("source",source).put("source_sha256",WorkspaceApplicationContract.hash(hashSource)).put("start_char",start)
        return AtlasToolCall(id,"s","t","workspace_package_read_file","{}",ToolCallStatus.COMPLETED,ToolRisk.READ_ONLY,false,id,
            resultJson=receipt.toString(),createdAtMs=0,updatedAtMs=0)
    }
    @Test fun onlyMatchingReadBytesSurviveCandidateChangeAndNewestDuplicateWins() {
        val calls=listOf(read("old"),read("new"),read("foreign","other"),read("changed",source="stale"),read("forged",source="forged",hashSource="current"))
        val retained=WorkspaceSourceContext.retain(calls,"w","live","new-candidate",JSONObject().put("ui/app.js","current"))
        assertEquals(1,retained.size);assertEquals("new",retained.single().getString("read_operation_id"))
        assertEquals("new-candidate",retained.single().getString("candidate_id"));assertEquals("live",retained.single().getString("base_revision_id"))
        assertEquals("current",retained.single().getString("source"))
        assertTrue(WorkspaceSourceContext.retain(calls,"w","live",null,JSONObject().put("ui/app.js","changed bytes")).isEmpty())
    }
    @Test fun partialRangesKeepOffsetsAndFailedReadsNeverEnterCache() {
        val good=read("partial",source="rr",hashSource="current",start=2)
        val bad=good.copy(id="failed",status=ToolCallStatus.FAILED)
        val retained=WorkspaceSourceContext.retain(listOf(good,bad),"w","live",null,JSONObject().put("ui/app.js","current"))
        assertEquals(1,retained.size);assertEquals(2,retained.single().getInt("start_char"))
        assertTrue(retained.single().isNull("candidate_id"));assertEquals("live",retained.single().getString("revision_id"))
    }
}
