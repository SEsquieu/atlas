package com.grinningfrog.atlas

import android.app.Application
import com.grinningfrog.atlas.data.AtlasDatabase
import com.grinningfrog.atlas.data.SecureSettings
import com.grinningfrog.atlas.media.MediaRepository
import com.grinningfrog.atlas.cloud.ManagedAccountClient
import com.grinningfrog.atlas.data.SessionArchive
import com.grinningfrog.atlas.provider.ChatGptAuthManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AtlasApplication : Application() {
    val runtimeCore by lazy { com.grinningfrog.atlas.runtime.core.RuntimeCoreEngine(this) }
    val runtimeBundles by lazy { com.grinningfrog.atlas.runtime.core.RuntimeBundleStore(this) }
    val database by lazy { AtlasDatabase(this) }
    val settings by lazy { SecureSettings(this) }
    val mediaRepository by lazy { MediaRepository(this) }
    val managedAccount by lazy { ManagedAccountClient(this, settings) }
    val chatGptAuth by lazy { ChatGptAuthManager(this, settings) }
    val sessionArchive by lazy { SessionArchive(this, database, mediaRepository) }

    fun inferenceProviders() = buildList {
        addAll(settings.loadProviders())
        chatGptAuth.endpointOrNull()?.let(::add)
    }

    override fun onCreate() {
        super.onCreate()
        if (Application.getProcessName().contains(":workspace_js")) return
        com.grinningfrog.atlas.diagnostics.RuntimeDiagnostics.install(this)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { sessionArchive.enforceMediaRetention(settings.mediaRetentionDays) }
        }
    }
}
