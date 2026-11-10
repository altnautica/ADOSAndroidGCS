package com.altnautica.gcs.feature.cockpit.chrome

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altnautica.gcs.feature.cockpit.skillbar.WidthClass
import com.altnautica.gcs.feature.cockpit.skillbar.widthClassFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** Window sizes the flight screen must lay out cleanly in (dp, landscape only). */
private val WINDOWS = listOf(
    568f to 320f, // small phone
    640f to 360f,
    780f to 360f, // phone, 19.5:9
    914f to 411f, // the test AVD in landscape
    1024f to 600f, // small tablet
    1280f to 800f, // tablet
    1920f to 1080f, // TV
)

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp-land")
class FlightLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `no two zones intersect and every zone is inside the window`() {
        for ((w, h) in WINDOWS) {
            for (gimbal in listOf(false, true)) {
                val width = widthClassFor(w)
                val zones = flightZones(w.dp, h.dp, flightSizes(width), gimbal).named()
                for ((name, rect) in zones) {
                    assertTrue("$name inside ${w}x$h", rect.inside(w.dp, h.dp))
                    assertTrue("$name not empty at ${w}x$h", rect.width > 0.dp && rect.height > 0.dp)
                }
                for (i in zones.indices) for (j in i + 1 until zones.size) {
                    val (a, ra) = zones[i]
                    val (b, rb) = zones[j]
                    assertFalse("$a overlaps $b at ${w}x$h gimbal=$gimbal: $ra / $rb", ra.intersects(rb))
                }
            }
        }
    }

    @Test
    fun `left column keeps its three 56 dp buttons and the centre stays usable`() {
        for ((w, h) in WINDOWS) {
            val sizes = flightSizes(widthClassFor(w))
            val z = flightZones(w.dp, h.dp, sizes, gimbal = true)
            assertTrue("left column at ${w}x$h", z.leftColumn.height >= sizes.leftColumnHeight)
            assertTrue("56 dp targets", sizes.leftButton >= 56.dp && sizes.shutter >= 56.dp)
            assertTrue("centre is the widest zone at ${w}x$h", z.centre.width >= w.dp * 0.4f)
        }
    }

    @Test
    fun `the gimbal pad joins the column on tall windows and sits beside it on phones`() {
        assertFalse(flightZones(914.dp, 411.dp, flightSizes(WidthClass.Expanded), gimbal = true).gimbalInColumn)
        assertTrue(flightZones(1280.dp, 800.dp, flightSizes(WidthClass.Expanded), gimbal = true).gimbalInColumn)
        assertEquals(null, flightZones(914.dp, 411.dp, flightSizes(WidthClass.Expanded), gimbal = false).gimbalPad)
    }

    @Test
    fun `rendered grid places each zone at its rectangle without overlap`() {
        compose.setContent {
            Box(Modifier.size(780.dp, 360.dp)) {
                FlightGrid(
                    width = WidthClass.Medium,
                    gimbal = true,
                    background = {},
                    topBar = {},
                    leftColumn = {},
                    rightColumn = {},
                    gimbalPad = {},
                    card = {},
                    bottomRow = {},
                    skills = {},
                    centre = {},
                )
            }
        }
        val tags = listOf("zone-top-bar", "zone-left-column", "zone-right-column", "zone-gimbal-pad", "zone-card", "zone-bottom-row", "zone-skills")
        val rects = tags.map { it to compose.onNodeWithTag(it).getBoundsInRoot() }
        val root = compose.onRoot().getBoundsInRoot()
        for ((tag, r) in rects) {
            assertTrue("$tag inside the window", r.left >= root.left && r.top >= root.top && r.right <= root.right && r.bottom <= root.bottom)
        }
        for (i in rects.indices) for (j in i + 1 until rects.size) {
            assertFalse("${rects[i].first} overlaps ${rects[j].first}", rects[i].second.overlaps(rects[j].second))
        }
    }

    @Test
    fun `STOP stays fully visible and lower-priority items move to the Menu in order`() {
        val cases = listOf(
            1100.dp to emptySet(),
            800.dp to setOf(TopBarItem.Controller, TopBarItem.NavSource),
            // The spec moves every item, link icon and layer button included, before the pill truncates.
            560.dp to TopBarItem.entries.toSet(),
        )
        val overflow = TopBarOverflow()
        var barWidth by mutableStateOf(cases.first().first)
        compose.setContent {
            Box(Modifier.size(barWidth, 44.dp)) {
                TopBar(
                    overflow = overflow,
                    overflowOrder = SPEC_ORDER,
                    chip = { Item("chip", 150.dp) },
                    pill = { Item("pill", 150.dp) },
                    battery = { Item("battery", 120.dp) },
                    linkIcon = { Item("link", 20.dp) },
                    linkDetail = { Item("link-detail", 90.dp) },
                    navSource = { Item("nav", 110.dp) },
                    controller = { Item("controller", 80.dp) },
                    layers = { Item("layers", 40.dp) },
                    menu = { Item("menu", 40.dp) },
                    stop = { Item("stop", 72.dp) },
                )
            }
        }
        for ((w, expected) in cases) {
            barWidth = w
            compose.waitForIdle()
            assertEquals("moved at $barWidth", expected, overflow.moved)
            val bar = compose.onNodeWithTag("top-bar").getBoundsInRoot()
            for (tag in listOf("stop", "pill", "battery", "menu")) {
                val r = compose.onNodeWithTag(tag).getBoundsInRoot()
                assertTrue("$tag fully inside the bar at $barWidth: $r in $bar", r.left >= bar.left && r.right <= bar.right && r.right > r.left)
            }
            val stop = compose.onNodeWithTag("stop").getBoundsInRoot()
            assertEquals("STOP keeps its width at $barWidth", 72f, (stop.right - stop.left).value, 0.5f)
        }
    }

    @Test
    fun `on a narrow window with a long alert the left group never slides under the right group`() {
        val overflow = TopBarOverflow()
        var barWidth by mutableStateOf(640.dp)
        compose.setContent {
            Box(Modifier.size(barWidth, 44.dp)) {
                TopBar(
                    overflow = overflow,
                    overflowOrder = SPEC_ORDER,
                    chip = { Item("chip", 150.dp) },
                    pill = { Item("pill", 260.dp) },
                    battery = { Item("battery", 114.dp) },
                    linkIcon = { Item("link", 20.dp) },
                    linkDetail = { Item("link-detail", 90.dp) },
                    navSource = { Item("nav", 110.dp) },
                    controller = { Item("controller", 80.dp) },
                    layers = { Item("layers", 150.dp) },
                    menu = { Item("menu", 40.dp) },
                    stop = { Item("stop", 72.dp) },
                )
            }
        }
        for (w in listOf(700.dp, 640.dp, 560.dp)) {
            barWidth = w
            compose.waitForIdle()
            val left = listOf("pill", "battery", "link").map { compose.onNodeWithTag(it).getBoundsInRoot() }
            val right = listOf("menu", "stop").map { compose.onNodeWithTag(it).getBoundsInRoot() }
            val leftEnd = left.maxOf { it.right }
            val rightStart = right.minOf { it.left }
            assertTrue("left group ends at $leftEnd, right group starts at $rightStart at $w", leftEnd <= rightStart)
            if (TopBarItem.Layers !in overflow.moved) {
                val layers = compose.onNodeWithTag("layers").getBoundsInRoot()
                assertTrue("the layer button is clear of the left group at $w", leftEnd <= layers.left)
            }
            val stop = compose.onNodeWithTag("stop").getBoundsInRoot()
            assertEquals("STOP keeps its width at $w", 72f, (stop.right - stop.left).value, 0.5f)
        }
        assertTrue("at 560 dp the layer button is in the Menu", TopBarItem.Layers in overflow.moved)
    }

    @Test
    fun `the flight screen draws no legacy chrome`() {
        val root = listOf("src/main/kotlin", "feature/cockpit/src/main/kotlin").map(::File).first { it.isDirectory }
        val cockpit = File(root, "com/altnautica/gcs/feature/cockpit")
        val screen = File(cockpit, "CockpitScreen.kt").readText()
        for (legacy in listOf("SafetyBand", "TelemetryStrip", "StreamTabs", "HintBar", "PortraitLayout", "NavigationRail")) {
            assertFalse("CockpitScreen draws $legacy", Regex("\\b$legacy\\b").containsMatchIn(screen))
        }
        assertFalse("the status band is gone", File(cockpit, "band/SafetyBand.kt").exists())
        val all = cockpit.walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        for (legacy in listOf("fun SafetyBand(", "fun TelemetryStrip(", "class StripValues")) {
            assertFalse("$legacy still defined", legacy in all)
        }
    }
}

@androidx.compose.runtime.Composable
private fun Item(tag: String, w: Dp) {
    Box(Modifier.width(w).size(w, 32.dp).testTag(tag))
}

private fun DpRect.overlaps(o: DpRect): Boolean = left < o.right && o.left < right && top < o.bottom && o.top < bottom

/** The spec's top-bar overflow order (`top_bar.overflow.to_menu_order`). */
internal val SPEC_ORDER: List<TopBarItem> =
    TopBarItem.order(com.altnautica.gcs.feature.cockpit.testSpec.layout.zones.first { it.id == "top_bar" }.overflow?.toMenuOrder.orEmpty())
