package com.grinningfrog.atlas.provider

import com.grinningfrog.atlas.model.InferenceRequest
import com.grinningfrog.atlas.model.InferenceResponse
import com.grinningfrog.atlas.model.ProviderEndpoint

interface InferenceBackend {
    suspend fun infer(endpoint: ProviderEndpoint, apiKey: String?, request: InferenceRequest): InferenceResponse
}

class InferenceUnavailableException(
    message: String,
    cause: Throwable? = null,
    /** True means a retry could duplicate inference cost or an opaque provider-side operation. */
    val outcomeAmbiguous: Boolean = false,
) : Exception(message, cause)
