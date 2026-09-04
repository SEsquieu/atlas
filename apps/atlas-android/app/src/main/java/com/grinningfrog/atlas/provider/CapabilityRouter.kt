package com.grinningfrog.atlas.provider

import com.grinningfrog.atlas.model.InferenceRequest
import com.grinningfrog.atlas.model.InferenceResponse
import com.grinningfrog.atlas.model.ProviderEndpoint
import com.grinningfrog.atlas.model.RouteTable

class CapabilityRouter(
    private val backend: InferenceBackend,
    private val endpoints: () -> List<ProviderEndpoint>,
    private val routes: () -> RouteTable,
    private val apiKey: suspend (ProviderEndpoint) -> String?,
    private val onAttempt: suspend (endpoint: ProviderEndpoint, success: Boolean, error: String?) -> Unit = { _, _, _ -> },
) {
    suspend fun route(request: InferenceRequest): InferenceResponse {
        val configured = endpoints().associateBy { it.id }
        val candidates = routes().candidates(request.capability)
            .mapNotNull(configured::get)
            .filter { request.image == null || it.supportsVision }
        if (candidates.isEmpty()) throw InferenceUnavailableException("No endpoint is configured for ${request.capability.name.lowercase()}")

        val failures = mutableListOf<String>()
        for (endpoint in candidates) {
            val safeEndpoint = try {
                endpoint.copy(baseUrl = EndpointSecurity.assess(endpoint.baseUrl).normalizedBaseUrl)
            } catch (error: IllegalArgumentException) {
                val message = error.message ?: "Endpoint blocked by transport policy"
                failures += "${endpoint.name}: $message"
                onAttempt(endpoint, false, message)
                continue
            }
            try {
                val result = backend.infer(safeEndpoint, apiKey(endpoint), request)
                onAttempt(endpoint, true, null)
                return result.copy(degraded = endpoint.id !in routes().candidates(request.capability).take(1))
            } catch (error: Exception) {
                val message = error.message ?: error::class.java.simpleName
                failures += "${endpoint.name}: $message"
                onAttempt(endpoint, false, message)
            }
        }
        throw InferenceUnavailableException("All ${request.capability.name.lowercase()} routes failed: ${failures.joinToString("; ")}")
    }
}
