package com.altnautica.gcs.feature.home

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import com.altnautica.gcs.core.designsystem.component.console.ConsoleCommandDispatcher
import com.altnautica.gcs.core.designsystem.component.console.LocalConsoleCommands
import com.altnautica.gcs.core.designsystem.component.overlay.ProvideOverlayHost
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import java.io.File
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Home on a 1080×2400 phone in portrait, its wording, and the first-launch disclaimer. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-port-xxhdpi")
class HomeLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    private val res get() = ApplicationProvider.getApplicationContext<Context>().resources

    private fun setHome(state: HomeUiState = HomeUiState(loading = false)) {
        compose.setContent {
            AdosTheme {
                CompositionLocalProvider(LocalConsoleCommands provides remember { ConsoleCommandDispatcher() }) {
                    ProvideOverlayHost {
                        HomeScreen(
                            state = state,
                            thumbnails = { flowOf(null) },
                            onSelect = {},
                            onSelectNext = {},
                            onEnableDemo = {},
                            onRetrySavedNodes = {},
                            onOpenSurface = { _, _ -> },
                            onUnpair = {},
                            onAddVehicle = {},
                            onOpenController = {},
                            onOpenQuickMenu = {},
                        )
                    }
                }
            }
        }
    }

    @Test
    fun quickMenuButtonIsAFullTouchTarget() {
        setHome()
        val b = compose.onNodeWithTag("home-quick-menu").fetchSemanticsNode().boundsInRoot
        with(compose.density) {
            assertTrue("width ${b.width.toDp()}", b.width.toDp() >= 47.5.dp)
            assertTrue("height ${b.height.toDp()}", b.height.toDp() >= 47.5.dp)
        }
    }

    @Test
    fun anEmptyFleetOffersAddDroneOnce() {
        setHome()
        assertEquals(1, compose.onAllNodesWithText(res.getString(R.string.home_add_vehicle)).fetchSemanticsNodes().size)
    }

    @Test
    fun allSevenSurfaceCardsFitInPortrait() {
        setHome()
        val settings = compose.onNodeWithText(res.getString(R.string.home_surface_settings), useUnmergedTree = true)
        settings.assertIsDisplayed()
        val bottom = settings.fetchSemanticsNode().boundsInRoot.bottom
        val screen = with(compose.density) { 914.dp.toPx() }
        assertTrue("Settings label bottom $bottom past $screen", bottom <= screen)
        assertEquals(3, PORTRAIT_GRID_COLUMNS)
    }

    @Test
    fun operatorTextSaysDroneNotVehicle() {
        val strings = Regex("""<string name="(home_[^"]+)"[^>]*>(.*?)</string>""")
            .findAll(File("src/main/res/values/strings.xml").readText())
            .associate { it.groupValues[1] to it.groupValues[2] }
        val vehicle = strings.filterValues { it.contains("vehicle", ignoreCase = true) }
        assertTrue("operator text still says vehicle: $vehicle", vehicle.isEmpty())
        assertEquals("Flight logs", res.getString(R.string.home_surface_logs))
        assertEquals("Demo drone", res.getString(R.string.home_demo_vehicle))
    }

    @Test
    fun controlsEntryUsesTheSpecName() {
        val spec = File("../cockpit/src/main/assets/cockpit-spec/setup-strings.json").readText()
        val title = Regex(""""controls\.title"\s*:\s*"([^"]+)"""").find(spec)!!.groupValues[1]
        assertEquals(title, res.getString(R.string.home_quick_controller))
    }

    @Test
    fun disclaimerAcceptStaysOffUntilTicked() {
        var accepted = false
        compose.setContent { AdosTheme { DisclaimerScreen(onAccept = { accepted = true }) } }
        compose.onNodeWithTag("disclaimer-accept").assertIsNotEnabled()
        compose.onNodeWithTag("disclaimer-accept-check").performClick()
        compose.onNodeWithTag("disclaimer-accept").assertIsEnabled().performClick()
        assertTrue(accepted)
    }
}
