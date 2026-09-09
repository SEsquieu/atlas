package com.grinningfrog.atlas.provider

import com.grinningfrog.atlas.model.InferenceRequest
import com.grinningfrog.atlas.model.ProviderEndpoint
import com.grinningfrog.atlas.model.ResponseContract
import com.grinningfrog.atlas.model.ResponseMode
import com.grinningfrog.atlas.model.RouteCapability

data class ProviderCheck(val ok: Boolean, val message: String, val latencyMs: Long? = null)

class ProviderConnectionTester(private val backend: InferenceBackend = OpenAiCompatibleBackend()) {
    suspend fun check(endpoint: ProviderEndpoint, apiKey: String?): ProviderCheck {
        val assessment = runCatching { EndpointSecurity.assess(endpoint.baseUrl) }
            .getOrElse { return ProviderCheck(false, it.message ?: "Invalid endpoint") }
        val testTimeoutMs = when (assessment.location) {
            EndpointLocation.DEVICE -> endpoint.timeoutMs.coerceAtLeast(60_000)
            EndpointLocation.PRIVATE_NETWORK -> endpoint.timeoutMs.coerceAtLeast(30_000)
            EndpointLocation.REMOTE -> endpoint.timeoutMs.coerceAtMost(30_000)
        }
        val safeEndpoint = endpoint.copy(baseUrl = assessment.normalizedBaseUrl, timeoutMs = testTimeoutMs, supportsStreaming = false)
        return runCatching {
            val response = backend.infer(
                safeEndpoint,
                apiKey,
                InferenceRequest(
                    sessionId = "connection-test",
                    capability = RouteCapability.FAST,
                    systemPrompt = "This is an Atlas connection test. Reply with only: ready",
                    userText = "Connection test",
                    responseContract = ResponseContract(ResponseMode.IMMEDIATE, 1, 3, 1, 8),
                ),
            )
            val tuning = if (assessment.location == EndpointLocation.DEVICE) " · on-device thinking disabled" else ""
            ProviderCheck(true, "Text connected in ${response.latencyMs} ms$tuning · ${assessment.notice}. Declared vision, tools, and streaming are not probed.", response.latencyMs)
        }.getOrElse { error ->
            ProviderCheck(false, error.message?.take(240) ?: "Connection failed")
        }
    }
}
