package com.altnautica.gcs.feature.cockpit.band

import com.altnautica.gcs.core.agent.model.BatteryHealth
import com.altnautica.gcs.feature.cockpit.chrome.StatusSeverity
import com.altnautica.gcs.feature.cockpit.chrome.batteryUi
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryReserveTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun health(text: String) = json.decodeFromString(BatteryHealth.serializer(), text)

    @Test
    fun `the gauge marks the flight controller's return reserve and the time to it`() {
        val reserve = BatteryReserve.from(
            health("""{"enabled":true,"stale":false,"thresholds":{"reserve_percent":22},"packs":[{"id":0,"prediction":{"state":"discharging","eta_s":340}}]}"""),
        )
        assertEquals(BatteryReserve(22, 340), reserve)
        val ui = batteryUi(pct = 64, reserve = reserve, flightControllerTimeS = 900, severity = StatusSeverity.Ok)
        assertEquals(22, ui.reservePct)
        assertEquals("the time runs to the reserve, not to empty", 340, ui.timeLeftS)
        assertTrue(ui.toReserve)
    }

    @Test
    fun `without a battery engine there is no reserve mark and the time is labelled to empty`() {
        assertNull(BatteryReserve.from(null))
        assertNull(BatteryReserve.from(health("""{"enabled":false,"thresholds":{"reserve_percent":22}}""")))
        assertNull("a stale engine is not trusted", BatteryReserve.from(health("""{"enabled":true,"stale":true,"thresholds":{"reserve_percent":22}}""")))
        assertNull("no reserve threshold, no mark", BatteryReserve.from(health("""{"enabled":true,"thresholds":{}}""")))
        val ui = batteryUi(pct = 64, reserve = null, flightControllerTimeS = 900, severity = StatusSeverity.Ok)
        assertNull("the app's low-battery warning is never drawn as a reserve", ui.reservePct)
        assertEquals(900, ui.timeLeftS)
        assertFalse("the FC estimate is time to empty", ui.toReserve)
    }

    @Test
    fun `an idle pack keeps the reserve mark with no time to it`() {
        val reserve = BatteryReserve.from(health("""{"enabled":true,"thresholds":{"reserve_percent":25},"packs":[{"id":0,"prediction":{"state":"idle"}}]}"""))
        assertEquals(BatteryReserve(25, null), reserve)
        val ui = batteryUi(pct = 90, reserve = reserve, flightControllerTimeS = null, severity = StatusSeverity.Ok)
        assertEquals(25, ui.reservePct)
        assertNull(ui.timeLeftS)
    }
}
