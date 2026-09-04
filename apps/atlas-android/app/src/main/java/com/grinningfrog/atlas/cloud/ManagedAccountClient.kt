package com.grinningfrog.atlas.cloud

import android.content.Context
import com.grinningfrog.atlas.BuildConfig
import com.grinningfrog.atlas.data.SecureSettings
import com.grinningfrog.atlas.model.ProviderEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class ManagedAccountState(
    val configured: Boolean = false,
    val signedIn: Boolean = false,
    val email: String? = null,
    val organizationId: String? = null,
    val balanceMicros: Long? = null,
    val subscriptionStatus: String = "none",
    val busy: Boolean = false,
    val message: String? = null,
)

class ManagedAccountClient(context: Context, private val settings: SecureSettings) {
    private val prefs = context.getSharedPreferences("atlas.account", Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder().callTimeout(90, TimeUnit.SECONDS).build()
    private val mutableState = MutableStateFlow(
        ManagedAccountState(
            configured = configured,
            signedIn = settings.secret(ACCESS_ALIAS) != null,
            email = prefs.getString("email", null),
        ),
    )
    val state: StateFlow<ManagedAccountState> = mutableState

    val configured get() = BuildConfig.ATLAS_GATEWAY_URL.isNotBlank() && BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_PUBLISHABLE_KEY.isNotBlank()

    suspend fun signUp(email: String, password: String) = authenticate("${BuildConfig.SUPABASE_URL}/auth/v1/signup", email, password)
    suspend fun signIn(email: String, password: String) = authenticate("${BuildConfig.SUPABASE_URL}/auth/v1/token?grant_type=password", email, password)

    suspend fun signOut() {
        settings.removeSecret(ACCESS_ALIAS); settings.removeSecret(REFRESH_ALIAS)
        prefs.edit().clear().apply(); mutableState.value = snapshot(message = "Signed out")
    }

    suspend fun accessToken(): String? {
        val access = settings.secret(ACCESS_ALIAS) ?: return null
        if (System.currentTimeMillis() < prefs.getLong("expires_at", 0) - 60_000) return access
        val refresh = settings.secret(REFRESH_ALIAS) ?: return null
        return refresh(refresh) ?: run {
            settings.removeSecret(ACCESS_ALIAS)
            settings.removeSecret(REFRESH_ALIAS)
            mutableState.value = snapshot(message = "Your Atlas Cloud session expired. Sign in again.")
            null
        }
    }

    suspend fun refreshBalance() = withBusy {
        val response = authorized("${gateway()}/api/account/balance")
        val json = JSONObject(response)
        val organizationId = json.optString("organization_id").takeIf(String::isNotBlank)
        if (organizationId != null) prefs.edit().putString("organization_id", organizationId).apply()
        mutableState.value = snapshot().copy(organizationId = organizationId, balanceMicros = json.optLong("balance_micros"), subscriptionStatus = json.optString("subscription_status", "none"), message = null)
    }

    /** Future organization UI can switch scope without changing provider or runtime contracts. */
    fun selectOrganization(organizationId: String?) {
        prefs.edit().apply {
            if (organizationId.isNullOrBlank()) remove("organization_id") else putString("organization_id", organizationId)
        }.apply()
        mutableState.value = snapshot(message = "Atlas organization scope changed")
    }

    suspend fun checkout(product: String): String = withContext(Dispatchers.IO) {
        val response = authorized("${gateway()}/api/billing/checkout", JSONObject().put("product", product).toString())
        JSONObject(response).getString("url")
    }

    suspend fun portal(): String = withContext(Dispatchers.IO) {
        JSONObject(authorized("${gateway()}/api/billing/portal", "{}")).getString("url")
    }

    fun reportError(error: Throwable) {
        mutableState.value = snapshot(message = error.message ?: "Atlas Cloud request failed")
    }

    fun endpoint() = ProviderEndpoint(
        id = ENDPOINT_ID, name = "Atlas Cloud", baseUrl = "${gateway()}/api", model = "atlas/auto",
        apiKeyAlias = null, supportsVision = true, supportsTools = false, timeoutMs = 90_000,
    )

    private suspend fun authenticate(url: String, email: String, password: String) = withBusy {
        check(configured) { "Atlas Cloud is not configured in this build" }
        val body = JSONObject().put("email", email.trim()).put("password", password).toString()
        val request = Request.Builder().url(url).header("apikey", BuildConfig.SUPABASE_PUBLISHABLE_KEY).post(body.toRequestBody(JSON)).build()
        val json = execute(request)
        if (json.has("access_token")) saveSession(json, email.trim())
        else mutableState.value = snapshot(message = "Check your email to confirm the Atlas account, then sign in.")
    }

    private suspend fun refresh(refreshToken: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject().put("refresh_token", refreshToken).toString()
            val request = Request.Builder().url("${BuildConfig.SUPABASE_URL}/auth/v1/token?grant_type=refresh_token")
                .header("apikey", BuildConfig.SUPABASE_PUBLISHABLE_KEY).post(body.toRequestBody(JSON)).build()
            val json = execute(request); saveSession(json, prefs.getString("email", "").orEmpty()); json.getString("access_token")
        }.getOrNull()
    }

    private fun saveSession(json: JSONObject, email: String) {
        settings.putSecret(ACCESS_ALIAS, json.getString("access_token"))
        json.optString("refresh_token").takeIf(String::isNotBlank)?.let { settings.putSecret(REFRESH_ALIAS, it) }
        val expiresAt = System.currentTimeMillis() + json.optLong("expires_in", 3600) * 1000
        prefs.edit().putString("email", email).putLong("expires_at", expiresAt).apply()
        mutableState.value = snapshot(message = "Atlas account connected")
    }

    private suspend fun authorized(url: String, body: String? = null): String {
        val token = accessToken() ?: error("Sign in to Atlas Cloud first")
        val builder = Request.Builder().url(url).header("Authorization", "Bearer $token").apply {
            prefs.getString("organization_id", null)?.let { header("X-Atlas-Organization-Id", it) }
        }
        val request = if (body == null) builder.get().build() else builder.post(body.toRequestBody(JSON)).build()
        return execute(request).toString()
    }

    private suspend fun execute(request: Request): JSONObject = withContext(Dispatchers.IO) {
        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = runCatching { JSONObject(raw).optJSONObject("error")?.optString("message") }.getOrNull()
                error(detail?.takeIf { it.isNotBlank() } ?: "Atlas Cloud returned HTTP ${response.code}")
            }
            JSONObject(raw)
        }
    }

    private suspend fun withBusy(block: suspend () -> Unit) {
        mutableState.value = snapshot().copy(busy = true, message = null)
        runCatching { block() }.onFailure { mutableState.value = snapshot(message = it.message ?: "Atlas Cloud request failed") }
        if (mutableState.value.busy) mutableState.value = snapshot(message = mutableState.value.message)
    }

    private fun snapshot(message: String? = null): ManagedAccountState {
        val previous = mutableState.value
        return ManagedAccountState(
            configured = configured,
            signedIn = settings.secret(ACCESS_ALIAS) != null,
            email = prefs.getString("email", null),
            organizationId = prefs.getString("organization_id", null),
            balanceMicros = previous?.balanceMicros,
            subscriptionStatus = previous?.subscriptionStatus ?: "none",
            message = message,
        )
    }
    private fun gateway() = BuildConfig.ATLAS_GATEWAY_URL.trimEnd('/')

    companion object {
        const val ENDPOINT_ID = "atlas-managed"
        const val ACCESS_ALIAS = "atlas.account.access"
        private const val REFRESH_ALIAS = "atlas.account.refresh"
        private val JSON = "application/json".toMediaType()
    }
}
