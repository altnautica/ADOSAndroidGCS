package com.altnautica.gcs.feature.cockpit

import androidx.lifecycle.viewModelScope
import com.altnautica.gcs.core.agent.http.AgentError
import com.altnautica.gcs.core.agent.http.AgentResult
import com.altnautica.gcs.core.agent.nav.AutonomyFeedApi
import com.altnautica.gcs.core.agent.node.LocalNodeApis
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.PilotState
import com.altnautica.gcs.core.model.RcReceiver
import com.altnautica.gcs.core.skills.MonotonicClock
import com.altnautica.gcs.core.skills.builtin.BuiltinIds
import com.altnautica.gcs.core.skills.cockpit.StopControls
import com.altnautica.gcs.core.skills.hud.NoRcText
import com.altnautica.gcs.core.skills.hud.SpecText
import com.altnautica.gcs.feature.cockpit.pilot.CockpitNoRc
import com.altnautica.gcs.feature.cockpit.pilot.CockpitPilot
import com.altnautica.gcs.feature.cockpit.pilot.PilotChannel
import com.altnautica.gcs.feature.cockpit.pilot.Ticker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Flying with no RC transmitter: the link notification's Pause, Land and Return run in the
 * open cockpit and reach the pilot, and the heartbeat follows whether a stop control is
 * reachable (the cockpit resumed, or the notification) on the main-looper tick only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NoRcControlsTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** A pilot flying with no RC declared; every client frame lands in [sent], every action is answered ok. */
    private class NoRcPilot(private val scope: TestScope) : AutonomyFeedApi {
        val sent = mutableListOf<JsonObject>()
        private val data = """{"owner":"skill","control_mode":"ados","armed":true,"landed":false,""" +
            """"fc_mode":4,"fc_mode_name":"GUIDED","rc_receiver":"absent","link_loss_action":"rth","link_loss_s":10}"""

        fun actions() = sent.filter { it.op() == "action" }.map { it.getValue("name").jsonPrimitive.content }

        fun ops() = sent.map { it.op() }

        override fun detections() = emptyFlow<String>()
        override fun navStream(outbound: Flow<String>) = flow<String> { awaitCancellation() }

        override fun pilotStream(outgoing: Flow<String>) = callbackFlow {
            val reader = launch {
                outgoing.collect { text ->
                    val o = Json.parseToJsonElement(text).jsonObject
                    sent += o
                    if (o.op() == "action") {
                        val name = o.getValue("name").jsonPrimitive.content
                        trySend("""{"kind":"action_result","name":"$name","id":"${o["id"]?.jsonPrimitive?.content}","ok":true}""")
                    }
                }
            }
            val frames = launch {
                while (true) {
                    trySend("""{"kind":"pilot","ts_ms":${scope.testScheduler.currentTime},"data":$data}""")
                    kotlinx.coroutines.delay(100)
                }
            }
            awaitClose {
                reader.cancel()
                frames.cancel()
            }
        }

        override suspend fun navStatus(view: String?): AgentResult<JsonObject>? = null
        private val absent = AgentResult.Err(AgentError.FeatureAbsent)
        override suspend fun visionDemand(body: JsonObject) = absent
        override suspend fun visionStatus() = absent
        override suspend fun setDetector(modelId: String) = absent
        override suspend fun mapLive(since: Long) = absent

        val apis: Set<LocalNodeApis> = setOf(
            object : LocalNodeApis {
                override fun handles(id: DeviceId) = false
                override fun capture(id: DeviceId) = null
                override fun sensors(id: DeviceId) = null
                override fun capturePreview(id: DeviceId, camera: String?) = null
                override fun autonomy(id: DeviceId) = this@NoRcPilot
            },
        )

        private fun JsonObject.op() = getValue("op").jsonPrimitive.content
    }

    /** A tick the test drives by hand, as the main looper would. */
    private class HandTicker : Ticker {
        var tick: (() -> Unit)? = null
        override fun start(tick: () -> Unit) {
            this.tick = tick
        }

        override fun stop() {
            tick = null
        }
    }

    private fun TestScope.flying(pilot: NoRcPilot, stops: StopControls): CockpitViewModel {
        val vehicle = FakeVehicle()
        vehicle.heartbeatAt(testScheduler.currentTime, armed = true, mode = "GUIDED")
        val vm = testCockpitVm(vehicle, pilot.apis, stopControls = stops)
        advanceTimeBy(600)
        runCurrent()
        return vm
    }

    @Test
    fun `the notification's Pause, Land and Return send the pilot's pause, land and rth from the open cockpit`() = runTest(dispatcher) {
        val pilot = NoRcPilot(this)
        val stops = StopControls(FakeNotificationControls())
        val vm = flying(pilot, stops)
        assertTrue("the open cockpit offers the notification's controls", stops.available.value)

        assertTrue(stops.run(BuiltinIds.PAUSE))
        advanceTimeBy(100)
        runCurrent()
        assertEquals(listOf("pause"), pilot.actions())

        stops.run(BuiltinIds.LAND)
        advanceTimeBy(100)
        runCurrent()
        stops.run(BuiltinIds.RTH)
        advanceTimeBy(100)
        runCurrent()
        assertEquals(listOf("pause", "land", "rth"), pilot.actions())

        vm.viewModelScope.cancel()
        runCurrent()
        assertFalse("a closed cockpit no longer offers them", stops.available.value)
    }

    @Test
    fun `with no RC the heartbeat flows only from the main tick while the cockpit is resumed or the notification reachable`() = runTest(dispatcher) {
        val pilot = NoRcPilot(this)
        val notification = FakeNotificationControls()
        val ticker = HandTicker()
        val owner = CoroutineScope(dispatcher + Job())
        val cockpit = CockpitPilot(
            owner, MonotonicClock { testScheduler.currentTime }, MutableStateFlow(DeviceId("drone-1")), { pilot },
            MutableStateFlow<PilotState?>(null), flowOf(null), notificationReachable = notification::reachable, ticker = { ticker },
        )
        fun tickFor(ms: Long) {
            repeat((ms / PilotChannel.TICK_MS).toInt()) {
                advanceTimeBy(PilotChannel.TICK_MS)
                runCurrent()
                ticker.tick?.invoke()
            }
        }
        runCurrent()
        tickFor(3_000)
        assertEquals("nothing reachable: no hb", 0, pilot.ops().count { it == "hb" })

        cockpit.resumed = true
        tickFor(3_000)
        val resumed = pilot.ops().count { it == "hb" }
        assertTrue("a resumed cockpit: hb at 1 Hz, $resumed", resumed in 2..4)

        cockpit.resumed = false
        notification.canReach = true
        tickFor(3_000)
        assertTrue("the notification reachable: hb keeps flowing", pilot.ops().count { it == "hb" } > resumed)

        notification.canReach = false
        val before = pilot.ops().count { it == "hb" }
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals("the coroutine watchdog never sends hb with no RC", before, pilot.ops().count { it == "hb" })
        owner.cancel()
    }

    @Test
    fun `the posture turns the on-screen sticks on and warns before arming when the lock screen hides the notification`() = runTest(dispatcher) {
        val state = MutableStateFlow<PilotState?>(null)
        val notification = FakeNotificationControls(hidden = true)
        val owner = CoroutineScope(dispatcher + Job())
        val noRc = CockpitNoRc(owner, StopControls(notification), SpecText(testSpec.setupStrings), state, MutableStateFlow(false)) {}
        runCurrent()
        assertFalse(noRc.sticksEnabled.value)
        assertNull(noRc.lockScreenItem(null))

        val absent = pilotState(com.altnautica.gcs.core.model.PilotOwner.Idle).copy(rcReceiver = RcReceiver.Absent, linkLossAction = "rth")
        state.value = absent
        runCurrent()
        assertTrue(noRc.active.value)
        assertTrue("no RC: the on-screen sticks show", noRc.sticksEnabled.value)
        val item = noRc.lockScreenItem(absent)!!
        assertEquals(NoRcText.LOCKSCREEN_ITEM, item.id)
        assertFalse(item.ok)
        assertEquals(com.altnautica.gcs.core.model.ReadinessSeverity.Warn, item.severity)

        notification.hidden = false
        assertNull("the lock screen shows it: no warning", noRc.lockScreenItem(absent))
        owner.cancel()
    }
}
