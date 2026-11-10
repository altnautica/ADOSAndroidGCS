package com.altnautica.gcs.feature.cockpit

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.feature.cockpit.flow.LocalCockpitStrings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The top-bar controller cell says "No controller" until a pad is seen, instead of leaving a gap. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ControllerCellTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `with no pad seen the controller cell reads No controller`() {
        compose.setContent {
            AdosTheme {
                CompositionLocalProvider(LocalCockpitStrings provides testSpec.setupStrings) {
                    ControllerCell(StickChip.NoGamepad, onToggle = {})
                }
            }
        }
        compose.onNodeWithTag("controller").assertExists()
        compose.onNodeWithText(testSpec.setupStrings.format("controls.no_controller"), useUnmergedTree = true).assertExists()
    }
}
