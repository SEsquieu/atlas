package com.grinningfrog.atlas.runtime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.grinningfrog.atlas.AtlasApplication
import com.grinningfrog.atlas.MainActivity
import com.grinningfrog.atlas.R
import com.grinningfrog.atlas.device.CameraController
import com.grinningfrog.atlas.device.DeviceHealthMonitor
import com.grinningfrog.atlas.device.MotionMonitor
import com.grinningfrog.atlas.device.SpeechController
import com.grinningfrog.atlas.provider.CapabilityRouter
import com.grinningfrog.atlas.provider.OpenAiCompatibleBackend
import com.grinningfrog.atlas.intent.AndroidIdleRuntime
import com.grinningfrog.atlas.intent.SqliteIntentStore
import com.grinningfrog.atlas.intent.IntentInferenceWorkHandler
import com.grinningfrog.atlas.intent.IntentMetadataMaintenanceHandler
import com.grinningfrog.atlas.intent.InferenceLocation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Process-local owner of a physical Atlas session.
 *
 * The Activity may be recreated or leave the foreground without destroying the session. The
 * foreground service owns camera, speech, heartbeat, provider routing, and the durable event log.
 */
class AtlasSessionService : LifecycleService() {
    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var runtime: AtlasMobileRuntime
        private set

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification())

        val app = application as AtlasApplication
        val motion = MotionMonitor(this)
        val camera = CameraController(this, this, motion, app.mediaRepository)
        val speech = SpeechController(this)
        val health = DeviceHealthMonitor(this, motion)
        val router = CapabilityRouter(
            backend = OpenAiCompatibleBackend(),
            endpoints = app.settings::loadProviders,
            routes = app.settings::loadRoutes,
            apiKey = { endpoint -> if (endpoint.id == com.grinningfrog.atlas.cloud.ManagedAccountClient.ENDPOINT_ID) app.managedAccount.accessToken() else app.settings.apiKey(endpoint) },
            onAttempt = { endpoint, success, error ->
                app.database.loadLatestSession()?.let { session ->
                    app.database.appendEvent(session.id, "provider.route_attempted", JSONObject().apply {
                        put("endpointId", endpoint.id)
                        put("endpointName", endpoint.name)
                        put("model", endpoint.model)
                        put("endpointLocation", runCatching { com.grinningfrog.atlas.provider.EndpointSecurity.assess(endpoint.baseUrl).location.name }.getOrNull())
                        put("reasoningEnabled", endpoint.reasoningEnabled)
                        put("supportsVision", endpoint.supportsVision)
                        put("supportsTools", endpoint.supportsTools)
                        put("supportsStreaming", endpoint.supportsStreaming)
                        put("configuredTimeoutMs", endpoint.timeoutMs)
                        put("success", success)
                        error?.let { put("error", it) }
                    })
                }
            },
        )
        val idleRuntime = AndroidIdleRuntime(
            SqliteIntentStore(app.database), app.settings, health, serviceScope,
            handlers = listOf(
                IntentMetadataMaintenanceHandler(),
                IntentInferenceWorkHandler(InferenceLocation.LOCAL, app.database, router),
                IntentInferenceWorkHandler(InferenceLocation.CLOUD, app.database, router),
            ),
        )
        runtime = AtlasMobileRuntime(app.database, app.mediaRepository, camera, speech, motion, health, router, serviceScope, idleRuntime)
        serviceScope.launch {
            runCatching { runtime.initialize() }.onFailure { error ->
                app.database.loadLatestSession()?.let { session ->
                    app.database.appendEvent(
                        session.id,
                        "runtime.initialization_failed",
                        JSONObject().put("error", error.message ?: error.javaClass.simpleName),
                    )
                }
            }
        }
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return Service.START_STICKY
    }

    override fun onDestroy() {
        runtime.close()
        serviceScope.cancel()
        super.onDestroy()
    }

    inner class LocalBinder : Binder() {
        val service: AtlasSessionService get() = this@AtlasSessionService
    }

    private fun notification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_atlas)
        .setContentTitle("Atlas physical session")
        .setContentText("Device resources are used only while a session is active")
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        )
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Atlas sessions", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Keeps an active Atlas physical session running"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "atlas.physical-session"
        private const val NOTIFICATION_ID = 4201

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, AtlasSessionService::class.java))
        }
    }
}
