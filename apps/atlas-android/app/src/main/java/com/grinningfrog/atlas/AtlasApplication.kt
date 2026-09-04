package com.grinningfrog.atlas

import android.app.Application
import com.grinningfrog.atlas.data.AtlasDatabase
import com.grinningfrog.atlas.data.SecureSettings
import com.grinningfrog.atlas.media.MediaRepository
import com.grinningfrog.atlas.cloud.ManagedAccountClient
import com.grinningfrog.atlas.data.SessionArchive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AtlasApplication : Application() {
    val database by lazy { AtlasDatabase(this) }
    val settings by lazy { SecureSettings(this) }
    val mediaRepository by lazy { MediaRepository(this) }
    val managedAccount by lazy { ManagedAccountClient(this, settings) }
    val sessionArchive by lazy { SessionArchive(this, database, mediaRepository) }

    override fun onCreate() {
        super.onCreate()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { sessionArchive.enforceMediaRetention(settings.mediaRetentionDays) }
        }
    }
}
