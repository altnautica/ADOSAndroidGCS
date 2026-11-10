package com.altnautica.gcs.feature.cockpit.chrome

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.feature.cockpit.flow.LocalCockpitStrings
import com.altnautica.gcs.feature.cockpit.testSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** System Back (and a pad's Back) closes the alert list before it leaves the flight screen. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class StatusListBackTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun backClosesTheAlertListAndNotTheScreen() {
        var open by mutableStateOf(true)
        var exits = 0
        compose.setContent {
            AdosTheme {
                CompositionLocalProvider(LocalCockpitStrings provides testSpec.setupStrings) {
                    // The flight screen's own Back, registered first like the route's.
                    androidx.navigationevent.compose.NavigationBackHandler(
                        state = androidx.navigationevent.compose.rememberNavigationEventState(androidx.navigationevent.NavigationEventInfo.None),
                        isBackEnabled = true,
                        onBackCompleted = { exits++ },
                    )
                    if (open) StatusList(emptyList(), onClose = { open = false })
                }
            }
        }
        compose.onNodeWithTag("status-list", useUnmergedTree = true).assertExists()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertFalse(open)
        assertEquals(0, exits)
        compose.onNodeWithTag("status-list", useUnmergedTree = true).assertDoesNotExist()
    }
}
