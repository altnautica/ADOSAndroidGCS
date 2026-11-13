package com.altnautica.gcs.feature.cockpit.hud

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.theme.AdosTypography
import com.altnautica.gcs.core.designsystem.tokens.AdosColors
import com.altnautica.gcs.core.model.Attitude
import com.altnautica.gcs.feature.cockpit.autonomy.OccupancyLayer
import com.altnautica.gcs.feature.cockpit.autonomy.PoseFrame
import com.altnautica.gcs.feature.cockpit.autonomy.PoseHealth
import com.altnautica.gcs.feature.cockpit.autonomy.TrailPoint
import com.altnautica.gcs.feature.cockpit.flow.LocalCockpitStrings
import com.altnautica.gcs.feature.cockpit.testSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.lang.management.ManagementFactory

/**
 * The local minimap's moving parts (pose, heading, trail) are read by its draw pass only: a
 * stream of new samples redraws the map's layer without recomposing the card, and a steady
 * draw allocates nothing per tile, trail segment or label.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LocalMinimapRedrawTest {
    @get:Rule
    val compose = createComposeRule()

    private fun trail(n: Int) = List(n) { i ->
        TrailPoint(i * 0.2f, (i % 20) * 0.3f, if (i % 7 == 0) PoseHealth.Degraded else PoseHealth.Good, atMs = i * 100L)
    }

    private fun inputs(i: Int, now: Long = 1_000L) = LocalMapInputs(
        pose = PoseFrame(i * 0.1f, i * 0.05f, i * 0.02f, PoseHealth.Good, receivedAtMs = 1_000L),
        fcPosition = null,
        attitude = Attitude(0f, 0f, i * 0.03f, 1_000L),
        trail = trail(20 + i),
        scan = null,
        occupancy = null,
        follow = null,
        nowMs = now,
        plan = LocalPlan(listOf(0f to 0f, 4f to 1f, 6f + i * 0.1f to 3f), 6f + i * 0.1f to 3f, blocked = false),
    )

    @Test
    fun `new pose, heading and trail samples redraw the map without recomposing the card`() {
        val state = mutableStateOf(inputs(0))
        var recompositions = 0
        compose.setContent {
            CompositionLocalProvider(LocalCockpitStrings provides testSpec.setupStrings) {
                AdosTheme {
                    LocalMinimap({ state.value }, "auto", {}, Modifier.size(200.dp), recompositionProbe = { recompositions++ })
                }
            }
        }
        compose.waitForIdle()
        val composed = recompositions
        repeat(30) { i ->
            state.value = inputs(i + 1)
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        assertEquals("a moving drone never recomposes the card", composed, recompositions)

        // The pose going stale flips the FC POS badge: that one change recomposes the card.
        state.value = inputs(31, now = 1_000L + 60_000L)
        compose.waitForIdle()
        assertTrue("the FC POS badge follows the pose going stale", recompositions > composed)
    }

    @Test
    fun `a steady map draw allocates nothing per tile, trail segment or label`() {
        val density = Density(2f)
        val measurer = TextMeasurer(createFontFamilyResolver(ApplicationProvider.getApplicationContext()), density, LayoutDirection.Ltr)
        val painter = MinimapPainter(measurer, AdosColors.BrandDark, AdosTypography.numeric.small, "ORIGIN")
        val tiles = HashMap<Pair<Int, Int>, ImageBitmap>()
        for (ix in -1..0) for (iy in -1..0) tiles[ix to iy] = ImageBitmap(64, 64)
        val map = inputs(5).copy(trail = trail(TRAIL), occupancy = OccupancyLayer(0.1f, 64, tiles, lastOkMs = 1_000L))
        val canvas = Canvas(ImageBitmap(400, 400))
        val scope = CanvasDrawScope()
        fun drawOnce() = scope.draw(density, LayoutDirection.Ltr, canvas, Size(400f, 400f)) {
            with(painter) { paint(map, 10f, Offset.Zero, 5) }
        }
        repeat(20) { drawOnce() }
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val before = bean.currentThreadAllocatedBytes
        repeat(DRAWS) { drawOnce() }
        val perDraw = (bean.currentThreadAllocatedBytes - before) / DRAWS
        // Mapping each trail point through a pair (and boxing both floats) costs over 100 bytes a
        // segment, 20 KiB a draw for this trail before the per-tile tint and the label re-measure.
        // A cached draw measures about 9 KiB here, most of it the shadow canvas's own allocation.
        assertTrue("steady minimap draw allocated $perDraw bytes per draw", perDraw < 12_288)
    }

    private companion object {
        const val TRAIL = 200
        const val DRAWS = 50
    }
}
