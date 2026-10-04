package com.grinningfrog.atlas.runtime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import androidx.core.content.ContextCompat
import com.grinningfrog.atlas.data.AtlasDatabase
import com.grinningfrog.atlas.data.SecureSettings
import com.grinningfrog.atlas.model.AtlasSession
import com.grinningfrog.atlas.model.AtlasToolCall
import com.grinningfrog.atlas.model.PermissionPolicy
import com.grinningfrog.atlas.model.ToolCallProposal
import com.grinningfrog.atlas.model.ToolDefinition
import com.grinningfrog.atlas.model.ToolRisk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Device-context tools remain owned and executed by Atlas; inference providers only propose calls. */
object AndroidContextTools {
    fun create(context: Context, database: AtlasDatabase, settings: SecureSettings): List<AtlasToolAdapter> = buildList {
        add(CurrentLocationTool(context.applicationContext))
        add(ReverseGeocodeTool(context.applicationContext))
        // Do not advertise a local search executor that cannot run. ChatGPT hosted search is
        // provider-owned and is declared by ChatGptPlanBackend instead of ToolHarness.
        if (!settings.braveSearchKey().isNullOrBlank()) add(BraveWebSearchTool(settings))
        add(RecordFindingTool(database))
    }
}

private class BraveWebSearchTool(
    private val settings: SecureSettings,
    private val client: OkHttpClient = OkHttpClient(),
) : AtlasToolAdapter {
    override val definition = ToolDefinition(
        name = "web_search",
        description = "Search the public web for current or externally verifiable information. Use a narrow query and return evidence from the bounded results; this does not grant permission to browse arbitrary pages or perform actions.",
        parametersJson = """{"type":"object","properties":{"query":{"type":"string","maxLength":600},"freshness":{"type":"string","enum":["day","week","month","year","any"]},"count":{"type":"integer","minimum":1,"maximum":5}},"required":["query"],"additionalProperties":false}""",
        risk = ToolRisk.READ_ONLY,
        maxCallsPerTurn = 3,
    )

    override fun evaluate(session: AtlasSession, proposal: ToolCallProposal) = ToolPolicyDecision(
        allowed = !settings.braveSearchKey().isNullOrBlank(),
        requiresConfirmation = false,
        risk = ToolRisk.READ_ONLY,
        reason = if (settings.braveSearchKey().isNullOrBlank()) "Configure a Brave Search API key in System → Tools" else "Bounded read-only public web search",
    )

    override suspend fun execute(call: AtlasToolCall): ToolExecutionResult = withContext(Dispatchers.IO) {
        val args = JSONObject(call.argumentsJson)
        val query = args.getString("query").trim().take(600)
        require(query.isNotBlank() && query.split(Regex("\\s+")).size <= 75) { "Search query must contain 1–75 words" }
        val count = args.optInt("count", 5).coerceIn(1, 5)
        val freshness = when (args.optString("freshness", "any")) { "day" -> "pd"; "week" -> "pw"; "month" -> "pm"; "year" -> "py"; else -> null }
        val url = "https://api.search.brave.com/res/v1/web/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query).addQueryParameter("count", count.toString())
            .addQueryParameter("safesearch", "strict").apply { freshness?.let { addQueryParameter("freshness", it) } }.build()
        val request = Request.Builder().url(url).header("Accept", "application/json")
            .header("X-Subscription-Token", settings.braveSearchKey() ?: error("Brave Search is not configured")).build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error(when (response.code) {
                401, 403 -> "Brave Search authorization failed"
                429 -> "Brave Search usage limit reached"
                else -> "Brave Search failed with HTTP ${response.code}"
            })
            val results = parseBraveSearchResults(body, count)
            ToolExecutionResult(JSONObject().put("ok", true).put("query", query).put("result_count", results.length()).put("results", results).toString())
        }
    }
}

internal fun parseBraveSearchResults(body: String, limit: Int): JSONArray {
    val source = JSONObject(body).optJSONObject("web")?.optJSONArray("results") ?: JSONArray()
    return JSONArray().apply {
        for (index in 0 until minOf(source.length(), limit.coerceIn(1, 5))) {
            val item = source.optJSONObject(index) ?: continue
            val url = item.optString("url")
            if (!url.startsWith("https://") && !url.startsWith("http://")) continue
            put(JSONObject().apply {
                put("title", item.optString("title").take(300)); put("url", url)
                put("description", item.optString("description").take(700)); put("age", item.optString("age").take(80))
            })
        }
    }
}

private class CurrentLocationTool(private val context: Context) : AtlasToolAdapter {
    override val definition = ToolDefinition(
        name = "get_current_location",
        description = "Read the Atlas device's current geographic coordinates when location is needed. Do not use visual guessing as a substitute for precise location.",
        parametersJson = """{"type":"object","properties":{"desired_accuracy":{"type":"string","enum":["coarse","precise"]}},"additionalProperties":false}""",
        risk = ToolRisk.PERSONAL_DATA,
        maxCallsPerTurn = 2,
    )

    override fun evaluate(session: AtlasSession, proposal: ToolCallProposal): ToolPolicyDecision {
        if (session.permissions.location == PermissionPolicy.NEVER) return ToolPolicyDecision(false, false, ToolRisk.PERSONAL_DATA, "Location is disabled for this session")
        if (!hasLocationPermission(context)) return ToolPolicyDecision(false, false, ToolRisk.PERSONAL_DATA, "Android location permission is required; grant it in System settings")
        return ToolPolicyDecision(true, session.permissions.location == PermissionPolicy.USER_REQUEST, ToolRisk.PERSONAL_DATA, "Location access follows the active session policy")
    }

    override suspend fun execute(call: AtlasToolCall): ToolExecutionResult {
        val location = currentLocation(context)
        return ToolExecutionResult(JSONObject().apply {
            put("ok", true); put("latitude", location.latitude); put("longitude", location.longitude)
            put("accuracy_m", location.accuracy.toDouble()); put("altitude_m", if (location.hasAltitude()) location.altitude else JSONObject.NULL)
            put("provider", location.provider); put("observed_at_ms", location.time)
            put("age_ms", (System.currentTimeMillis() - location.time).coerceAtLeast(0))
        }.toString())
    }
}

private class ReverseGeocodeTool(private val context: Context) : AtlasToolAdapter {
    override val definition = ToolDefinition(
        name = "reverse_geocode",
        description = "Resolve known latitude and longitude into a human-readable place. Coordinates should normally come from get_current_location.",
        parametersJson = """{"type":"object","properties":{"latitude":{"type":"number","minimum":-90,"maximum":90},"longitude":{"type":"number","minimum":-180,"maximum":180}},"required":["latitude","longitude"],"additionalProperties":false}""",
        risk = ToolRisk.PERSONAL_DATA,
        maxCallsPerTurn = 2,
    )

    override fun evaluate(session: AtlasSession, proposal: ToolCallProposal) = ToolPolicyDecision(
        allowed = session.permissions.location != PermissionPolicy.NEVER,
        requiresConfirmation = false,
        risk = ToolRisk.PERSONAL_DATA,
        reason = "Read-only place lookup for coordinates already available to the session",
    )

    @Suppress("DEPRECATION")
    override suspend fun execute(call: AtlasToolCall): ToolExecutionResult = withContext(Dispatchers.IO) {
        val args = JSONObject(call.argumentsJson)
        val latitude = args.getDouble("latitude").also { require(it in -90.0..90.0) }
        val longitude = args.getDouble("longitude").also { require(it in -180.0..180.0) }
        require(Geocoder.isPresent()) { "No reverse-geocoding service is available on this device" }
        val address = Geocoder(context, Locale.getDefault()).getFromLocation(latitude, longitude, 1)?.firstOrNull()
            ?: error("No address was found for this location")
        ToolExecutionResult(JSONObject().apply {
            put("ok", true); put("display_name", (0..address.maxAddressLineIndex).joinToString(", ") { address.getAddressLine(it) })
            put("feature", address.featureName); put("locality", address.locality); put("sub_admin_area", address.subAdminArea)
            put("admin_area", address.adminArea); put("postal_code", address.postalCode); put("country", address.countryName)
            put("country_code", address.countryCode); put("latitude", latitude); put("longitude", longitude)
        }.toString())
    }
}

private class RecordFindingTool(private val database: AtlasDatabase) : AtlasToolAdapter {
    override val definition = ToolDefinition(
        name = "atlas_record_finding",
        description = "Record a structured capability, bug, UX, policy, or improvement finding discovered during a session so it is preserved in the exported audit log.",
        parametersJson = """{"type":"object","properties":{"category":{"type":"string","enum":["capability","bug","ux","policy","improvement"]},"title":{"type":"string","maxLength":160},"evidence":{"type":"string","maxLength":1500},"proposed_change":{"type":"string","maxLength":1500},"user_endorsed":{"type":"boolean"}},"required":["category","title","evidence"],"additionalProperties":false}""",
        risk = ToolRisk.SESSION_WRITE,
        maxCallsPerTurn = 4,
    )

    override suspend fun execute(call: AtlasToolCall): ToolExecutionResult {
        val args = JSONObject(call.argumentsJson)
        val findingId = UUID.randomUUID().toString()
        database.appendEvent(call.sessionId, "development.finding_recorded", JSONObject().apply {
            put("findingId", findingId); put("turnId", call.turnId); put("toolCallId", call.id)
            put("category", args.getString("category")); put("title", args.getString("title").trim().take(160))
            put("evidence", args.getString("evidence").trim().take(1_500))
            put("proposedChange", args.optString("proposed_change").trim().take(1_500))
            put("userEndorsed", args.optBoolean("user_endorsed", false))
        })
        return ToolExecutionResult(JSONObject().put("ok", true).put("finding_id", findingId).toString())
    }
}

private fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

@Suppress("MissingPermission")
private suspend fun currentLocation(context: Context): Location {
    check(hasLocationPermission(context)) { "Android location permission is required" }
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val enabled = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { provider ->
        runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)
    }
    require(enabled.isNotEmpty()) { "Device location is turned off" }
    val recent = enabled.mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
        .filter { System.currentTimeMillis() - it.time <= 120_000L }
        .minByOrNull { it.accuracy }
    if (recent != null) return recent
    return withTimeout(15_000L) {
        suspendCancellableCoroutine { continuation ->
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    manager.removeUpdates(this)
                    if (continuation.isActive) continuation.resume(location)
                }
                override fun onProviderDisabled(provider: String) = Unit
                override fun onProviderEnabled(provider: String) = Unit
                @Deprecated("Deprecated in Android") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            }
            continuation.invokeOnCancellation { manager.removeUpdates(listener) }
            runCatching { manager.requestLocationUpdates(enabled.first(), 0L, 0f, listener, context.mainLooper) }
                .onFailure { error -> if (continuation.isActive) continuation.resumeWithException(error) }
        }
    }
}
