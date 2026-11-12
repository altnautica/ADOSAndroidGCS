package com.altnautica.gcs.feature.cockpit

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.feature.cockpit.flow.LocalCockpitStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Back while the controller tour shows closes the tour, not the flight screen. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ControllerTourBackTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun backClosesTheTourAndNotTheScreen() {
        var open by mutableStateOf(true)
        var exits = 0
        compose.setContent {
            AdosTheme {
                CompositionLocalProvider(LocalCockpitStrings provides testSpec.setupStrings) {
                    // The flight screen's own Back, registered first like the route's.
                    NavigationBackHandler(
                        state = rememberNavigationEventState(NavigationEventInfo.None),
                        isBackEnabled = true,
                        onBackCompleted = { exits++ },
                    )
                    if (open) ControllerTourCard(step = 0, onNext = {}, onClose = { open = false })
                }
            }
        }
        compose.onNodeWithTag(TAG_COCKPIT_TOUR, useUnmergedTree = true).assertExists()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertFalse(open)
        assertEquals(0, exits)
    }
}
