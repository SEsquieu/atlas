package com.grinningfrog.atlas.workspace.application

import com.grinningfrog.atlas.runtime.StructuredToolRejection
import org.json.JSONObject

/** Lifecycle is distinct from structural validity and historical successful checks. */
internal object WorkspaceCandidateState {
    fun annotate(candidate: JSONObject, liveRevision: String, liveHash: String?): JSONObject {
        val live = candidate.optString("content_hash") == liveHash
        val testable = candidate.getString("base_revision_id") == liveRevision
        return candidate.put("live_revision_id", liveRevision).put("candidate_state", if (live) "live_content" else if (testable) "pending" else "stale_base")
            .put("testable_against_current_base", testable)
            .put("next_step", if (!testable) "Stage requested changes against live_revision_id in this same workspace. Omit parent_candidate_id to copy live source. This historical candidate cannot be retested against a different base; do not create a replacement workspace."
                else "Continue this candidate with parent_candidate_id=${candidate.getString("candidate_id")} when editing its source. Test meaningful behavior, then activate using exact matching check IDs.")
    }
    fun requireTestable(candidate: JSONObject) {
        if (!candidate.getBoolean("testable_against_current_base")) throw StructuredToolRejection(
            "REVISION_CONFLICT: historical candidate base differs from current live revision; stage changes from the current live source", "CANDIDATE_BASE_CONFLICT",
            JSONObject().put("candidate_id", candidate.getString("candidate_id")).put("base_revision_id", candidate.getString("base_revision_id"))
                .put("live_revision_id", candidate.getString("live_revision_id")).put("candidate_state", candidate.getString("candidate_state"))
                .put("next_step", candidate.getString("next_step")))
    }
}
