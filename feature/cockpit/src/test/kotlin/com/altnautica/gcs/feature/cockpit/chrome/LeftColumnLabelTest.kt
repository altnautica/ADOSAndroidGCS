package com.altnautica.gcs.feature.cockpit.chrome

import androidx.activity.ComponentActivity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.feature.cockpit.flow.LocalCockpitStrings
import com.altnautica.gcs.feature.cockpit.flow.specText
import com.altnautica.gcs.feature.cockpit.testSpec
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A left-column action shows its short spec word under the icon, so it is never icon-only. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class LeftColumnLabelTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun theReturnHomeButtonCarriesItsShortWord() {
        compose.setContent {
            AdosTheme {
                CompositionLocalProvider(LocalCockpitStrings provides testSpec.setupStrings) {
                    RoundButton(Icons.Outlined.Home, "Return home", 56.dp, "grid-rth", {}, shortLabel = specText("builtin.short.rth"))
                }
            }
        }
        compose.onNodeWithTag("grid-rth-label", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Return home").assertIsDisplayed()
        compose.onNodeWithTag("grid-rth-label", useUnmergedTree = true).assertTextEquals("RTH")
    }
}
