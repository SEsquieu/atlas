package com.grinningfrog.atlas.provider

import java.net.URI

enum class EndpointLocation { DEVICE, PRIVATE_NETWORK, REMOTE }

data class EndpointAssessment(
    val normalizedBaseUrl: String,
    val location: EndpointLocation,
    val encryptedInTransit: Boolean,
    val notice: String,
)

/** Product-enforced transport boundary for user-configured inference endpoints. */
object EndpointSecurity {
    fun assess(rawUrl: String): EndpointAssessment {
        val uri = runCatching { URI(rawUrl.trim()) }.getOrElse { throw IllegalArgumentException("Enter a valid endpoint URL") }
        require(uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) { "Endpoint must use http or https" }
        require(!uri.host.isNullOrBlank()) { "Endpoint URL must include a host" }
        require(uri.userInfo == null) { "Put credentials in the API key field, not the URL" }
        require(uri.fragment == null && uri.query == null) { "Endpoint URL cannot contain a query or fragment" }

        val host = uri.host.lowercase().removeSurrounding("[", "]")
        val location = when {
            host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "10.0.2.2" -> EndpointLocation.DEVICE
            isPrivateIpv4(host) || host.endsWith(".local") || host.endsWith(".lan") -> EndpointLocation.PRIVATE_NETWORK
            else -> EndpointLocation.REMOTE
        }
        val tls = uri.scheme.equals("https", true)
        require(tls || location != EndpointLocation.REMOTE) {
            "Remote endpoints must use HTTPS. Plain HTTP is allowed only for this device or a private LAN."
        }
        val notice = when {
            location == EndpointLocation.REMOTE -> "Remote HTTPS endpoint · prompts and selected image derivatives can leave this phone"
            tls -> "Private endpoint with encrypted transport"
            else -> "Private-network HTTP · traffic is not encrypted; use only on a trusted network"
        }
        return EndpointAssessment(rawUrl.trim().trimEnd('/'), location, tls, notice)
    }

    private fun isPrivateIpv4(host: String): Boolean {
        val parts = host.split('.').map { it.toIntOrNull() ?: return false }
        if (parts.size != 4 || parts.any { it !in 0..255 }) return false
        return parts[0] == 10 || parts[0] == 127 ||
            (parts[0] == 192 && parts[1] == 168) ||
            (parts[0] == 172 && parts[1] in 16..31) ||
            (parts[0] == 169 && parts[1] == 254)
    }
}
