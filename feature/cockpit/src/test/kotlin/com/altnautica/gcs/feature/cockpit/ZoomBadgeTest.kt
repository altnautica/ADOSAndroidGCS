package com.altnautica.gcs.feature.cockpit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.feature.cockpit.flow.LocalCockpitStrings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The digital zoom in use is named on the flight screen whether or not the zoom pill fits the right column. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w914dp-h411dp-land")
class ZoomBadgeTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(zoom: Int, zoomInColumn: Boolean) = compose.setContent {
        AdosTheme {
            CompositionLocalProvider(LocalCockpitStrings provides testSpec.setupStrings) {
                // At the window's right edge, like the right column: the badge hangs off its video side.
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.align(Alignment.CenterEnd).size(200.dp, 400.dp)) {
                        RightColumnLayout(
                            zoom = zoom,
                            zoomInColumn = zoomInColumn,
                            gap = 8.dp,
                            top = { Box(Modifier.size(56.dp)) },
                            zoomPill = { Box(Modifier.size(48.dp, 96.dp)) },
                            bottom = { Box(Modifier.size(48.dp)) },
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `the badge shows beside the zoom pill`() {
        show(zoom = 2, zoomInColumn = true)
        compose.onNodeWithTag("digital-zoom").assertIsDisplayed()
    }

    @Test
    fun `the badge shows when the zoom pill dropped out of the column`() {
        show(zoom = 4, zoomInColumn = false)
        compose.onNodeWithTag("digital-zoom").assertIsDisplayed()
    }

    @Test
    fun `no badge without digital zoom`() {
        show(zoom = 1, zoomInColumn = false)
        compose.onNodeWithTag("digital-zoom").assertDoesNotExist()
    }
}
