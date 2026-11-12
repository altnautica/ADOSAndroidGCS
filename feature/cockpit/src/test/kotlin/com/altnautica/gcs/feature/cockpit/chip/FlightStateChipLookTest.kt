package com.altnautica.gcs.feature.cockpit.chip

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.feature.cockpit.flow.LocalCockpitStrings
import com.altnautica.gcs.core.model.MissionState
import com.altnautica.gcs.core.model.PilotOwner
import com.altnautica.gcs.feature.cockpit.pilotState
import com.altnautica.gcs.feature.cockpit.testSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The flight-state chip is an outline in its state colour: a 6 dp dot, the text in the
 * chrome text, the secondary part inline, and no fill behind it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FlightStateChipLookTest {
    @get:Rule
    val compose = createComposeRule()

    private val ui = FlightStateChipUi(
        ruleId = "mission_active",
        primary = ChipText("Mission · WP 4/6", "mission", 0xFFFF3EC8),
        dim = false,
        button = null,
        secondary = listOf(ChipText("ADOS Pilot", "dim", 0xFF909090)),
    )

    private fun show() = compose.setContent {
        AdosTheme {
            CompositionLocalProvider(LocalCockpitStrings provides testSpec.setupStrings) {
                Box(Modifier.background(Color.White)) {
                    FlightStateChip(ui, onOpen = {}, onButton = {})
                }
            }
        }
    }

    @Test
    fun `the chip words are the spec's sentence case, not upper-cased`() {
        val chips = FlightStateChipResolver(testSpec.flightState, testSpec.tokens, testSpec.setupStrings.strings)
        val now = 10_000L
        val mission = chips.resolve(
            pilotState(PilotOwner.MissionFc, fcModeName = "AUTO", mission = MissionState.Active, receivedAtMs = now),
            ChipContext(now),
        )
        assertEquals("mission_active", mission.ruleId)
        assertTrue(mission.primary.text, mission.primary.text.startsWith("Mission · WP 2/6"))
        val pilot = chips.resolve(pilotState(PilotOwner.Pilot, receivedAtMs = now), ChipContext(now))
        assertTrue(pilot.secondary.map { it.text }.toString(), pilot.secondary.any { it.text == "ADOS Pilot" })
    }

    @Test
    fun `the chip leads with a 6 dp dot and keeps the secondary part on the same line`() {
        show()
        compose.onNodeWithTag(FLIGHT_CHIP_DOT_TAG, useUnmergedTree = true).assertWidthIsEqualTo(6.dp).assertHeightIsEqualTo(6.dp)
        val primary = compose.onNodeWithText("Mission · WP 4/6", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val secondary = compose.onNodeWithText(" · ADOS Pilot", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertEquals("one line", primary.top.value, secondary.top.value, 1f)
        assertTrue("after the primary", secondary.left >= primary.right)
        val chip = compose.onNodeWithTag(FLIGHT_CHIP_TAG).getUnclippedBoundsInRoot()
        assertTrue("the visual height is the chrome control height or the 48 dp touch target", chip.height <= 48.dp)
    }

    @Test
    fun `nothing fills the chip, so the video shows through between the outline and the text`() {
        show()
        val pixels = compose.onNodeWithTag(FLIGHT_CHIP_TAG).captureToImage().toPixelMap()
        val text = compose.onNodeWithText("Mission · WP 4/6", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val chip = compose.onNodeWithTag(FLIGHT_CHIP_TAG).getUnclippedBoundsInRoot()
        val density = compose.density.density
        // A point inside the outline, above the text: white page behind, no glass tint.
        val x = ((text.left - chip.left).value + 2f) * density
        val y = ((text.top - chip.top).value / 2f + 0.5f).coerceAtLeast(2.5f) * density
        val p = pixels[x.toInt(), y.toInt().coerceAtMost(pixels.height - 1)]
        assertTrue("background $p", p.red > 0.9f && p.green > 0.9f && p.blue > 0.9f)
    }
}
