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
        storageFreeBytes = 4_000_000_000L,
    )

    private fun failing(i: PreflightInputs) = PreflightChecklist.evaluate(i, now).failing.map { it.item }

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
        assertEquals(listOf(PreflightItem.Ekf), failing(good.copy(ekf = good.ekf!!.copy(compassVar = 0.9f))))
        assertEquals(listOf(PreflightItem.Ekf), failing(good.copy(ekf = good.ekf!!.copy(flags = 1 or (1 shl 10)))))
        assertEquals(listOf(PreflightItem.Home), failing(good.copy(home = null)))
        assertEquals(listOf(PreflightItem.Link), failing(good.copy(linkFresh = false)))
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
    fun `a stale nav source fails its check and a full disk only warns`() {
        assertEquals(listOf(PreflightItem.NavSource), failing(good.copy(navChip = NavChip(NavLabel.Stale, null, NavTone.Muted))))
        val lowDisk = PreflightChecklist.evaluate(good.copy(storageFreeBytes = 10_000L), now)
        assertFalse(lowDisk.checks.first { it.item == PreflightItem.Storage }.passed)
        assertTrue("storage never blocks the arm", lowDisk.ready)
    }

    @Test
    fun `a controller is required only when ADOS Pilot takes the sticks`() {
        assertTrue(PreflightChecklist.evaluate(good.copy(controlMode = ControlMode.FcManual), now).ready)
        assertEquals(listOf(PreflightItem.Controller), failing(good.copy(controlMode = ControlMode.Ados)))
        assertTrue(PreflightChecklist.evaluate(good.copy(controlMode = ControlMode.Ados, controllerConnected = true), now).ready)
    }
}
