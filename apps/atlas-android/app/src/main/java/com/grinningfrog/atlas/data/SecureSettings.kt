package com.grinningfrog.atlas.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.grinningfrog.atlas.model.ProviderEndpoint
import com.grinningfrog.atlas.model.RouteTable
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class SecureSettings(context: Context) {
    private val prefs = context.getSharedPreferences("atlas.settings", Context.MODE_PRIVATE)

    fun saveProvider(endpoint: ProviderEndpoint, apiKey: String?) {
        val providers = loadProviders().filterNot { it.id == endpoint.id } + endpoint
        prefs.edit().putString("providers", JSONArray(providers.map(::endpointJson)).toString()).apply()
        if (!apiKey.isNullOrBlank()) SecretStore.put(endpoint.apiKeyAlias ?: endpoint.id, apiKey, prefs)
    }

    fun deleteProvider(id: String) {
        val endpoint = loadProviders().firstOrNull { it.id == id }
        prefs.edit().putString("providers", JSONArray(loadProviders().filterNot { it.id == id }.map(::endpointJson)).toString()).apply()
        SecretStore.remove(endpoint?.apiKeyAlias ?: id, prefs)
    }

    fun updateProviderCapabilities(id: String, supportsVision: Boolean, supportsTools: Boolean, supportsStreaming: Boolean) {
        val providers = loadProviders().map {
            if (it.id == id) it.copy(supportsVision = supportsVision, supportsTools = supportsTools, supportsStreaming = supportsStreaming) else it
        }
        prefs.edit().putString("providers", JSONArray(providers.map(::endpointJson)).toString()).apply()
    }

    fun loadProviders(): List<ProviderEndpoint> = runCatching {
        val array = JSONArray(prefs.getString("providers", "[]"))
        buildList {
            for (index in 0 until array.length()) {
                val json = array.getJSONObject(index)
                add(ProviderEndpoint(
                    id = json.getString("id"), name = json.getString("name"), baseUrl = json.getString("baseUrl"), model = json.getString("model"),
                    apiKeyAlias = json.optString("apiKeyAlias").ifBlank { null }, supportsVision = json.optBoolean("supportsVision"),
                    supportsTools = json.optBoolean("supportsTools"), supportsStreaming = json.optBoolean("supportsStreaming"), timeoutMs = json.optLong("timeoutMs", 60_000),
                ))
            }
        }
    }.getOrDefault(emptyList())

    fun apiKey(endpoint: ProviderEndpoint): String? = SecretStore.get(endpoint.apiKeyAlias ?: endpoint.id, prefs)

    fun putSecret(alias: String, value: String) = SecretStore.put(alias, value, prefs)
    fun secret(alias: String): String? = SecretStore.get(alias, prefs)
    fun removeSecret(alias: String) = SecretStore.remove(alias, prefs)

    fun saveRoutes(routes: RouteTable) = prefs.edit().putString("routes", JSONObject().apply {
        put("fast", JSONArray(routes.fast)); put("vision", JSONArray(routes.vision)); put("reasoning", JSONArray(routes.reasoning)); put("fallback", JSONArray(routes.fallback))
    }.toString()).apply()

    fun loadRoutes(): RouteTable = runCatching {
        val json = JSONObject(prefs.getString("routes", "{}") ?: "{}")
        RouteTable(json.strings("fast"), json.strings("vision"), json.strings("reasoning"), json.strings("fallback"))
    }.getOrDefault(RouteTable())

    private fun endpointJson(endpoint: ProviderEndpoint) = JSONObject().apply {
        put("id", endpoint.id); put("name", endpoint.name); put("baseUrl", endpoint.baseUrl); put("model", endpoint.model)
        put("apiKeyAlias", endpoint.apiKeyAlias); put("supportsVision", endpoint.supportsVision); put("supportsTools", endpoint.supportsTools)
        put("supportsStreaming", endpoint.supportsStreaming); put("timeoutMs", endpoint.timeoutMs)
    }
}

private fun JSONObject.strings(key: String): List<String> {
    val array = optJSONArray(key) ?: return emptyList()
    return buildList { for (index in 0 until array.length()) add(array.getString(index)) }
}

private object SecretStore {
    private const val KEY_ALIAS = "atlas.provider-secrets.v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    @OptIn(ExperimentalEncodingApi::class)
    fun put(alias: String, value: String, prefs: android.content.SharedPreferences) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString("secret.$alias", Base64.encode(cipher.iv) + ":" + Base64.encode(encrypted)).apply()
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun get(alias: String, prefs: android.content.SharedPreferences): String? = runCatching {
        val parts = prefs.getString("secret.$alias", null)?.split(':', limit = 2) ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0]))) }
        String(cipher.doFinal(Base64.decode(parts[1])), Charsets.UTF_8)
    }.getOrNull()

    fun remove(alias: String, prefs: android.content.SharedPreferences) = prefs.edit().remove("secret.$alias").apply()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
}
