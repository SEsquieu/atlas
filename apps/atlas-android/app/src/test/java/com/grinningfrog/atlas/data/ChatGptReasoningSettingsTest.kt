package com.grinningfrog.atlas.data

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ChatGptReasoningSettingsTest {
    @Test fun explicitEffortSurvivesReloadAndCanReturnToProviderDefault() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("atlas.settings", 0).edit().clear().commit()
        val settings = SecureSettings(context)
        assertNull(settings.chatGptReasoningEffort())
        settings.saveChatGptReasoningEffort("high")
        assertEquals("high", SecureSettings(context).chatGptReasoningEffort())
        assertTrue(runCatching { settings.saveChatGptReasoningEffort("invalid") }.isFailure)
        assertEquals("high", settings.chatGptReasoningEffort())
        settings.saveChatGptReasoningEffort(null)
        assertNull(SecureSettings(context).chatGptReasoningEffort())
    }
}
