package com.altnautica.gcs.core.link.vehicle

import com.altnautica.gcs.core.link.mavlink.ElapsedClock
import com.altnautica.gcs.core.link.testing.ScriptedLink
import com.altnautica.gcs.core.mavlink.messages.GpsGlobalOrigin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `GPS_GLOBAL_ORIGIN` keeps where the flight controller's local frame sits, not only that it is anchored. */
class EkfOriginTelemetryTest {
    @Test
    fun `the EKF origin's position is kept with the anchored flag`() = runTest {
        val link = ScriptedLink()
        val telemetry = MavlinkVehicleTelemetry(backgroundScope, link, ElapsedClock { testScheduler.currentTime })
        runCurrent()
        assertFalse(telemetry.ekfOriginSet.value)
        assertNull(telemetry.ekfOrigin.value)

        link.reply(GpsGlobalOrigin(latitude = 470_123_456, longitude = 80_654_321, altitude = 395_500))
        runCurrent()

        assertTrue(telemetry.ekfOriginSet.value)
        val origin = telemetry.ekfOrigin.value
        assertNotNull(origin)
        origin!!
        assertEquals(47.0123456, origin.latDeg, 1e-9)
        assertEquals(8.0654321, origin.lonDeg, 1e-9)
        assertEquals(395.5f, origin.altMslM, 1e-3f)
    }
}
