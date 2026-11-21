package com.altnautica.gcs.core.link.follow

import com.altnautica.gcs.core.link.mavlink.ElapsedClock
import com.altnautica.gcs.core.link.testing.ScriptedLink
import com.altnautica.gcs.core.mavlink.MavlinkFrameParser
import com.altnautica.gcs.core.mavlink.MavlinkFrameWriter
import com.altnautica.gcs.core.mavlink.messages.FollowTarget
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal fun beaconFix(atMs: Long, accM: Float = 4f) =
    BeaconFix(47.397742, 8.545594, 488.5, accM, 6f, 1f, -0.5f, atMs)

class FollowBeaconStreamerTest {

    @Test
    fun a_fix_encodes_as_follow_target_and_survives_the_wire() {
        val m = FollowBeaconCodec.encode(beaconFix(12_345))
        assertEquals(FollowTarget.ID, m.msgId)
        assertEquals(144, m.msgId)
        assertEquals(12_345L, m.timestamp)
        assertEquals(473_977_420, m.lat)
        assertEquals(85_455_940, m.lon)
        assertEquals(488.5f, m.alt)
        assertEquals(FollowBeaconCodec.CAP_POS or FollowBeaconCodec.CAP_VEL, m.estCapabilities)
        assertArrayEquals(floatArrayOf(1f, -0.5f, 0f), m.vel, 0f)
        assertArrayEquals(floatArrayOf(4f, 6f, 0f), m.positionCov, 0f)
        assertArrayEquals(FloatArray(3), m.acc, 0f)

        val frame = MavlinkFrameParser().parse(MavlinkFrameWriter().encode(m)).single()
        assertEquals(m, frame.decode())
    }

    @Test
    fun unknown_altitude_velocity_and_vertical_accuracy_are_sent_as_unknown() {
        val m = FollowBeaconCodec.encode(BeaconFix(47.0, 8.0, null, 12f, null, null, null, 1))
        assertTrue(m.alt.isNaN())
        assertEquals(FollowBeaconCodec.CAP_POS, m.estCapabilities)
        assertArrayEquals(FloatArray(3), m.vel, 0f)
        assertArrayEquals(floatArrayOf(12f, 0f, 0f), m.positionCov, 0f)
    }

    @Test
    fun ground_speed_and_bearing_become_north_east_velocity() {
        val east = BeaconFix.of(0.0, 0.0, null, 3f, null, speedMps = 2f, bearingDeg = 90f, fixElapsedMs = 0)
        assertEquals(0f, east.velNorthMps!!, 1e-5f)
        assertEquals(2f, east.velEastMps!!, 1e-5f)
        val still = BeaconFix.of(0.0, 0.0, null, 3f, null, speedMps = 0.05f, bearingDeg = null, fixElapsedMs = 0)
        assertEquals(0f to 0f, still.velNorthMps to still.velEastMps)
        val unknown = BeaconFix.of(0.0, 0.0, null, 3f, null, speedMps = 1.5f, bearingDeg = null, fixElapsedMs = 0)
        assertNull(unknown.velNorthMps)
        val noSpeed = BeaconFix.of(0.0, 0.0, null, 3f, null, speedMps = null, bearingDeg = 10f, fixElapsedMs = 0)
        assertNull(noSpeed.velEastMps)
    }

    private fun TestScope.streamer(link: ScriptedLink) =
        MavlinkFollowBeaconStreamer(backgroundScope, link, ElapsedClock { testScheduler.currentTime })

    @Test
    fun a_20_hz_location_stream_goes_out_at_5_hz() = runTest {
        val link = ScriptedLink()
        val fixes = MutableStateFlow<BeaconFix?>(null)
        backgroundScope.launch {
            while (true) {
                fixes.value = beaconFix(testScheduler.currentTime)
                delay(50)
            }
        }
        val s = streamer(link)
        s.start(fixes)
        advanceTimeBy(999)
        val sent = link.sent.map { it as FollowTarget }
        assertEquals(5, sent.size)
        // One send per 200 ms tick, each carrying the newest 20 Hz fix.
        assertTrue(sent.zipWithNext { a, b -> b.timestamp - a.timestamp }.all { it in 150L..200L })
        assertEquals(sent.last().timestamp, s.lastSent.value?.fixElapsedMs)

        s.stop()
        advanceTimeBy(2_000)
        assertEquals("stop halts the stream", 5, link.sent.size)
        assertNull(s.lastSent.value)
    }

    @Test
    fun a_fix_is_sent_once_and_never_when_stale() = runTest {
        val link = ScriptedLink()
        val fixes = MutableStateFlow<BeaconFix?>(beaconFix(0))
        streamer(link).start(fixes)
        advanceTimeBy(1_000)
        assertEquals("one fix, one message", 1, link.sent.size)

        advanceTimeBy(4_000)
        // Newer than the last one sent, but 2.5 s old by now.
        fixes.value = beaconFix(testScheduler.currentTime - 2_500)
        advanceTimeBy(1_000)
        assertEquals(1, link.sent.size)
        fixes.value = beaconFix(testScheduler.currentTime - 1_500)
        advanceTimeBy(250)
        runCurrent()
        assertEquals(2, link.sent.size)
    }
}
