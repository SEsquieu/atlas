package com.grinningfrog.atlas.provider

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.grinningfrog.atlas.data.SecureSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.RSAPublicKeySpec
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

sealed interface ChatGptAuthState {
    data object Disconnected : ChatGptAuthState
    data object Authenticating : ChatGptAuthState
    data class Connected(val email: String?, val model: String, val models: List<ChatGptModel>) : ChatGptAuthState
    data object Refreshing : ChatGptAuthState
    data class AuthorizationRequired(val message: String) : ChatGptAuthState
    data class EntitlementUnavailable(val message: String) : ChatGptAuthState
    data class Error(val message: String) : ChatGptAuthState
}

data class ChatGptModel(val slug: String, val displayName: String)

data class ChatGptCredential(
    val clientId: String,
    val subject: String,
    val email: String?,
    val accessToken: String,
    val refreshToken: String,
    val idToken: String,
    val scopes: Set<String>,
    val expiresAtMs: Long,
    val earliestRefreshAtMs: Long,
    val models: List<ChatGptModel>,
    val selectedModel: String,
)

data class OAuthCallback(val code: String?, val state: String?, val clientId: String?, val error: String?, val errorDescription: String?)

fun interface ChatGptTokenProvider {
    suspend fun accessToken(forceRefresh: Boolean): String
    fun onSessionInvalidated(callback: () -> Unit): AutoCloseable = AutoCloseable { }
}

object ChatGptOAuth {
    const val AUTHORIZATION_ENDPOINT = "https://auth.openai.com/api/accounts/authorize"
    const val TOKEN_ENDPOINT = "https://auth.openai.com/api/accounts/oauth/token"
    const val JWKS_ENDPOINT = "https://auth.openai.com/.well-known/jwks.json"
    const val DISCOVERY_ENDPOINT = "https://auth.openai.com/.well-known/openid-configuration"
    const val ISSUER = "https://auth.openai.com"
    const val RESOURCE = "https://api.openai.com/v1"
    const val REQUIRED_SCOPE = "chatgpt.tokens.use.direct"
    const val SCOPES = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"

    @OptIn(ExperimentalEncodingApi::class)
    fun randomValue(bytes: Int = 32): String = ByteArray(bytes).also(java.security.SecureRandom()::nextBytes)
        .let { Base64.UrlSafe.encode(it).trimEnd('=') }

    @OptIn(ExperimentalEncodingApi::class)
    fun challenge(verifier: String): String = MessageDigest.getInstance("SHA-256")
        .digest(verifier.toByteArray(StandardCharsets.US_ASCII))
        .let { Base64.UrlSafe.encode(it).trimEnd('=') }

    fun parseCallback(target: String): OAuthCallback {
        val uri = java.net.URI(target)
        val query = uri.rawQuery.orEmpty().split('&').filter(String::isNotBlank).associate { part ->
            val pieces = part.split('=', limit = 2)
            URLDecoder.decode(pieces[0], "UTF-8") to URLDecoder.decode(pieces.getOrElse(1) { "" }, "UTF-8")
        }
        return OAuthCallback(query["code"], query["state"], query["client_id"], query["error"], query["error_description"])
    }

    fun redact(message: String): String = message
        .replace(Regex("(?i)(access_token|refresh_token|id_token|code|code_verifier)=?[^&\\s\"]+"), "$1=<redacted>")
        .take(300)
}

class ChatGptAuthManager(
    context: Context,
    private val settings: SecureSettings,
    private val client: OkHttpClient = OkHttpClient(),
    private val now: () -> Long = System::currentTimeMillis,
) : ChatGptTokenProvider {
    private val appContext = context.applicationContext
    private val authorizationMutex = Mutex()
    private val refreshMutex = Mutex()
    private val invalidationCallbacks = CopyOnWriteArraySet<() -> Unit>()
    private val _state = MutableStateFlow<ChatGptAuthState>(initialState())
    val state: StateFlow<ChatGptAuthState> = _state

    suspend fun authorize(timeoutMs: Long = 180_000): ChatGptAuthState = authorizationMutex.withLock { withContext(Dispatchers.IO) {
        _state.value = ChatGptAuthState.Authenticating
        runCatching {
            val saved = loadCredential()
            val state = ChatGptOAuth.randomValue()
            val nonce = ChatGptOAuth.randomValue()
            val verifier = ChatGptOAuth.randomValue(64)
            LoopbackCallbackServer().use { listener ->
                val redirect = listener.redirectUri
                val initial = saved == null || saved.clientId.isBlank()
                val clientId = if (initial) "dynamic_agent_client" else saved!!.clientId
                val url = ChatGptOAuth.AUTHORIZATION_ENDPOINT.toHttpUrl().newBuilder()
                    .addQueryParameter("client_id", clientId)
                    .apply { if (initial) addQueryParameter("agent_name_hint", "Atlas") }
                    .addQueryParameter("ext_agent_host_id", hostId())
                    .apply {
                        if (!initial && saved?.idToken?.isNotBlank() == true) addQueryParameter("id_token_hint", saved.idToken)
                        if (!initial && !saved?.email.isNullOrBlank()) addQueryParameter("login_hint", saved?.email)
                    }
                    .addQueryParameter("response_type", "code")
                    .addQueryParameter("redirect_uri", redirect)
                    .addQueryParameter("scope", ChatGptOAuth.SCOPES)
                    .addQueryParameter("resource", ChatGptOAuth.RESOURCE)
                    .addQueryParameter("state", state)
                    .addQueryParameter("nonce", nonce)
                    .addQueryParameter("code_challenge_method", "S256")
                    .addQueryParameter("code_challenge", ChatGptOAuth.challenge(verifier))
                    .apply { if (!initial && ChatGptOAuth.REQUIRED_SCOPE !in saved!!.scopes) addQueryParameter("prompt", "consent") }
                    .build()
                appContext.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url.toString())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                val callback = withTimeout(timeoutMs) { listener.awaitCallback(timeoutMs) }
                require(callback.state == state) { "The authorization callback could not be verified." }
                if (callback.error != null) throw AuthorizationDenied(callback.errorDescription ?: "Authorization was denied.")
                val issuedClientId = if (initial) callback.clientId?.takeIf { it != "dynamic_agent_client" }
                    ?: error("OpenAI did not return an issued client registration.")
                else {
                    if (callback.clientId != null && callback.clientId != saved!!.clientId) error("OpenAI returned a different client registration.")
                    saved!!.clientId
                }
                val token = exchangeCode(issuedClientId, requireNotNull(callback.code), verifier, redirect)
                val identity = validateIdToken(token.idToken, issuedClientId, nonce)
                if (saved != null && saved.subject.isNotBlank() && saved.subject != identity.subject) error("The selected ChatGPT account did not match the saved registration.")
                if (ChatGptOAuth.REQUIRED_SCOPE !in token.scopes) {
                    val record = token.toCredential(issuedClientId, identity, emptyList(), "")
                    check(saveCredential(record)) { "Atlas could not securely save the ChatGPT authorization." }
                    return@runCatching ChatGptAuthState.EntitlementUnavailable("Signed in, but ChatGPT plan inference permission was not granted.")
                }
                check(saveCredential(token.toCredential(issuedClientId, identity, emptyList(), ""))) {
                    "Atlas could not securely save the ChatGPT authorization."
                }
                val models = fetchModels(token.accessToken)
                if (models.isEmpty()) return@runCatching ChatGptAuthState.EntitlementUnavailable("This account did not return any eligible ChatGPT plan models.")
                val selected = saved?.selectedModel?.takeIf { prior -> models.any { it.slug == prior } } ?: models.first().slug
                val credential = token.toCredential(issuedClientId, identity, models, selected)
                check(saveCredential(credential)) { "Atlas could not securely save the ChatGPT authorization." }
                ChatGptAuthState.Connected(identity.email, selected, models)
            }
        }.getOrElse { error ->
            when (error) {
                is AuthorizationDenied -> ChatGptAuthState.AuthorizationRequired(error.message ?: "Authorization was denied.")
                is kotlinx.coroutines.TimeoutCancellationException -> ChatGptAuthState.Error("ChatGPT authorization timed out.")
                else -> ChatGptAuthState.Error(ChatGptOAuth.redact(error.message ?: "ChatGPT authorization failed."))
            }
        }.also { _state.value = it }
    } }

    override suspend fun accessToken(forceRefresh: Boolean): String = refreshMutex.withLock {
        val current = loadCredential() ?: throw InferenceUnavailableException("ChatGPT authorization is required")
        if (ChatGptOAuth.REQUIRED_SCOPE !in current.scopes) throw InferenceUnavailableException("ChatGPT plan inference is not authorized")
        if (!forceRefresh && current.accessToken.isNotBlank() && now() < current.expiresAtMs - 60_000) return@withLock current.accessToken
        _state.value = ChatGptAuthState.Refreshing
        val refreshed = runCatching { refresh(current) }.getOrElse { error ->
            val message = ChatGptOAuth.redact(error.message ?: "ChatGPT authorization refresh failed")
            if (message.contains("invalid_grant", true) || message.contains("refresh_token", true)) {
                saveCredential(current.copy(accessToken = "", refreshToken = "", idToken = "", expiresAtMs = 0))
                _state.value = ChatGptAuthState.AuthorizationRequired("ChatGPT authorization expired. Sign in again.")
            } else _state.value = ChatGptAuthState.Error(message)
            throw InferenceUnavailableException(message, error)
        }
        check(saveCredential(refreshed)) { "Could not atomically rotate ChatGPT credentials" }
        _state.value = ChatGptAuthState.Connected(refreshed.email, refreshed.selectedModel, refreshed.models)
        refreshed.accessToken
    }

    suspend fun disconnect(): Boolean = withContext(Dispatchers.IO) {
        invalidationCallbacks.forEach { runCatching(it) }
        val current = loadCredential()
        var remotelyRevoked = true
        if (current != null && current.refreshToken.isNotBlank()) remotelyRevoked = runCatching {
            val discovery = JSONObject(get(ChatGptOAuth.DISCOVERY_ENDPOINT))
            val endpoint = discovery.getString("revocation_endpoint")
            val response = client.newCall(Request.Builder().url(endpoint).post(FormBody.Builder()
                .add("token", current.refreshToken).add("token_type_hint", "refresh_token").add("client_id", current.clientId).build()).build()).execute()
            response.use { it.isSuccessful }
        }.getOrDefault(false)
        if (current != null) saveCredential(current.copy(accessToken = "", refreshToken = "", idToken = "", scopes = emptySet(), expiresAtMs = 0))
        _state.value = ChatGptAuthState.Disconnected
        remotelyRevoked
    }

    override fun onSessionInvalidated(callback: () -> Unit): AutoCloseable {
        invalidationCallbacks += callback
        return AutoCloseable { invalidationCallbacks -= callback }
    }

    fun endpointOrNull(): com.grinningfrog.atlas.model.ProviderEndpoint? {
        val credential = loadCredential() ?: return null
        if (credential.accessToken.isBlank() || ChatGptOAuth.REQUIRED_SCOPE !in credential.scopes || credential.selectedModel.isBlank()) return null
        return com.grinningfrog.atlas.model.ProviderEndpoint(
            id = ENDPOINT_ID, name = "ChatGPT plan", baseUrl = ChatGptOAuth.RESOURCE, model = credential.selectedModel,
            supportsVision = true, supportsTools = true, supportsStreaming = true, reasoningEnabled = true,
            timeoutMs = 120_000, kind = com.grinningfrog.atlas.model.ProviderKind.CHATGPT_PLAN,
        )
    }

    fun selectModel(slug: String) {
        val current = loadCredential() ?: return
        require(current.models.any { it.slug == slug })
        check(saveCredential(current.copy(selectedModel = slug)))
        _state.value = ChatGptAuthState.Connected(current.email, slug, current.models)
    }

    private fun initialState(): ChatGptAuthState {
        val saved = loadCredential() ?: return ChatGptAuthState.Disconnected
        if (saved.accessToken.isBlank() && saved.refreshToken.isBlank()) return ChatGptAuthState.Disconnected
        if (ChatGptOAuth.REQUIRED_SCOPE !in saved.scopes) return ChatGptAuthState.EntitlementUnavailable("Signed in, but ChatGPT plan inference permission was not granted.")
        if (saved.selectedModel.isBlank()) return ChatGptAuthState.EntitlementUnavailable("Connected, but no eligible ChatGPT plan model is available.")
        return ChatGptAuthState.Connected(saved.email, saved.selectedModel, saved.models)
    }

    private fun exchangeCode(clientId: String, code: String, verifier: String, redirect: String): TokenResult = tokenRequest(FormBody.Builder()
        .add("grant_type", "authorization_code").add("client_id", clientId).add("code", code)
        .add("code_verifier", verifier).add("redirect_uri", redirect).add("resource", ChatGptOAuth.RESOURCE).build())

    private fun refresh(current: ChatGptCredential): ChatGptCredential {
        require(current.refreshToken.isNotBlank()) { "ChatGPT refresh token is unavailable" }
        val token = tokenRequest(FormBody.Builder().add("grant_type", "refresh_token").add("client_id", current.clientId)
            .add("refresh_token", current.refreshToken).add("resource", ChatGptOAuth.RESOURCE).build())
        val scopes = if (token.scopes.isEmpty()) current.scopes else token.scopes
        require(ChatGptOAuth.REQUIRED_SCOPE in scopes) { "Required ChatGPT plan scope was not retained" }
        return current.copy(accessToken = token.accessToken, refreshToken = token.refreshToken, idToken = current.idToken,
            scopes = scopes, expiresAtMs = token.expiresAtMs, earliestRefreshAtMs = token.earliestRefreshAtMs)
    }

    private fun tokenRequest(body: FormBody): TokenResult {
        val response = client.newCall(Request.Builder().url(ChatGptOAuth.TOKEN_ENDPOINT).post(body).build()).execute()
        response.use {
            val raw = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw IllegalStateException("OpenAI token exchange failed (${it.code}): ${safeError(raw)}")
            val json = JSONObject(raw)
            return TokenResult(json.getString("access_token"), json.getString("refresh_token"), json.optString("id_token"),
                json.optString("scope").split(' ').filter(String::isNotBlank).toSet(),
                now() + json.optLong("expires_in", 3600) * 1000, json.optLong("earliest_refresh_at", 0) * 1000)
        }
    }

    private fun fetchModels(accessToken: String): List<ChatGptModel> {
        val response = client.newCall(Request.Builder().url("${ChatGptOAuth.RESOURCE}/models").header("Authorization", "Bearer $accessToken").build()).execute()
        response.use {
            val raw = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw IllegalStateException("OpenAI model discovery failed (${it.code}): ${safeError(raw)}")
            val array = JSONObject(raw).optJSONArray("models") ?: JSONArray()
            return buildList { for (i in 0 until array.length()) array.optJSONObject(i)?.takeIf { model -> model.optString("visibility") == "list" }?.let { model ->
                model.optString("slug").takeIf(String::isNotBlank)?.let { add(ChatGptModel(it, model.optString("display_name", it))) }
            } }
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun validateIdToken(token: String, audience: String, nonce: String): Identity {
        val parts = token.split('.')
        require(parts.size == 3) { "OpenAI returned an invalid identity token" }
        val header = JSONObject(String(Base64.UrlSafe.decode(pad(parts[0])), Charsets.UTF_8))
        require(header.optString("alg") == "RS256") { "Unsupported identity-token signature" }
        val jwks = JSONObject(get(ChatGptOAuth.JWKS_ENDPOINT)).getJSONArray("keys")
        val kid = header.getString("kid")
        val jwk = (0 until jwks.length()).map(jwks::getJSONObject).firstOrNull { it.optString("kid") == kid }
            ?: error("OpenAI identity signing key was not found")
        val n = BigInteger(1, Base64.UrlSafe.decode(pad(jwk.getString("n"))))
        val e = BigInteger(1, Base64.UrlSafe.decode(pad(jwk.getString("e"))))
        val key = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(n, e))
        val signature = Signature.getInstance("SHA256withRSA").apply { initVerify(key); update("${parts[0]}.${parts[1]}".toByteArray(StandardCharsets.US_ASCII)) }
        require(signature.verify(Base64.UrlSafe.decode(pad(parts[2])))) { "OpenAI identity-token signature was invalid" }
        val claims = JSONObject(String(Base64.UrlSafe.decode(pad(parts[1])), Charsets.UTF_8))
        require(claims.optString("iss") == ChatGptOAuth.ISSUER) { "OpenAI identity-token issuer was invalid" }
        val aud = claims.opt("aud")
        require(aud == audience || (aud is JSONArray && (0 until aud.length()).any { aud.optString(it) == audience })) { "OpenAI identity-token audience was invalid" }
        require(claims.optLong("exp") * 1000 > now() - 5_000) { "OpenAI identity token expired" }
        require(claims.optString("nonce") == nonce) { "OpenAI identity-token nonce did not match" }
        return Identity(claims.getString("sub"), claims.optString("email").ifBlank { null })
    }

    private fun get(url: String): String = client.newCall(Request.Builder().url(url).build()).execute().use {
        val raw = it.body?.string().orEmpty(); if (!it.isSuccessful) error("OpenAI metadata request failed (${it.code})"); raw
    }

    private fun safeError(raw: String): String = runCatching {
        val json = JSONObject(raw); json.optJSONObject("error")?.optString("code")?.ifBlank { null }
            ?: json.optString("error").ifBlank { null } ?: "request_rejected"
    }.getOrDefault("request_rejected")

    private fun saveCredential(value: ChatGptCredential): Boolean = settings.putSecretAtomically(CREDENTIAL_ALIAS, value.toJson().toString())
    private fun loadCredential(): ChatGptCredential? = settings.secret(CREDENTIAL_ALIAS)?.let { runCatching { credential(JSONObject(it)) }.getOrNull() }

    @OptIn(ExperimentalEncodingApi::class)
    private fun hostId(): String {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val publicKey = if (store.containsAlias(HOST_KEY_ALIAS)) store.getCertificate(HOST_KEY_ALIAS).publicKey as ECPublicKey else {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(HOST_KEY_ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setDigests(KeyProperties.DIGEST_SHA256).setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1")).build())
            }.generateKeyPair().public as ECPublicKey
        }
        fun coordinate(value: BigInteger): String {
            val raw = value.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else ByteArray(32 - it.size) + it }
            return Base64.UrlSafe.encode(raw).trimEnd('=')
        }
        val canonical = "{\"crv\":\"P-256\",\"kty\":\"EC\",\"x\":\"${coordinate(publicKey.w.affineX)}\",\"y\":\"${coordinate(publicKey.w.affineY)}\"}"
        val thumbprint = Base64.UrlSafe.encode(MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())).trimEnd('=')
        return "urn:ietf:params:oauth:jwk-thumbprint:$thumbprint"
    }

    private data class TokenResult(val accessToken: String, val refreshToken: String, val idToken: String, val scopes: Set<String>, val expiresAtMs: Long, val earliestRefreshAtMs: Long) {
        fun toCredential(clientId: String, identity: Identity, models: List<ChatGptModel>, selected: String) = ChatGptCredential(clientId, identity.subject, identity.email,
            accessToken, refreshToken, idToken, scopes, expiresAtMs, earliestRefreshAtMs, models, selected)
    }
    private data class Identity(val subject: String, val email: String?)
    private class AuthorizationDenied(message: String) : Exception(message)

    companion object {
        const val ENDPOINT_ID = "chatgpt-plan"
        private const val CREDENTIAL_ALIAS = "chatgpt.oauth.v1"
        private const val HOST_KEY_ALIAS = "atlas.chatgpt-host.v1"
        private fun pad(value: String) = value + "=".repeat((4 - value.length % 4) % 4)
    }
}

internal class LoopbackCallbackServer : AutoCloseable {
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 1_000 }
    val redirectUri = "http://127.0.0.1:${server.localPort}/auth/callback"

    suspend fun awaitCallback(timeoutMs: Long): OAuthCallback = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            if (System.currentTimeMillis() >= deadline) throw SocketTimeoutException("callback timeout")
            try {
                server.accept().use { socket ->
                    val line = socket.getInputStream().bufferedReader().readLine() ?: error("Empty authorization callback")
                    val target = line.split(' ').getOrNull(1) ?: error("Malformed authorization callback")
                    val parsed = ChatGptOAuth.parseCallback(target)
                    val ok = target.substringBefore('?') == "/auth/callback"
                    val body = if (ok) "Atlas received the authorization. You can return to the app." else "Atlas rejected this callback."
                    val status = if (ok) "200 OK" else "404 Not Found"
                    socket.getOutputStream().bufferedWriter().use { out ->
                        out.write("HTTP/1.1 $status\r\nContent-Type: text/plain; charset=utf-8\r\nCache-Control: no-store\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body")
                    }
                    if (!ok) error("Unexpected authorization callback path")
                    return@withContext parsed
                }
            } catch (_: SocketTimeoutException) { }
        }
        error("unreachable")
    }

    override fun close() = server.close()
}

private fun ChatGptCredential.toJson() = JSONObject().apply {
    put("client_id", clientId); put("subject", subject); put("email", email); put("access_token", accessToken); put("refresh_token", refreshToken)
    put("id_token", idToken); put("scopes", JSONArray(scopes.toList())); put("expires_at_ms", expiresAtMs); put("earliest_refresh_at_ms", earliestRefreshAtMs)
    put("selected_model", selectedModel); put("models", JSONArray(models.map { JSONObject().put("slug", it.slug).put("display_name", it.displayName) }))
}

private fun credential(json: JSONObject): ChatGptCredential {
    fun strings(key: String) = json.optJSONArray(key)?.let { array -> (0 until array.length()).map(array::getString) }.orEmpty()
    val modelArray = json.optJSONArray("models") ?: JSONArray()
    return ChatGptCredential(json.getString("client_id"), json.optString("subject"), json.optString("email").ifBlank { null }, json.optString("access_token"),
        json.optString("refresh_token"), json.optString("id_token"), strings("scopes").toSet(), json.optLong("expires_at_ms"), json.optLong("earliest_refresh_at_ms"),
        (0 until modelArray.length()).map { modelArray.getJSONObject(it) }.map { ChatGptModel(it.getString("slug"), it.optString("display_name", it.getString("slug"))) }, json.optString("selected_model"))
}
