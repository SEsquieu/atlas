package com.grinningfrog.atlas.runtime.core

import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

data class RuntimeBundle(val version: String, val sha256: String, val source: String) {
    val id: String get() = MessageDigest.getInstance("SHA-256").digest(
        JSONArray().put("atlas.runtime.v1").put(version).put(1).put(sha256).toString().toByteArray(Charsets.UTF_8)
    ).joinToString("") { "%02x".format(it) }
    companion object {
        const val MAX_BYTES = 256 * 1024
        fun parse(raw: String, trustedKey: String? = null, builtin: Boolean = false): RuntimeBundle {
            require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Runtime bundle too large" }
            val b = JSONObject(raw)
            require(b.getString("format") == "atlas.runtime.v1" && b.get("bridge") == 1) { "Incompatible runtime bridge" }
            val version = b.getString("version")
            require(version.matches(Regex("[A-Za-z0-9._-]{1,80}"))) { "Invalid runtime version" }
            val source = b.getString("source")
            val hash = MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            require(hash == b.getString("sha256")) { "Runtime integrity check failed" }
            if (!builtin) {
                require(!trustedKey.isNullOrBlank()) { "Configure a trusted publisher first" }
                val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(trustedKey.trim())))
                val payload = JSONArray().put("atlas.runtime.v1").put(version).put(1).put(hash).toString()
                val verifier = Signature.getInstance("SHA256withECDSA")
                verifier.initVerify(key); verifier.update(payload.toByteArray(Charsets.UTF_8))
                require(verifier.verify(Base64.getDecoder().decode(b.getString("signature")))) { "Untrusted runtime signature" }
            }
            return RuntimeBundle(version, hash, source)
        }
    }
}
