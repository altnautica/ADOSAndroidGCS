package com.altnautica.gcs.feature.cockpit

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altnautica.gcs.core.designsystem.component.overlay.ProvideOverlayHost
import com.altnautica.gcs.core.designsystem.component.overlay.ProvideOverlayTopBar
import com.altnautica.gcs.core.designsystem.component.overlay.overlayTopBar
import com.altnautica.gcs.core.designsystem.component.overlay.rememberOverlayTopBar
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.feature.cockpit.flow.LocalCockpitStrings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The leave-while-armed sheet opens when Back is pressed in flight; it starts below the
 * flight screen's top bar like every other sheet, so the first press on STOP reaches STOP.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w914dp-h411dp-land")
class LeaveArmedSheetTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var stops = 0
    private var stays = 0
    private var leaves = 0

    private fun show() {
        compose.setContent {
            AdosTheme {
                ProvideOverlayHost {
                    CompositionLocalProvider(LocalCockpitStrings provides testSpec.setupStrings) {
                        val bar = rememberOverlayTopBar()
                        ProvideOverlayTopBar(bar) {
                            // The route's safe-drawing padding: the flight screen starts below the window top.
                            Box(Modifier.fillMaxSize().padding(top = 32.dp)) {
                                Box(Modifier.fillMaxWidth().height(56.dp).overlayTopBar(bar)) {
                                    Box(Modifier.align(Alignment.CenterEnd).size(80.dp, 40.dp).testTag("stop").clickable { stops++ }) { Text("STOP") }
                                }
                            }
                            LeaveArmedSheet(onStay = { stays++ }, onLeave = { leaves++ })
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun text(key: String) = testSpec.setupStrings.format(key, emptyMap())

    @Test
    fun `STOP takes the first press with the leave sheet open`() {
        show()
        val stop = compose.onNodeWithTag("stop", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        compose.onRoot().performTouchInput { click(stop.center) }
        compose.waitForIdle()
        assertEquals(1, stops)
        assertEquals(0, stays)
        compose.onNodeWithText(text("cockpit.leave_armed.title")).assertIsDisplayed()
    }

    @Test
    fun `the sheet words come from the spec`() {
        show()
        compose.onNodeWithText(text("cockpit.leave_armed.message")).assertIsDisplayed()
        compose.onNodeWithText(text("cockpit.stay")).performClick()
        compose.onNodeWithText(text("cockpit.leave")).performClick()
        compose.waitForIdle()
        assertEquals(1, stays)
        assertEquals(1, leaves)
    }
}
