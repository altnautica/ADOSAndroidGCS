package com.altnautica.gcs.feature.cockpit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altnautica.gcs.core.data.cockpit.CockpitSettingsRepository
import com.altnautica.gcs.core.designsystem.component.overlay.ProvideOverlayHost
import com.altnautica.gcs.core.designsystem.component.overlay.LocalOverlayTopInset
import com.altnautica.gcs.core.designsystem.component.overlay.ProvideOverlayTopBar
import com.altnautica.gcs.core.designsystem.component.overlay.rememberOverlayTopBar
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.model.VehicleCommand
import com.altnautica.gcs.core.skills.builtin.BuiltinIds
import com.altnautica.gcs.feature.cockpit.skillbar.WidthClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.minutes

/**
 * STOP, Land and Return home take the top layer of the flight screen whatever is open: with
 * the top-bar Menu open, the confirm sheet draws above it and the first hold on the sheet
 * sends the command. The Menu closes as the sheet appears.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w914dp-h411dp-land-xxhdpi", application = RedrawTestApp::class)
class SafetyOverMenuTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `STOP pressed with the Menu open stops the drone on the first hold`() = flightScreen(menuOpen = true) {
        tap("stop")
        holdFirstTime(strings.format("stop.hold_label"), holdMs = 3_000)
        assertEquals(listOf<VehicleCommand>(VehicleCommand.Kill), vehicle.control.sent)
        assertMenuClosed()
    }

    @Test
    fun `Land opened with the Menu open lands on the first hold`() = flightScreen(menuOpen = true) {
        // The pad's Land: a safety action opens its sheet with any overlay open.
        compose.runOnIdle { vm.activate(BuiltinIds.LAND) }
        holdFirstTime(strings.format("confirm.hold", mapOf("s" to "1.5")), holdMs = 1_500)
        assertEquals(listOf<VehicleCommand>(VehicleCommand.Land), vehicle.control.sent)
        assertMenuClosed()
    }

    @Test
    fun `Return home opened with the Menu open returns on the first hold`() = flightScreen(menuOpen = true) {
        compose.runOnIdle { vm.activate(BuiltinIds.RTH) }
        holdFirstTime(strings.format("confirm.hold", mapOf("s" to "1.5")), holdMs = 1_500)
        assertEquals(listOf<VehicleCommand>(VehicleCommand.ReturnToLaunch), vehicle.control.sent)
        assertMenuClosed()
    }

    @Test
    fun `a press just below the top bar lands on the confirm sheet, not on the screen under it`() = flightScreen(menuOpen = false) {
        compose.runOnIdle { vm.activate(BuiltinIds.RTH) }
        frames(100)
        assertNotNull("the sheet is open", vm.confirm.state.value)
        // Inside the strip as tall as the window's top safe area, just under the top bar, left of the sheet panel.
        val y = with(compose.density) { (topInset + SAFE_TOP / 2).toPx() }
        compose.onRoot().performTouchInput { click(Offset(width * 0.3f, y)) }
        frames(200)
        assertNull("the sheet's scrim took the press and closed the sheet", vm.confirm.state.value)
        assertTrue(vehicle.control.sent.isEmpty())
    }

    /** The top bar's bottom edge in the window, as the flight screen provides it to its overlays. */
    private var topInset = 0.dp

    private val strings = testSpec.setupStrings

    private inner class Screen(private val scope: TestScope, val vm: CockpitViewModel, val vehicle: FakeVehicle) {
        fun frame() {
            vehicle.heartbeatAt(scope.testScheduler.currentTime, armed = true, mode = "GUIDED")
            scope.testScheduler.advanceTimeBy(FRAME_MS)
            scope.runCurrent()
            compose.mainClock.advanceTimeBy(FRAME_MS)
        }

        fun frames(ms: Long) = repeat((ms / FRAME_MS).toInt()) { frame() }

        fun tap(tag: String) {
            val bounds = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            compose.onRoot().performTouchInput { click(bounds.center) }
            frames(100)
        }

        /** Presses the sheet's hold control once, for longer than [holdMs], and releases it. */
        fun holdFirstTime(label: String, holdMs: Long) {
            frames(100)
            val nodes = compose.onAllNodesWithText(label).fetchSemanticsNodes()
            assertEquals("one hold control on the sheet", 1, nodes.size)
            val at = nodes.single().boundsInRoot.center
            compose.onRoot().performTouchInput { down(at) }
            frames(holdMs + 500)
            compose.onRoot().performTouchInput { up() }
            frames(200)
        }

        fun assertMenuClosed() =
            assertTrue("the Menu closed as the sheet appeared", compose.onAllNodesWithText(strings.format("cockpit.menu.view")).fetchSemanticsNodes().isEmpty())
    }

    /** The flight screen as the route draws it, armed in flight, with its top-bar Menu open when [menuOpen]. */
    private fun flightScreen(menuOpen: Boolean, block: Screen.() -> Unit) {
        val dispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(dispatcher)
        try {
            runTest(dispatcher, timeout = 5.minutes) {
                val vehicle = FakeVehicle()
                val vm = testCockpitVm(vehicle)
                vehicle.heartbeatAt(testScheduler.currentTime, armed = true, mode = "GUIDED")
                // The local minimap: the map card's MapLibre view has no native library here.
                vm.autonomy.setMinimapPin(CockpitSettingsRepository.MINIMAP_LOCAL)
                vm.setFlightViewShown(true)
                runCurrent()
                val activity = Robolectric.buildActivity(RedrawTestActivity::class.java).create().get()
                compose.mainClock.autoAdvance = false
                compose.setContent {
                    CompositionLocalProvider(LocalContext provides activity, LocalViewModelStoreOwner provides extensionViewModels(vehicle.id.value)) {
                        AdosTheme {
                            ProvideOverlayHost {
                                val topBar = rememberOverlayTopBar()
                                ProvideOverlayTopBar(topBar) {
                                    topInset = LocalOverlayTopInset.current
                                    // The route's safe-drawing padding: the flight screen starts below the window top.
                                    Box(Modifier.fillMaxSize().padding(top = SAFE_TOP)) {
                                        CockpitScreen(vm, WidthClass.Expanded, connecting = false, topBar = topBar, onExit = {}, snackbar = {})
                                    }
                                }
                            }
                        }
                    }
                }
                val screen = Screen(this, vm, vehicle)
                screen.frames(1_000)
                if (menuOpen) {
                    screen.tap("menu")
                    assertTrue(
                        "the Menu is open",
                        compose.onAllNodesWithText(strings.format("cockpit.menu.view")).fetchSemanticsNodes().isNotEmpty(),
                    )
                }
                vehicle.control.sent.clear()
                screen.block()
                vm.viewModelScope.cancel()
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    private companion object {
        const val FRAME_MS = 16L
        val SAFE_TOP = 32.dp
    }
}
