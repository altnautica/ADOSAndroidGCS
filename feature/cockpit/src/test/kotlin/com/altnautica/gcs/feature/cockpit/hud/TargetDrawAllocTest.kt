package com.altnautica.gcs.feature.cockpit.hud

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altnautica.gcs.core.designsystem.theme.AdosTypography
import com.altnautica.gcs.core.designsystem.tokens.AdosColors
import com.altnautica.gcs.feature.cockpit.autonomy.Box
import com.altnautica.gcs.feature.cockpit.autonomy.CockpitAutonomy
import com.altnautica.gcs.feature.cockpit.autonomy.DrawItem
import com.altnautica.gcs.feature.cockpit.autonomy.TargetKind
import com.altnautica.gcs.feature.cockpit.autonomy.TargetTag
import com.altnautica.gcs.feature.cockpit.autonomy.TargetText
import com.altnautica.gcs.feature.cockpit.testSpec
import io.mockk.mockk
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.lang.management.ManagementFactory

/**
 * The targets layer redraws with every detection frame, so a draw of unchanged targets must
 * not build a painter, paths, strokes, dash effects or tag texts: they are kept across draws.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TargetDrawAllocTest {
    private val items = listOf(
        DrawItem(TargetKind.Candidate, Box(40f, 60f, 80f, 120f), TargetTag.Candidate("person", 62), emptyList(), emptySet(), trackId = null, confidence = 0.62f),
        DrawItem(TargetKind.Tracked, Box(300f, 80f, 90f, 160f), TargetTag.Tracked("person", 7, uncertain = false), emptyList(), emptySet(), trackId = 7, confidence = 0.8f),
        DrawItem(
            TargetKind.Following, Box(600f, 120f, 120f, 220f), TargetTag.Track(91, uncertain = false),
            listOf(TargetTag.Range(14.5f), TargetTag.Sigma(1.2f)), emptySet(), trackId = 9, confidence = 0.91f,
        ),
        DrawItem(
            TargetKind.Coasting, Box(900f, 200f, 80f, 140f), TargetTag.Tracked("car", 11, uncertain = false),
            listOf(TargetTag.Estimate(30f)), emptySet(), trackId = 11, confidence = 0.5f,
            ghost = listOf(860f to 330f, 880f to 335f, 900f to 340f, 940f to 340f),
        ),
    )

    @Test
    fun `a steady targets draw allocates no painter, paths, strokes or tag texts`() {
        val density = Density(2f)
        val measurer = TextMeasurer(createFontFamilyResolver(ApplicationProvider.getApplicationContext()), density, LayoutDirection.Ltr)
        // Only lost and re-acquired targets read the autonomy feed; none of these do.
        val drawer = TargetDrawer(
            AdosColors.BrandDark, measurer, AdosTypography.numeric.small, TargetText(testSpec.setupStrings),
            MotionPrefs(reducedMotion = false, lowPower = false), mockk<CockpitAutonomy>(), compact = false,
        )
        val canvas = Canvas(ImageBitmap(1280, 720))
        val scope = CanvasDrawScope()
        val view = Rect(0f, 0f, 1280f, 720f)
        fun drawOnce() = scope.draw(density, LayoutDirection.Ltr, canvas, Size(1280f, 720f)) {
            with(drawer) { drawItems(items, view, 1f, 5_000L, 1f) }
        }
        repeat(WARM_UP) { drawOnce() }
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val before = bean.currentThreadAllocatedBytes
        repeat(DRAWS) { drawOnce() }
        val perDraw = (bean.currentThreadAllocatedBytes - before) / DRAWS
        assertTrue("steady targets draw allocated $perDraw bytes per draw", perDraw < BOUND)
    }

    private companion object {
        const val WARM_UP = 20
        const val DRAWS = 50

        /**
         * Above the shadow canvas's own allocation for about forty calls (1.4 to 2.5 KiB, see
         * LayerStackTest) and the per-item view rects; a draw that builds a painter, a path and
         * two strokes per outline and formats and measures each of its six tags twice allocates
         * several times this.
         */
        const val BOUND = 6_144L
    }
}
