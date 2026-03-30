package com.altnautica.gcs

import com.altnautica.gcs.data.telemetry.AttitudeState
import com.altnautica.gcs.data.telemetry.BatteryState
import com.altnautica.gcs.data.telemetry.ConnectionState
import com.altnautica.gcs.data.telemetry.ConnectionStatus
import com.altnautica.gcs.data.telemetry.FlightMode
import com.altnautica.gcs.data.telemetry.PositionState
import com.altnautica.gcs.data.telemetry.TelemetryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TelemetryStoreTest {

    private lateinit var store: TelemetryStore

    @Before
    fun setup() {
        store = TelemetryStore()
    }

    @Test
    fun `update attitude emits new state`() {
        val att = AttitudeState(roll = 15f, pitch = -10f, yaw = 90f)
        store.updateAttitude(att)

        assertEquals(15f, store.attitude.value.roll, 0.001f)
        assertEquals(-10f, store.attitude.value.pitch, 0.001f)
        assertEquals(90f, store.attitude.value.yaw, 0.001f)
    }

    @Test
    fun `update position emits new state`() {
        val pos = PositionState(
            lat = 12.9716,
            lon = 77.5946,
            altMsl = 920f,
            altRel = 50f,
            heading = 270,
        )
        store.updatePosition(pos)

        assertEquals(12.9716, store.position.value.lat, 0.0001)
        assertEquals(77.5946, store.position.value.lon, 0.0001)
        assertEquals(920f, store.position.value.altMsl, 0.1f)
        assertEquals(50f, store.position.value.altRel, 0.1f)
        assertEquals(270, store.position.value.heading)
    }

    @Test
    fun `update battery emits new state`() {
        val bat = BatteryState(voltage = 22.2f, current = 12.5f, remaining = 68)
        store.updateBattery(bat)

        assertEquals(22.2f, store.battery.value.voltage, 0.01f)
        assertEquals(12.5f, store.battery.value.current, 0.01f)
        assertEquals(68, store.battery.value.remaining)
    }

    @Test
    fun `update flight mode emits new state`() {
        store.updateFlightMode(FlightMode.LOITER)
        assertEquals(FlightMode.LOITER, store.flightMode.value)

        store.updateFlightMode(FlightMode.RTL)
        assertEquals(FlightMode.RTL, store.flightMode.value)
    }

    @Test
    fun `update armed state emits new state`() {
        store.updateArmed(true)
        assertTrue(store.armed.value)

        store.updateArmed(false)
        assertFalse(store.armed.value)
    }

    @Test
    fun `home position is separate from current position`() {
        val home = PositionState(lat = 12.97, lon = 77.59, altMsl = 900f, altRel = 0f, heading = 0)
        val current = PositionState(lat = 12.98, lon = 77.60, altMsl = 950f, altRel = 50f, heading = 180)

        store.updateHomePosition(home)
        store.updatePosition(current)

        assertEquals(12.97, store.homePosition.value?.lat ?: 0.0, 0.001)
        assertEquals(12.98, store.position.value.lat, 0.001)
    }

    @Test
    fun `clear resets all to defaults`() {
        // Set some values
        store.updateAttitude(AttitudeState(roll = 45f, pitch = 30f, yaw = 180f))
        store.updateArmed(true)
        store.updateFlightMode(FlightMode.AUTO)
        store.updateHomePosition(PositionState(lat = 12.97, lon = 77.59, altMsl = 900f, altRel = 0f, heading = 0))
        store.addStatusMessage("test message")

        // Clear everything
        store.clear()

        assertEquals(0f, store.attitude.value.roll, 0.001f)
        assertEquals(0f, store.attitude.value.pitch, 0.001f)
        assertFalse(store.armed.value)
        assertNull(store.flightMode.value)
        assertNull(store.homePosition.value)
        assertEquals(ConnectionStatus.DISCONNECTED, store.connection.value.status)
        assertTrue(store.statusMessages.value.isEmpty())
    }

    @Test
    fun `status messages are capped at 50`() {
        for (i in 1..60) {
            store.addStatusMessage("message $i")
        }

        assertEquals(50, store.statusMessages.value.size)
        // Oldest messages should have been dropped
        assertEquals("message 11", store.statusMessages.value.first())
        assertEquals("message 60", store.statusMessages.value.last())
    }
}
