package com.grinningfrog.atlas.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SystemSectionTabsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun phoneTabsHaveEqualBoundsReadableLabelsAndSelectedState() {
        val selected = mutableStateOf("Data")
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.3f)) {
                MaterialTheme { Box(Modifier.width(360.dp)) { SystemSectionTabs(selected.value, { selected.value = it }) } }
            }
        }
        val tabs = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).fetchSemanticsNodes()
        assertEquals(4, tabs.size)
        tabs.forEach { assertEquals(tabs.first().boundsInRoot.width, it.boundsInRoot.width, 0.1f); assertEquals(56f, it.boundsInRoot.height, 0.1f) }
        for (label in listOf("Inference", "Data & logs", "Intent", "Tools")) {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(label).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            assertEquals("$label must remain on one line", 1, layout.lineCount)
            assertFalse("$label must not be truncated", layout.isLineEllipsized(0))
            assertEquals("Every character of $label must be visible", label.length, layout.getLineEnd(0, visibleEnd = true))
        }
        compose.onNodeWithText("Data & logs").assertIsSelected()
        compose.onNodeWithText("Tools").performClick().assertIsSelected()
        assertEquals("Tools", selected.value)
    }
}
