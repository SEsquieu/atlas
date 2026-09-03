package com.grinningfrog.atlas

import android.app.Application
import com.grinningfrog.atlas.data.AtlasDatabase
import com.grinningfrog.atlas.data.SecureSettings
import com.grinningfrog.atlas.media.MediaRepository
import com.grinningfrog.atlas.cloud.ManagedAccountClient

class AtlasApplication : Application() {
    val database by lazy { AtlasDatabase(this) }
    val settings by lazy { SecureSettings(this) }
    val mediaRepository by lazy { MediaRepository(this) }
    val managedAccount by lazy { ManagedAccountClient(this, settings) }
}
