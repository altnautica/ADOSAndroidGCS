package com.altnautica.gcs.core.designsystem.component.overlay

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A screen with a top bar that must stay usable (the flight screen's STOP) provides
 * [LocalOverlayTopInset]; sheets, dialogs and menus then start below it, so the first
 * press on STOP reaches STOP with any of them open.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w914dp-h411dp-land")
class OverlayTopInsetTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var stops = 0
    private var dismissals = 0

    private fun screen(inset: Dp, overlay: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            AdosTheme {
                ProvideOverlayHost {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxWidth().height(TOP_BAR)) {
                            Box(Modifier.align(Alignment.CenterEnd).size(80.dp, 40.dp).testTag("stop").clickable { stops++ }) { Text("STOP") }
                        }
                        CompositionLocalProvider(LocalOverlayTopInset provides inset) { overlay() }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun pressStop() {
        // Pressed where STOP is drawn, so whatever lies on top there takes the press.
        val stop = compose.onNodeWithTag("stop", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        compose.onRoot().performTouchInput { click(stop.center) }
        compose.waitForIdle()
    }

    @Test
    fun stopTakesTheFirstPressWithASheetOpen() {
        var open by mutableStateOf(true)
        screen(TOP_BAR) { if (open) AdosSheet(onDismiss = { dismissals++; open = false }, title = "Flight modes") { Text("body") } }
        pressStop()
        assertEquals(1, stops)
        assertEquals(0, dismissals)
    }

    @Test
    fun stopTakesTheFirstPressWithADialogOpen() {
        screen(TOP_BAR) { AdosDialog(title = "Confirm", onDismiss = { dismissals++ }, confirm = { Text("OK") }) }
        pressStop()
        assertEquals(1, stops)
        assertEquals(0, dismissals)
    }

    @Test
    fun stopTakesTheFirstPressWithAMenuOpen() {
        screen(TOP_BAR) {
            val state = rememberAdosMenuState()
            state.expanded = true
            AdosMenu(state, listOf(AdosMenuItem("Settings") {}))
        }
        pressStop()
        assertEquals(1, stops)
    }

    @Test
    fun withoutAnInsetTheSheetCoversStop() {
        screen(0.dp) { AdosSheet(onDismiss = { dismissals++ }) { Text("body") } }
        pressStop()
        assertEquals(0, stops)
    }

    @Test
    fun aMenuAnchoredLowFlipsUpWithoutCoveringTheTopBar() {
        screen(TOP_BAR) {
            val state = rememberAdosMenuState()
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.align(Alignment.BottomEnd).size(80.dp, 40.dp).adosMenuAnchor(state).testTag("anchor"))
            }
            state.expanded = true
            AdosMenu(state, List(24) { AdosMenuItem("Item $it") {} })
        }
        val menuTop = compose.onNodeWithText("Item 0", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top
        val anchorTop = compose.onNodeWithTag("anchor").fetchSemanticsNode().boundsInRoot.top
        assertTrue("the menu flipped above its anchor", menuTop < anchorTop)
        val barBottom = with(compose.density) { TOP_BAR.toPx() }
        assertTrue("the menu starts below the top bar ($menuTop < $barBottom)", menuTop >= barBottom)
        pressStop()
        assertEquals(1, stops)
    }

    @Test
    fun aMenuWithoutAnAnchorStartsBelowTheTopBar() {
        screen(TOP_BAR) {
            val state = rememberAdosMenuState()
            state.expanded = true
            AdosMenu(state, listOf(AdosMenuItem("Settings") {}))
        }
        val menuTop = compose.onNodeWithText("Settings", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top
        assertTrue(menuTop >= with(compose.density) { TOP_BAR.toPx() })
    }

    @Test
    fun aMeasuredTopBarCoversAScreenOffsetFromTheWindowTop() {
        // The screen starts below a window caption bar or cutout; overlays still draw from the window root.
        compose.setContent {
            AdosTheme {
                ProvideOverlayHost {
                    val bar = rememberOverlayTopBar()
                    ProvideOverlayTopBar(bar) {
                        Box(Modifier.fillMaxSize().padding(top = 40.dp)) {
                            Box(Modifier.fillMaxWidth().height(TOP_BAR).overlayTopBar(bar)) {
                                Box(Modifier.align(Alignment.CenterEnd).size(80.dp, 40.dp).testTag("stop").clickable { stops++ }) { Text("STOP") }
                            }
                            AdosSheet(onDismiss = { dismissals++ }, title = "Flight modes") { Text("body") }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        pressStop()
        assertEquals(1, stops)
        assertEquals(0, dismissals)
    }

    private companion object {
        val TOP_BAR = 60.dp
    }
}
