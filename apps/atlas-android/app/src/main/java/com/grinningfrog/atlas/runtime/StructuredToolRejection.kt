package com.grinningfrog.atlas.runtime

import org.json.JSONObject

/** Core-owned rejection details; hints help the model correct a proposal without retrying it automatically. */
class StructuredToolRejection(message: String, val code: String, val recovery: JSONObject) : IllegalArgumentException(message)

object ToolFailurePayload {
    fun json(error: Exception, safeMessage: String): JSONObject = JSONObject().put("ok", false).put("error", safeMessage).apply {
        if (error is StructuredToolRejection) {
            put("error_code", error.code)
            put("recovery", JSONObject(error.recovery.toString()))
            put("authorization_granted", false)
            put("candidate_invoked", false)
        }
    }
}
