package com.altnautica.gcs.feature.cockpit.chip

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.feature.cockpit.flow.LocalCockpitStrings
import com.altnautica.gcs.feature.cockpit.testSpec
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The flight-state chip carries its state in colour and text only: no glyph ahead of the text. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class FlightStateChipGlyphTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the chip text starts at the chip's padding, with no leading icon`() {
        val ui = FlightStateChipUi(
            ruleId = "mission_active",
            primary = ChipText("ADOS · Mission", "accent", 0xFF3B82F6),
            dim = false,
            button = null,
            secondary = emptyList(),
        )
        compose.setContent {
            AdosTheme {
                CompositionLocalProvider(LocalCockpitStrings provides testSpec.setupStrings) {
                    FlightStateChip(ui, onOpen = {}, onButton = {})
                }
            }
        }
        val chip = compose.onNodeWithTag(FLIGHT_CHIP_TAG).getUnclippedBoundsInRoot()
        val text = compose.onNodeWithText("ADOS · Mission", useUnmergedTree = true).getUnclippedBoundsInRoot()
        // Horizontal padding is 8 dp; a 20 dp icon and its gap would push the text past 24 dp.
        assertTrue("text inset ${text.left - chip.left}", text.left - chip.left < 16.dp)
    }
}
