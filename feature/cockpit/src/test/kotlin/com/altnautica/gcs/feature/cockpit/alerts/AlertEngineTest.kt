package com.altnautica.gcs.feature.cockpit.alerts

import com.altnautica.gcs.core.model.AlertLevel
import com.altnautica.gcs.core.model.EkfState
import com.altnautica.gcs.core.model.FenceStatus
import com.altnautica.gcs.core.model.GpsFix
import com.altnautica.gcs.core.model.GpsState
import com.altnautica.gcs.core.model.SystemHealth
import com.altnautica.gcs.core.model.VibrationState
import com.altnautica.gcs.core.data.cockpit.BatteryBands
import com.altnautica.gcs.feature.cockpit.testSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertEngineTest {
    private val now = 100_000L

    private fun inputs(
        armed: Boolean? = true,
        hbAge: Long? = 0,
        battery: Int? = 80,
        sys: SystemHealth? = null,
        ekf: EkfState? = null,
        fence: FenceStatus? = null,
        gps: GpsState? = null,
        vib: VibrationState? = null,
        frozen: Boolean = false,
        crit: ReceivedText? = null,
        prearm: ReceivedText? = null,
    ) = AlertInputs(armed, hbAge?.let { now - it }, sys, battery, BatteryBands(30, 20), ekf, fence, gps, vib, frozen, crit, prearm)

    private fun ids(i: AlertInputs) = AlertEngine().evaluate(i, now).map { it.id to it.level }

    @Test
    fun `unknown battery never alerts`() {
        assertTrue(ids(inputs(battery = null)).none { it.first == AlertId.BATT_LOW || it.first == AlertId.BATT_CRIT })
        assertTrue(ids(inputs(battery = -1)).none { it.first == AlertId.BATT_LOW || it.first == AlertId.BATT_CRIT })
    }

    @Test
    fun `battery bands raise warning then critical, at or below each band`() {
        assertEquals(listOf(AlertId.BATT_LOW to AlertLevel.Warning), ids(inputs(battery = 25)))
        assertEquals(listOf(AlertId.BATT_LOW to AlertLevel.Warning), ids(inputs(battery = 30)))
        assertEquals(listOf(AlertId.BATT_CRIT to AlertLevel.Critical), ids(inputs(battery = 20)))
        assertEquals(emptyList<Pair<String, AlertLevel>>(), ids(inputs(battery = 31)))
    }

    @Test
    fun `without an operator choice a 20 percent pack warns and is not critical, as the web`() {
        val fallback = testSpec.layout.battery!!.lowFallbackPct!!
        assertEquals(BatteryBands(lowPct = fallback, criticalPct = 10), BatteryBands.DEFAULT)
        assertEquals(AlertLevel.Warning, AlertEngine.batteryLevel(20, BatteryBands.DEFAULT))
        assertEquals(AlertLevel.Critical, AlertEngine.batteryLevel(10, BatteryBands.DEFAULT))
        assertEquals(null, AlertEngine.batteryLevel(21, BatteryBands.DEFAULT))
    }

    @Test
    fun `link stale warns and a long loss while armed is critical`() {
        assertEquals(listOf(AlertId.LINK_STALE to AlertLevel.Warning), ids(inputs(hbAge = 3_500)))
        assertEquals(listOf(AlertId.LINK_LOST to AlertLevel.Critical), ids(inputs(hbAge = 11_000)))
        // Disarmed on the bench, a lost link is only a warning.
        assertEquals(listOf(AlertId.LINK_STALE to AlertLevel.Warning), ids(inputs(armed = false, hbAge = 11_000)))
    }

    @Test
    fun `failsafe sources are critical`() {
        val sys = SystemHealth(systemStatus = 5, sensorsPresent = 0, sensorsEnabled = 0, sensorsHealthy = 0, tMs = now)
        assertEquals(AlertLevel.Critical, AlertEngine().evaluate(inputs(sys = sys), now).single().level)
        val ekf = EkfState(0, velVar = 0.9f, posHorizVar = 0.85f, posVertVar = 0.1f, compassVar = 0.1f, tMs = now)
        assertEquals(listOf(AlertId.EKF_FAILSAFE to AlertLevel.Critical), ids(inputs(ekf = ekf)))
        assertEquals(listOf(AlertId.FENCE_BREACH to AlertLevel.Critical), ids(inputs(fence = FenceStatus(1, 1, now))))
        assertEquals(listOf(AlertId.STATUS_TEXT to AlertLevel.Critical), ids(inputs(crit = ReceivedText("Battery failsafe", now - 1_000))))
    }

    @Test
    fun `warnings and advisories follow their sources`() {
        val gps = GpsState(GpsFix.Fix2D, 5, 2f, now)
        assertEquals(listOf(AlertId.GPS_NO_3D to AlertLevel.Warning), ids(inputs(gps = gps)))
        assertEquals(listOf(AlertId.VIDEO_FROZEN to AlertLevel.Warning), ids(inputs(frozen = true)))
        val rc = SystemHealth(0, sensorsPresent = 1L shl 16, sensorsEnabled = 1L shl 16, sensorsHealthy = 0, tMs = now)
        assertEquals(listOf(AlertId.RC_FAILSAFE to AlertLevel.Warning), ids(inputs(sys = rc)))
        assertEquals(listOf(AlertId.PREARM to AlertLevel.Advisory), ids(inputs(armed = false, prearm = ReceivedText("GPS not healthy", now))))
        assertEquals(listOf(AlertId.HIGH_VIBRATION to AlertLevel.Advisory), ids(inputs(vib = VibrationState(35f, 1f, 1f, 0, now))))
    }

    @Test
    fun `alerts are ordered by severity and keep their first time`() {
        val engine = AlertEngine()
        val first = engine.evaluate(inputs(battery = 25, frozen = true, hbAge = 11_000), now)
        assertEquals(listOf(AlertLevel.Critical, AlertLevel.Warning, AlertLevel.Warning), first.map { it.level })
        val later = engine.evaluate(inputs(battery = 25, frozen = true, hbAge = 11_000), now + 500)
        assertEquals(first.map { it.sinceMs }, later.map { it.sinceMs })
    }

    @Test
    fun `failsafe text detection ignores cleared failsafes`() {
        assertTrue(AlertEngine.isFailsafeAnnouncement(4, "Radio Failsafe"))
        assertTrue(!AlertEngine.isFailsafeAnnouncement(4, "Radio Failsafe Cleared"))
        assertTrue(!AlertEngine.isFailsafeAnnouncement(6, "Battery failsafe"))
    }
}
