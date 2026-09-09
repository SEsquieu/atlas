package com.grinningfrog.atlas.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class EndpointSecurityTest {
    @Test fun `remote endpoint requires tls`() {
        assertThrows(IllegalArgumentException::class.java) { EndpointSecurity.assess("http://api.example.com/v1") }
        assertEquals(EndpointLocation.REMOTE, EndpointSecurity.assess("https://api.example.com/v1/").location)
    }

    @Test fun `private lan may use cleartext with warning`() {
        val result = EndpointSecurity.assess("http://192.168.1.40:11434/")
        assertEquals(EndpointLocation.PRIVATE_NETWORK, result.location)
        assertFalse(result.encryptedInTransit)
        assertEquals("http://192.168.1.40:11434", result.normalizedBaseUrl)
    }

    @Test fun `credentials and non http schemes are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { EndpointSecurity.assess("https://key@example.com/v1") }
        assertThrows(IllegalArgumentException::class.java) { EndpointSecurity.assess("file:///tmp/model") }
    }
}
