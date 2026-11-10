package com.altnautica.gcs.feature.cockpit.preflight

import com.altnautica.gcs.core.model.EkfState
import com.altnautica.gcs.core.model.GpsFix
import com.altnautica.gcs.core.model.GpsState
import com.altnautica.gcs.core.model.HomePosition
import com.altnautica.gcs.core.data.cockpit.BatteryBands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

import com.altnautica.gcs.core.model.ControlMode
import com.altnautica.gcs.feature.cockpit.autonomy.NavChip
import com.altnautica.gcs.feature.cockpit.autonomy.NavLabel
import com.altnautica.gcs.feature.cockpit.autonomy.NavTone
import com.altnautica.gcs.feature.cockpit.skillbar.WidthClass
import com.altnautica.gcs.feature.cockpit.testSpec

class PreflightChecklistTest {
    private val now = 50_000L
    private val good = PreflightInputs(
        gps = GpsState(GpsFix.Fix3D, 12, 0.8f, now),
        batteryPct = 90,
        bands = BatteryBands(30, 20),
        ekf = EkfState(flags = 1 or 2 or 4, velVar = 0.1f, posHorizVar = 0.1f, posVertVar = 0.1f, compassVar = 0.1f, tMs = now),
        home = HomePosition(47.0, 8.0, 400f, now),
        linkFresh = true,
        lastPrearmMs = null,
        navChip = NavChip(NavLabel.Gps, null, NavTone.Good),
        storageUsedPct = 40.0,
        storageWarnPct = 90,
    )

    private fun failing(i: PreflightInputs) = PreflightChecklist.evaluate(i, now).failing.map { it.item }

    @Test
    fun `on-screen sticks shown at this width satisfy the controller check, hidden ones do not`() {
        val classes = testSpec.layout.onScreenSticks.classes
        val shown = WidthClass.entries.first { it.id in classes }
        val hidden = WidthClass.entries.first { it.id !in classes }
        fun ready(pad: Boolean, onScreen: Boolean, width: WidthClass?) = PreflightChecklist.evaluate(
            good.copy(controlMode = ControlMode.Ados, controllerConnected = sticksAvailable(pad, onScreen, width, classes)),
            now,
        ).ready
        assertTrue("sticks shown at this width", ready(pad = false, onScreen = true, width = shown))
        assertFalse("sticks enabled but hidden at this width", ready(pad = false, onScreen = true, width = hidden))
        assertFalse("sticks off", ready(pad = false, onScreen = false, width = shown))
        assertFalse("width not reported yet", ready(pad = false, onScreen = true, width = null))
        assertTrue("a pad", ready(pad = true, onScreen = false, width = hidden))
    }

    @Test
    fun `all checks pass when every input is healthy`() {
        val r = PreflightChecklist.evaluate(good, now)
        assertTrue(r.ready)
        assertEquals(8, r.passed)
        assertEquals(8, r.total)
    }

    @Test
    fun `each input fails its own check`() {
        assertEquals(listOf(PreflightItem.Gps3d), failing(good.copy(gps = GpsState(GpsFix.Fix2D, 6, 2f, now))))
        assertEquals(listOf(PreflightItem.Battery), failing(good.copy(batteryPct = 25)))
        // At the low band the pack already warns in flight: the check fails with it.
        assertEquals(listOf(PreflightItem.Battery), failing(good.copy(batteryPct = 30)))
        assertEquals(listOf(PreflightItem.Ekf), failing(good.copy(ekf = good.ekf!!.copy(compassVar = 0.9f))))
        assertEquals(listOf(PreflightItem.Ekf), failing(good.copy(ekf = good.ekf!!.copy(flags = 1 or (1 shl 10)))))
        assertEquals(listOf(PreflightItem.Home), failing(good.copy(home = null)))
        assertEquals(listOf(PreflightItem.Link), failing(good.copy(linkFresh = false)))
    }

    @Test
    fun `home passes on the ground from the flight controller's home position or its EKF origin`() {
        // No arming needed: HOME_POSITION alone passes, and so does an EKF origin before any home is reported.
        assertTrue(PreflightItem.Home !in failing(good.copy(home = HomePosition(47.0, 8.0, 400f, now), ekfOriginSet = false)))
        assertTrue(PreflightItem.Home !in failing(good.copy(home = null, ekfOriginSet = true)))
        assertEquals(listOf(PreflightItem.Home), failing(good.copy(home = null, ekfOriginSet = false)))
    }

    @Test
    fun `a prearm failure blocks for five seconds`() {
        assertEquals(listOf(PreflightItem.NoPrearm), failing(good.copy(lastPrearmMs = now - 4_000)))
        assertTrue(PreflightChecklist.evaluate(good.copy(lastPrearmMs = now - 5_000), now).ready)
    }

    @Test
    fun `unreported inputs fail and are marked unknown`() {
        val r = PreflightChecklist.evaluate(good.copy(batteryPct = null, ekf = null), now)
        assertFalse(r.ready)
        val battery = r.checks.first { it.item == PreflightItem.Battery }
        assertFalse(battery.passed)
        assertFalse(battery.known)
    }

    @Test
    fun `a stale nav source fails its check and a nearly full disk only warns`() {
        assertEquals(listOf(PreflightItem.NavSource), failing(good.copy(navChip = NavChip(NavLabel.Stale, null, NavTone.Muted))))
        val storage = { pct: Double? -> PreflightChecklist.evaluate(good.copy(storageUsedPct = pct), now).checks.first { it.item == PreflightItem.Storage } }
        assertTrue(storage(89.9).passed)
        // Graded on percent used at the spec's warning band, as the pill: a big volume with 2 GB free is 98 % used.
        assertFalse(storage(90.0).passed)
        assertFalse(storage(null).known)
        val full = PreflightChecklist.evaluate(good.copy(storageUsedPct = 98.0), now)
        assertEquals(98.0, full.storageUsedPct!!, 0.0)
        assertTrue("storage never blocks the arm", full.ready)
    }

    @Test
    fun `percent used reads the volume's total and available bytes`() {
        assertEquals(98.0, usedPct(totalBytes = 128_000_000_000L, availableBytes = 2_560_000_000L)!!, 1e-9)
        assertEquals(null, usedPct(totalBytes = 0L, availableBytes = 0L))
    }

    @Test
    fun `a controller is required only when ADOS Pilot takes the sticks`() {
        assertTrue(PreflightChecklist.evaluate(good.copy(controlMode = ControlMode.FcManual), now).ready)
        assertEquals(listOf(PreflightItem.Controller), failing(good.copy(controlMode = ControlMode.Ados)))
        assertTrue(PreflightChecklist.evaluate(good.copy(controlMode = ControlMode.Ados, controllerConnected = true), now).ready)
    }
}
