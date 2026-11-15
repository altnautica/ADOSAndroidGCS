package com.altnautica.gcs.feature.cockpit.preflight

import com.altnautica.gcs.core.model.ReadinessItem
import com.altnautica.gcs.core.model.ReadinessSeverity
import com.altnautica.gcs.feature.cockpit.testSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryFailsafeFixTest {
    private val strings = testSpec.setupStrings

    private fun item(key: String, ok: Boolean = false, id: String = BatteryFailsafeFix.ITEM_ID) =
        ReadinessItem(id, ok, ReadinessSeverity.Warn, strings.format(key))

    @Test
    fun `the spec carries the battery failsafe texts and the fix label`() {
        assertEquals("Fix in Aircraft setup", strings.format(BatteryFailsafeFix.LABEL_KEY))
        assertEquals("Battery failsafe does nothing: set low = SmartRTL or Land, critical = Land", strings.format("readiness.battery_failsafe.none"))
        assertEquals("No battery monitor: set BATT_MONITOR so the battery failsafe can act", strings.format("readiness.battery_failsafe.no_monitor"))
        assertEquals("Battery failsafe", strings.format("readiness.item.battery_failsafe"))
    }

    @Test
    fun `the fix shows on an action left at nothing while disarmed`() {
        for (key in listOf("none", "none_confined", "low_none", "low_none_confined", "low_none_plane", "critical_none", "confined_rtl", "differs", "differs_confined", "differs_low")) {
            assertTrue(key, BatteryFailsafeFix.offered(item("readiness.battery_failsafe.$key"), armed = false, strings))
        }
    }

    @Test
    fun `no fix while armed or with the arm state unknown`() {
        val off = item("readiness.battery_failsafe.none")
        assertFalse(BatteryFailsafeFix.offered(off, armed = true, strings))
        assertFalse(BatteryFailsafeFix.offered(off, armed = null, strings))
    }

    @Test
    fun `no fix for unset levels, no monitor, an unread state, a set failsafe or another item`() {
        for (key in listOf("levels_unset", "no_monitor", "unread")) {
            assertFalse(key, BatteryFailsafeFix.offered(item("readiness.battery_failsafe.$key"), armed = false, strings))
        }
        assertFalse(BatteryFailsafeFix.offered(item("readiness.battery_failsafe.none", ok = true), armed = false, strings))
        assertFalse(BatteryFailsafeFix.offered(item("readiness.battery_failsafe.none", id = "position"), armed = false, strings))
    }
}
