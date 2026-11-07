package com.altnautica.gcs.feature.settings.controller

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.altnautica.gcs.core.skills.spec.CockpitSpecRepository
import com.altnautica.gcs.core.skills.spec.ControllerHotspotsSpec
import com.altnautica.gcs.core.skills.spec.DirectorySpecSource
import com.altnautica.gcs.core.skills.spec.LabelSide
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Controller drawing labels sit outside the art, never overlap each other and keep their anchors' order. */
class ControllerLabelLayoutTest {
    private val spec = CockpitSpecRepository(DirectorySpecSource(File("../cockpit/src/main/assets/cockpit-spec")))

    private fun requests(hotspots: ControllerHotspotsSpec, width: Float, height: Float) = hotspots.hotspots.map { (id, spot) ->
        LabelRequest(id, spot.labelSide, Offset(spot.labelAnchor[0], spot.labelAnchor[1]), width, height)
    }

    private fun assertClean(hotspots: ControllerHotspotsSpec, total: Float, gutter: Float, labelW: Float, labelH: Float) {
        val frame = layoutControllerLabels(requests(hotspots, labelW, labelH), total, gutter, hotspots.viewBox[2], hotspots.viewBox[3], gap = 12f, spacing = 4f)
        assertEquals(hotspots.hotspots.size, frame.labels.size)
        val placed = frame.labels.entries.toList()
        for ((id, label) in placed) {
            assertFalse("$id overlaps the art", label.rect.overlaps(frame.art))
            assertTrue("$id leaves the drawing: ${label.rect}", label.rect.left >= -0.5f && label.rect.right <= total + 0.5f)
            assertTrue("$id leaves the drawing: ${label.rect}", label.rect.top >= -0.5f && label.rect.bottom <= frame.height + 0.5f)
        }
        for (i in placed.indices) for (j in i + 1 until placed.size) {
            val a = placed[i]
            val b = placed[j]
            assertFalse("${a.key} overlaps ${b.key}", a.value.rect.overlaps(b.value.rect))
        }
    }

    private fun Rect.overlaps(other: Rect): Boolean =
        left < other.right - 0.5f && other.left < right - 0.5f && top < other.bottom - 0.5f && other.top < bottom - 0.5f

    @Test
    fun ds4LabelsClearTheArtAndEachOtherOnALandscapePhone() {
        assertClean(spec.ds4Hotspots, total = 2100f, gutter = 336f, labelW = 290f, labelH = 42f)
    }

    @Test
    fun ds4LabelsClearTheArtAndEachOtherInPortrait() {
        // A narrow drawing: labels wrap to two lines and the bands split into rows.
        assertClean(spec.ds4Hotspots, total = 1000f, gutter = 200f, labelW = 170f, labelH = 84f)
    }

    @Test
    fun genericPadLabelsClearTheArtAndEachOther() {
        assertClean(spec.genericHotspots, total = 2100f, gutter = 336f, labelW = 290f, labelH = 42f)
    }

    @Test
    fun sideLabelsKeepTheirAnchorOrder() {
        val hotspots = spec.ds4Hotspots
        val frame = layoutControllerLabels(requests(hotspots, 200f, 40f), 2100f, 336f, hotspots.viewBox[2], hotspots.viewBox[3], 12f, 4f)
        for (side in listOf(LabelSide.Left, LabelSide.Right)) {
            val ids = hotspots.hotspots.filterValues { it.labelSide == side }.entries.sortedBy { it.value.labelAnchor[1] }.map { it.key }
            val tops = ids.map { frame.labels.getValue(it).rect.top }
            assertEquals(tops.sorted(), tops)
        }
    }

    @Test
    fun spreadPushesOverlappingItemsApartWithinBounds() {
        val starts = spread(listOf(50f, 52f, 54f), listOf(20f, 20f, 20f), 0f, 100f, 4f)
        assertEquals(listOf(32f, 56f, 80f), starts)
    }
}
