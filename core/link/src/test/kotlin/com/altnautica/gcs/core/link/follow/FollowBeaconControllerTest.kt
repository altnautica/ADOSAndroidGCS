package com.altnautica.gcs.core.link.follow

import com.altnautica.gcs.core.data.follow.FollowBeaconSettingsRepository
import com.altnautica.gcs.core.link.fleet.MemoryPreferences
import com.altnautica.gcs.core.link.mavlink.ElapsedClock
import com.altnautica.gcs.core.link.services.VehicleServices
import com.altnautica.gcs.core.link.testing.ScriptedLink
import com.altnautica.gcs.core.model.BlockReason
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.Reach
import com.altnautica.gcs.core.model.Vehicle
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowBeaconControllerTest {

    private class FakePhone(private val scope: TestScope) : PhonePosition {
        var granted = true
        var withdrawn = false
        val emitted = MutableSharedFlow<BeaconFix>(extraBufferCapacity = 16)
        override val foreground = MutableStateFlow(true)
        override fun hasPermission(): Boolean = granted
        override fun nowMs(): Long = scope.testScheduler.currentTime
        override fun fixes(): Flow<BeaconFix> = flow {
            if (withdrawn) throw SecurityException("withdrawn")
            emitted.collect { emit(it) }
        }
    }

    /** A router that publishes every fix the link sent, while [accepts]. */
    private class FakeRouter(private val link: ScriptedLink) : RouterBeaconStatus {
        var accepts = true
        private var count = 0L
        override suspend fun published(vehicle: DeviceId, reach: Reach): Long? {
            if (accepts) count = link.sent.size.toLong()
            return count
        }
    }

    private class Rig(private val scope: TestScope) {
        val phone = FakePhone(scope)
        val link = ScriptedLink()
        val router = FakeRouter(link)
        val reach = MutableStateFlow<Reach>(Reach.Lan)
        val follow = MutableStateFlow(true)
        val settings = FollowBeaconSettingsRepository(MemoryPreferences())
        private val streamer = MavlinkFollowBeaconStreamer(
            scope.backgroundScope, link, ElapsedClock { scope.testScheduler.currentTime },
        )
        val drone1 = services("drone-1")
        val services = MutableStateFlow<VehicleServices?>(drone1)
        val controller = FollowBeaconController(scope.backgroundScope, phone, settings, router, services, follow)
        val share: StateFlow<BeaconShare> get() = controller.share

        /** Services of vehicle [id] on this rig's link and reach. */
        fun services(id: String): VehicleServices {
            val vehicle = mockk<Vehicle> {
                every { this@mockk.id } returns DeviceId(id)
                every { reach } returns this@Rig.reach
            }
            return mockk<VehicleServices> {
                every { this@mockk.vehicle } returns vehicle
                every { followBeacon } returns streamer
            }
        }

        init {
            // The phone reports a fresh fix every 200 ms.
            scope.backgroundScope.launch {
                while (true) {
                    phone.emitted.emit(beaconFix(scope.testScheduler.currentTime, accM = 3.6f))
                    delay(200)
                }
            }
        }
    }

    private fun TestScope.sharing(rig: Rig) {
        runCurrent()
        rig.controller.setEnabled(true)
        advanceTimeBy(2_500)
        runCurrent()
        assertEquals(BeaconShare.Sharing(3.6f), rig.share.value)
    }

    /** Messages sent over the next [ms] of virtual time. */
    private fun TestScope.sentDuring(rig: Rig, ms: Long): Int {
        val before = rig.link.sent.size
        advanceTimeBy(ms)
        runCurrent()
        return rig.link.sent.size - before
    }

    @Test
    fun off_by_default_and_silent() = runTest {
        val rig = Rig(this)
        assertEquals(0, sentDuring(rig, 2_000))
        assertEquals(BeaconShare.Off, rig.share.value)
        assertTrue(rig.controller.available.value)
    }

    @Test
    fun on_it_shares_at_5_hz_and_off_it_stops() = runTest {
        val rig = Rig(this)
        sharing(rig)
        assertEquals(5, sentDuring(rig, 1_000))
        rig.controller.setEnabled(false)
        runCurrent()
        assertEquals(BeaconShare.Off, rig.share.value)
        assertEquals(0, sentDuring(rig, 2_000))
    }

    @Test
    fun the_choice_persists_for_that_vehicle_only() = runTest {
        val rig = Rig(this)
        runCurrent()
        rig.controller.setEnabled(true)
        runCurrent()
        val again = FollowBeaconController(backgroundScope, rig.phone, rig.settings, rig.router, rig.services, rig.follow)
        advanceTimeBy(2_500)
        runCurrent()
        assertTrue(again.share.value is BeaconShare.Sharing)

        // Another vehicle has not been agreed to: nothing goes to it.
        rig.services.value = rig.services("drone-2")
        runCurrent()
        assertEquals(BeaconShare.Off, rig.share.value)
        assertEquals(0, sentDuring(rig, 2_000))
        rig.services.value = rig.drone1
        assertTrue(sentDuring(rig, 1_000) > 0)
    }

    @Test
    fun sharing_is_shown_only_while_the_vehicle_publishes_it() = runTest {
        val rig = Rig(this)
        sharing(rig)
        rig.router.accepts = false
        advanceTimeBy(4_500)
        runCurrent()
        assertEquals(BeaconShare.Blocked(ShareBlock.NotConfirmed), rig.share.value)
        rig.router.accepts = true
        advanceTimeBy(2_500)
        runCurrent()
        assertEquals(BeaconShare.Sharing(3.6f), rig.share.value)
    }

    @Test
    fun a_flight_controller_on_the_phone_is_never_offered_it() = runTest {
        val rig = Rig(this)
        rig.reach.value = Reach.DirectFc
        runCurrent()
        assertTrue(!rig.controller.available.value)
        rig.controller.setEnabled(true)
        assertEquals(0, sentDuring(rig, 2_000))
        assertEquals(BeaconShare.Off, rig.share.value)
    }

    @Test
    fun a_disconnect_or_a_lost_link_stops_it() = runTest {
        val rig = Rig(this)
        sharing(rig)
        rig.reach.value = Reach.Blocked(BlockReason.Offline)
        runCurrent()
        // No vehicle, so no consent to read: off, and the chip is not offered.
        assertEquals(BeaconShare.Off, rig.share.value)
        assertEquals(0, sentDuring(rig, 1_000))
        assertTrue(!rig.controller.available.value)

        rig.reach.value = Reach.Lan
        assertTrue(sentDuring(rig, 1_000) > 0)
        rig.services.value = null
        runCurrent()
        assertEquals(BeaconShare.Off, rig.share.value)
        assertEquals(0, sentDuring(rig, 1_000))
    }

    @Test
    fun without_follow_me_it_does_not_share() = runTest {
        val rig = Rig(this)
        sharing(rig)
        rig.follow.value = false
        runCurrent()
        assertEquals(BeaconShare.Blocked(ShareBlock.NoFollow), rig.share.value)
        assertEquals(0, sentDuring(rig, 1_000))
    }

    @Test
    fun the_background_grace_runs_out_after_30_s_and_the_front_resumes() = runTest {
        val rig = Rig(this)
        sharing(rig)
        rig.phone.foreground.value = false
        assertTrue("still sharing inside the grace", sentDuring(rig, 29_000) in 140..146)
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(BeaconShare.Blocked(ShareBlock.Background), rig.share.value)
        assertEquals(0, sentDuring(rig, 5_000))

        rig.phone.foreground.value = true
        assertTrue(sentDuring(rig, 2_500) > 0)
        assertTrue(rig.share.value is BeaconShare.Sharing)
    }

    @Test
    fun the_grace_is_configurable() = runTest {
        val rig = Rig(this)
        rig.settings.setBackgroundGraceS(5)
        sharing(rig)
        rig.phone.foreground.value = false
        advanceTimeBy(5_500)
        runCurrent()
        assertEquals(BeaconShare.Blocked(ShareBlock.Background), rig.share.value)
    }

    @Test
    fun a_missing_or_withdrawn_permission_stops_it() = runTest {
        val rig = Rig(this)
        rig.phone.granted = false
        runCurrent()
        rig.controller.setEnabled(true)
        runCurrent()
        assertEquals(BeaconShare.Blocked(ShareBlock.NoPermission), rig.share.value)
        assertEquals(0, sentDuring(rig, 1_000))

        rig.phone.granted = true
        rig.controller.permissionChanged()
        assertTrue(sentDuring(rig, 1_000) > 0)

        // Withdrawn in system settings while the app was away: back in front, the beacon stops.
        rig.phone.foreground.value = false
        runCurrent()
        rig.phone.granted = false
        rig.phone.foreground.value = true
        runCurrent()
        assertEquals(BeaconShare.Blocked(ShareBlock.NoPermission), rig.share.value)
        assertEquals(0, sentDuring(rig, 1_000))
    }

    @Test
    fun a_location_stream_refused_for_permission_stops_it() = runTest {
        val rig = Rig(this)
        rig.phone.withdrawn = true
        runCurrent()
        rig.controller.setEnabled(true)
        runCurrent()
        assertEquals(BeaconShare.Blocked(ShareBlock.NoPermission), rig.share.value)
        assertEquals(0, sentDuring(rig, 1_000))
    }
}
