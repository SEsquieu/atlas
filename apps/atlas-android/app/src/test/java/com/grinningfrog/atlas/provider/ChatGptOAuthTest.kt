package com.grinningfrog.atlas.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGptOAuthTest {
    @Test fun pkceUsesFreshUrlSafeValuesAndS256() {
        val first = ChatGptOAuth.randomValue(64)
        val second = ChatGptOAuth.randomValue(64)
        assertTrue(first.length >= 43)
        assertFalse(first.contains('='))
        assertFalse(first == second)
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", ChatGptOAuth.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }

    @Test fun callbackParserHandlesSuccessAndDenial() {
        val success = ChatGptOAuth.parseCallback("/auth/callback?code=secret&state=expected&client_id=oaiapp_123")
        assertEquals("expected", success.state)
        assertEquals("oaiapp_123", success.clientId)
        assertEquals("secret", success.code)
        assertNull(success.error)
        val denied = ChatGptOAuth.parseCallback("/auth/callback?error=access_denied&error_description=No+thanks&state=expected")
        assertEquals("access_denied", denied.error)
        assertEquals("No thanks", denied.errorDescription)
    }

    @Test fun sensitiveOAuthValuesAreRedacted() {
        val safe = ChatGptOAuth.redact("access_token=aaa refresh_token=bbb code=ccc")
        assertFalse(safe.contains("aaa")); assertFalse(safe.contains("bbb")); assertFalse(safe.contains("ccc"))
    }
}
