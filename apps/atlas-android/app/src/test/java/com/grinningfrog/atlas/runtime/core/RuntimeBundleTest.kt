package com.grinningfrog.atlas.runtime.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64

class RuntimeBundleTest {
    private val keys = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    private val trusted = Base64.getEncoder().encodeToString(keys.public.encoded)
    private fun bundle(): JSONObject {
        val source = "globalThis.atlasRuntime = {};"
        val hash = MessageDigest.getInstance("SHA-256").digest(source.toByteArray()).joinToString("") { "%02x".format(it) }
        val b = JSONObject().put("format", "atlas.runtime.v1").put("version", "test-1").put("bridge", 1).put("sha256", hash).put("source", source)
        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initSign(keys.private); update(JSONArray().put("atlas.runtime.v1").put("test-1").put(1).put(hash).toString().toByteArray())
        }.sign()
        return b.put("signature", Base64.getEncoder().encodeToString(signature))
    }
    @Test fun untrustedJsonNestingIsBoundedBeforePlatformParsing() {
        assertThrows(IllegalArgumentException::class.java) { boundedRuntimeObject("[".repeat(10000) + "]".repeat(10000), 32768) }
        val source = """{["\}[]"""
        assertEquals(source, boundedRuntimeObject(JSONObject().put("source", source).toString(), 100).getString("source"))
    }
    @Test fun signedArtifactAndTamperRejection() {
        val b = bundle()
        assertEquals("test-1", RuntimeBundle.parse(b.toString(), trusted).version)
        for (field in listOf("source", "version", "sha256", "signature")) {
            val bad = JSONObject(b.toString()).put(field, "changed")
            assertThrows(Exception::class.java) { RuntimeBundle.parse(bad.toString(), trusted) }
        }
        assertThrows(Exception::class.java) { RuntimeBundle.parse(b.toString()) }
        assertThrows(Exception::class.java) { RuntimeBundle.parse(b.put("bridge", 2).toString(), trusted) }
    }
    @Test fun commandsCannotExpandHostAuthorityOrUseStaleRequest() {
        fun response(action: String = "infer") = JSONObject().put("bridge", 1).put("request_id", "a").put("action", action).put("guidance", "test")
        assertEquals("infer", CoreDecision.parse(response().toString(), "a").action)
        assertThrows(Exception::class.java) { CoreDecision.parse(response().toString(), "b") }
        assertThrows(Exception::class.java) { CoreDecision.parse(response("execute_tool").toString(), "a") }
        assertThrows(Exception::class.java) { CoreDecision.parse(response().put("permissions", "all").toString(), "a") }
    }
}
