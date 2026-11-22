package com.altnautica.gcs.feature.cockpit.hud

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isUnspecified
import com.altnautica.gcs.core.map.GeoMath
import com.altnautica.gcs.core.map.GeoPoint
import com.altnautica.gcs.core.map.MapMoverKind
import com.altnautica.gcs.core.model.LocalPosition
import com.altnautica.gcs.core.model.MoverKind
import com.altnautica.gcs.core.model.MoverSource
import com.altnautica.gcs.core.model.TrackedMover
import com.altnautica.gcs.feature.cockpit.mapMovers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where the minimaps draw the pilot's tracked movers: the arrow, the ring, the fade and the map position. */
class MinimapMoversTest {
    private fun mover(vn: Float = 0f, ve: Float = 1.4f, sigma: Float = 0.5f, ageMs: Long = 100L, kind: MoverKind = MoverKind.Person) =
        TrackedMover(3, kind, MoverSource.Vision, 10f, -2f, vn, ve, sigma, ageMs)

    @Test
    fun `the arrow shows two seconds of travel with north up the screen, and a still mover has none`() {
        val p = Offset(100f, 100f)
        assertEquals(Offset(130f, 100f), MinimapMovers.arrowTip(mover(ve = 1.5f), p, 10f))
        assertEquals(Offset(100f, 80f), MinimapMovers.arrowTip(mover(vn = 1f, ve = 0f), p, 10f))
        assertTrue(MinimapMovers.arrowTip(mover(vn = 0.05f, ve = 0.05f), p, 10f).isUnspecified)
    }

    @Test
    fun `the movers line counts them and names the nearest person's distance`() {
        val strings = com.altnautica.gcs.feature.cockpit.testSpec.setupStrings
        val people = listOf(mover(), mover(kind = MoverKind.Vehicle).copy(northM = 1f, eastM = 0f))
        // The person at (10, -2) from a drone at (4, 6): 10.0 m.
        assertEquals("Movers: 2 · nearest person 10.0 m", MinimapMovers.line(people, 4f, 6f, strings::format))
        assertEquals("Movers: 2", MinimapMovers.line(people, null, null, strings::format))
        assertEquals("Movers: 1", MinimapMovers.line(people.drop(1), 4f, 6f, strings::format))
        assertEquals(null, MinimapMovers.line(emptyList(), 4f, 6f, strings::format))
    }

    @Test
    fun `with no drone position, or only a stale one, the line counts movers without a distance`() {
        val strings = com.altnautica.gcs.feature.cockpit.testSpec.setupStrings
        fun inputs(fc: LocalPosition?, now: Long) =
            LocalMapInputs(null, fc, null, emptyList(), null, null, null, now, null, listOf(mover()))
        // No pose and no FC position: never measured from the origin.
        assertEquals("Movers: 1", MinimapMovers.line(inputs(null, 10_000L), strings::format))
        val fc = LocalPosition(4f, 6f, -15f, 0f, 0f, 0f, tMs = 10_000L)
        assertEquals("Movers: 1 · nearest person 10.0 m", MinimapMovers.line(inputs(fc, 12_000L), strings::format))
        assertEquals("Movers: 1", MinimapMovers.line(inputs(fc, 12_001L), strings::format))
        assertNull(MinimapMovers.freshLocal(fc, 12_001L))
        assertEquals(fc, MinimapMovers.freshLocal(fc, 12_000L))
    }

    @Test
    fun `the ring is the one-sigma radius, hidden inside the marker`() {
        assertEquals(15f, MinimapMovers.ringPx(mover(sigma = 1.5f), 10f, 8f), 1e-4f)
        assertEquals(0f, MinimapMovers.ringPx(mover(sigma = 0.5f), 10f, 8f), 0f)
    }

    @Test
    fun `a mover not measured for over a second fades`() {
        assertEquals(1f, MinimapMovers.alpha(mover(ageMs = 1_000L)), 0f)
        assertEquals(MinimapMovers.STALE_ALPHA, MinimapMovers.alpha(mover(ageMs = 1_001L)), 0f)
    }

    @Test
    fun `on the map a mover sits at its offset from the vehicle, its arrow two seconds on`() {
        val vehicle = GeoPoint(52.0, 4.0)
        val local = LocalPosition(4f, -2f, -15f, 0f, 0f, 0f, 0L)
        val m = mapMovers(listOf(mover(vn = 0f, ve = 1.5f, ageMs = 1_500L, kind = MoverKind.Vehicle)), vehicle, local).single()
        // 6 m north of the vehicle; the arrow 3 m east of that.
        assertEquals(6.0, GeoMath.distanceM(vehicle, m.position), 0.01)
        assertEquals(0.0, GeoMath.bearingDeg(vehicle, m.position).let { if (it > 180) it - 360 else it }, 0.1)
        assertEquals(3.0, GeoMath.distanceM(m.position, m.arrowTip!!), 0.01)
        assertEquals(MapMoverKind.Vehicle, m.kind)
        assertTrue(m.stale)
        assertEquals(0.5, m.sigmaM, 1e-6)
        assertNull(mapMovers(listOf(mover(vn = 0f, ve = 0f)), vehicle, local).single().arrowTip)
    }

    @Test
    fun `without the vehicle's map or local position nothing is placed`() {
        assertEquals(0, mapMovers(listOf(mover()), null, LocalPosition(0f, 0f, 0f, 0f, 0f, 0f, 0L)).size)
        assertEquals(0, mapMovers(listOf(mover()), GeoPoint(52.0, 4.0), null).size)
    }
}
