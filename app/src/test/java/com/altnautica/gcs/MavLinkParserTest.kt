package com.altnautica.gcs

import com.altnautica.gcs.data.mavlink.MavLinkParser
import com.altnautica.gcs.data.telemetry.TelemetryStore
import io.dronefleet.mavlink.MavlinkMessage
import io.dronefleet.mavlink.common.Attitude
import io.dronefleet.mavlink.common.BatteryStatus
import io.dronefleet.mavlink.common.GlobalPositionInt
import io.dronefleet.mavlink.minimal.Heartbeat
import io.dronefleet.mavlink.minimal.MavAutopilot
import io.dronefleet.mavlink.minimal.MavModeFlag
import io.dronefleet.mavlink.minimal.MavType
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.EnumSet

class MavLinkParserTest {

    private lateinit var store: TelemetryStore
    private lateinit var parser: MavLinkParser

    @Before
    fun setup() {
        store = TelemetryStore()
        parser = MavLinkParser(store)
    }

    @Test
    fun `parser updates attitude on Attitude message`() {
        val attitude = Attitude.builder()
            .timeBootMs(1000L)
            .roll(0.1745f)   // ~10 degrees
            .pitch(-0.0873f) // ~-5 degrees
            .yaw(1.5708f)    // ~90 degrees
            .rollspeed(0f)
            .pitchspeed(0f)
            .yawspeed(0f)
            .build()

        val msg = wrapMessage(attitude)
        parser.handleMessage(msg)

        val state = store.attitude.value
        assertEquals(10f, state.roll, 0.5f)
        assertEquals(-5f, state.pitch, 0.5f)
        assertEquals(90f, state.yaw, 0.5f)
    }

    @Test
    fun `parser updates position on GlobalPositionInt message`() {
        val pos = GlobalPositionInt.builder()
            .timeBootMs(2000L)
            .lat(128812340)       // 12.8812340
            .lon(774975680)       // 77.4975680
            .alt(150000)          // 150m MSL
            .relativeAlt(50000)   // 50m AGL
            .vx(0)
            .vy(0)
            .vz(0)
            .hdg(18000)           // 180 deg
            .build()

        val msg = wrapMessage(pos)
        parser.handleMessage(msg)

        val state = store.position.value
        assertEquals(12.881234, state.lat, 0.00001)
        assertEquals(77.497568, state.lon, 0.00001)
        assertEquals(150f, state.altMsl, 1f)
        assertEquals(50f, state.altRel, 1f)
        assertEquals(180, state.heading)
    }

    @Test
    fun `parser updates battery on BatteryStatus message`() {
        val battery = BatteryStatus.builder()
            .id(0)
            .batteryFunction(0)
            .type(0)
            .temperature(2500)
            .voltages(listOf(4200, 4180, 4190, 0, 0, 0, 0, 0, 0, 0))
            .currentBattery(1500) // 15.00 A
            .currentConsumed(500)
            .energyConsumed(100)
            .batteryRemaining(75)
            .build()

        val msg = wrapMessage(battery)
        parser.handleMessage(msg)

        val state = store.battery.value
        // 4200 + 4180 + 4190 = 12570 mV = 12.57 V
        assertEquals(12.57f, state.voltage, 0.01f)
        assertEquals(15.0f, state.current, 0.1f)
        assertEquals(75, state.remaining)
    }

    @Test
    fun `parser updates flight mode from heartbeat custom mode`() {
        val heartbeat = Heartbeat.builder()
            .type(MavType.MAV_TYPE_QUADROTOR)
            .autopilot(MavAutopilot.MAV_AUTOPILOT_ARDUPILOTMEGA)
            .baseMode(EnumSet.of(MavModeFlag.MAV_MODE_FLAG_CUSTOM_MODE_ENABLED))
            .customMode(5L)  // Loiter = 5
            .systemStatus(0)
            .mavlinkVersion(3)
            .build()

        val msg = wrapMessage(heartbeat)
        parser.handleMessage(msg)

        val mode = store.flightMode.value
        assertEquals("Loiter", mode?.label)
        assertEquals(5, mode?.modeNumber)
    }

    @Test
    fun `parser updates armed state from heartbeat base mode flags`() {
        // Armed heartbeat
        val armed = Heartbeat.builder()
            .type(MavType.MAV_TYPE_QUADROTOR)
            .autopilot(MavAutopilot.MAV_AUTOPILOT_ARDUPILOTMEGA)
            .baseMode(EnumSet.of(
                MavModeFlag.MAV_MODE_FLAG_SAFETY_ARMED,
                MavModeFlag.MAV_MODE_FLAG_CUSTOM_MODE_ENABLED,
            ))
            .customMode(0L)
            .systemStatus(0)
            .mavlinkVersion(3)
            .build()

        parser.handleMessage(wrapMessage(armed))
        assertTrue(store.armed.value)

        // Disarmed heartbeat
        val disarmed = Heartbeat.builder()
            .type(MavType.MAV_TYPE_QUADROTOR)
            .autopilot(MavAutopilot.MAV_AUTOPILOT_ARDUPILOTMEGA)
            .baseMode(EnumSet.of(MavModeFlag.MAV_MODE_FLAG_CUSTOM_MODE_ENABLED))
            .customMode(0L)
            .systemStatus(0)
            .mavlinkVersion(3)
            .build()

        parser.handleMessage(wrapMessage(disarmed))
        assertFalse(store.armed.value)
    }

    @Test
    fun `parser handles unknown message type gracefully`() {
        // A message with a payload type the parser doesn't handle.
        // We use a mock to simulate an unknown payload type.
        val unknownPayload = object {}
        val msg = mockk<MavlinkMessage<Any>>()
        every { msg.payload } returns unknownPayload

        // Should not throw
        parser.handleMessage(msg)

        // Store remains at defaults
        assertNull(store.flightMode.value)
        assertFalse(store.armed.value)
        assertEquals(0f, store.attitude.value.roll, 0.001f)
    }

    /**
     * Wraps a payload object into a MavlinkMessage using a mock.
     * The parser only reads [MavlinkMessage.payload], so mocking is safe.
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T> wrapMessage(payload: T): MavlinkMessage<T> {
        val msg = mockk<MavlinkMessage<T>>()
        every { msg.payload } returns payload
        return msg
    }
}
