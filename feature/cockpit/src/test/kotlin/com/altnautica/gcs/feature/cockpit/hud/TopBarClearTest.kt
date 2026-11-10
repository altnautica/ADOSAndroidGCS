package com.altnautica.gcs.feature.cockpit.hud

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.feature.cockpit.chrome.flightSizes
import com.altnautica.gcs.feature.cockpit.chrome.flightZones
import com.altnautica.gcs.feature.cockpit.skillbar.WidthClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Layer and target labels never draw under the top bar; toasts sit in the centre zone, clear of the bottom chrome. */
class TopBarClearTest {
    @Test
    fun `a label above the top bar's edge slides below it`() {
        assertEquals(Offset(40f, 120f), clearOfTopBar(Offset(40f, 30f), 120f))
        assertEquals(Offset(40f, 200f), clearOfTopBar(Offset(40f, 200f), 120f))
    }

    @Test
    fun `the centre zone that holds toasts is clear of the card, bottom row and skills chip on a phone`() {
        val z = flightZones(914.dp, 411.dp, flightSizes(WidthClass.Expanded), gimbal = true)
        for (other in listOf(z.card, z.bottomRow, z.skills, z.leftColumn, z.rightColumn, z.topBar)) {
            val disjoint = z.centre.right <= other.x || other.right <= z.centre.x || z.centre.bottom <= other.y || other.bottom <= z.centre.y
            assertTrue("centre $z overlaps $other", disjoint)
        }
    }
}
