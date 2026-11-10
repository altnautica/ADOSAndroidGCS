package com.altnautica.gcs.feature.cockpit.chrome

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.feature.cockpit.flow.LocalCockpitStrings
import com.altnautica.gcs.feature.cockpit.testSpec
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w914dp-h411dp-land")
class TopBarFocusTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var focus: FocusManager
    private var stops = 0

    @Composable
    private fun item(tag: String) = Box(Modifier.size(48.dp, 32.dp).testTag(tag).clickable {})

    @OptIn(ExperimentalComposeUiApi::class)
    private fun show() {
        compose.setContent {
            LocalInputModeManager.current.requestInputMode(InputMode.Keyboard)
            focus = LocalFocusManager.current
            AdosTheme {
                CompositionLocalProvider(LocalCockpitStrings provides testSpec.setupStrings) {
                    TopBar(
                        overflow = TopBarOverflow(),
                        overflowOrder = SPEC_ORDER,
                        chip = { item("chip") },
                        pill = { item("pill") },
                        battery = {},
                        linkIcon = {},
                        linkDetail = {},
                        navSource = { item("nav") },
                        controller = {},
                        layers = { item("layers") },
                        menu = { item("menu") },
                        stop = { StopButton(onClick = { stops++ }) },
                        modifier = Modifier.width(900.dp).height(44.dp),
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `focus walks the top bar in drawn order, STOP last`() {
        show()
        val order = listOf("chip", "pill", "nav", "layers", "menu", "stop")
        compose.onNodeWithTag("chip", useUnmergedTree = true).requestFocus()
        for ((i, tag) in order.withIndex()) {
            if (i > 0) compose.runOnIdle { focus.moveFocus(FocusDirection.Next) }
            compose.onNodeWithTag(tag, useUnmergedTree = true).assertIsFocused()
        }
    }

    @Test
    fun `a press just above STOP's face still reaches STOP (48 dp touch target)`() {
        show()
        val stop = compose.onNodeWithTag("stop", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val above = with(compose.density) { 4.dp.toPx() }
        compose.onRoot().performTouchInput { click(androidx.compose.ui.geometry.Offset(stop.center.x, stop.top - above)) }
        compose.waitForIdle()
        assertEquals(1, stops)
    }
}
