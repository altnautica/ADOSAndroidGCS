package com.altnautica.gcs.feature.cockpit.chrome

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.feature.cockpit.flow.LocalCockpitStrings
import com.altnautica.gcs.feature.cockpit.testSpec
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Alert list items: a severity icon, the text first and the source label under it, under an "Alerts" heading. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class StatusListItemTest {
    @get:Rule
    val compose = createComposeRule()

    private val strings = testSpec.setupStrings

    @Test
    fun `each item shows its severity icon, its text, and its source under the text`() {
        val items = listOf(
            StatusItem("text:Motor failure", StatusSource.FcStatusText, StatusSeverity.Critical, text = "Motor failure"),
            StatusItem("text:GPS glitch", StatusSource.FcStatusText, StatusSeverity.Warning, text = "GPS glitch"),
            StatusItem("text:Logging", StatusSource.FcStatusText, StatusSeverity.Ok, text = "Logging"),
        )
        compose.setContent {
            AdosTheme {
                CompositionLocalProvider(LocalCockpitStrings provides strings) { StatusList(items, onClose = {}) }
            }
        }
        compose.onNodeWithText(strings.format("cockpit.alerts.title")).assertExists()
        compose.onNodeWithTag("status-icon-critical", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("status-icon-warning", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("status-icon-ok", useUnmergedTree = true).assertExists()
        val text = compose.onNodeWithText("Motor failure", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val icon = compose.onNodeWithTag("status-icon-critical", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("the icon leads the text", icon.right <= text.left)
    }
}
