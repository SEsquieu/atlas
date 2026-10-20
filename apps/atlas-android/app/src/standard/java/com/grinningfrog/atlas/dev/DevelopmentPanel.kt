package com.grinningfrog.atlas.dev

import androidx.compose.runtime.Composable

/** Standard APKs compile this tiny hook, not the developer panel or probe runner. */
object DevelopmentPanel {
    const val available = false
    @Composable fun Content(sourceSessionId: String?) = Unit
}
