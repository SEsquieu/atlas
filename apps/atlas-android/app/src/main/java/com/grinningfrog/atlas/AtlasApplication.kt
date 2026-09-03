package com.grinningfrog.atlas

import android.app.Application
import com.grinningfrog.atlas.data.AtlasDatabase
import com.grinningfrog.atlas.data.SecureSettings

class AtlasApplication : Application() {
    val database by lazy { AtlasDatabase(this) }
    val settings by lazy { SecureSettings(this) }
}
