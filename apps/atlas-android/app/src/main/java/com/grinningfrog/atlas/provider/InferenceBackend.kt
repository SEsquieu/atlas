package com.grinningfrog.atlas.provider

import com.grinningfrog.atlas.model.InferenceRequest
import com.grinningfrog.atlas.model.InferenceResponse
import com.grinningfrog.atlas.model.ProviderEndpoint

interface InferenceBackend {
    suspend fun infer(endpoint: ProviderEndpoint, apiKey: String?, request: InferenceRequest): InferenceResponse
}

class InferenceUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)
