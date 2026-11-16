package com.altnautica.gcs.core.extensions.bridge

import com.altnautica.gcs.core.extensions.FakeExtensionApi
import com.altnautica.gcs.core.extensions.FakeNodeSource
import com.altnautica.gcs.core.extensions.NODE
import com.altnautica.gcs.core.extensions.data.ExtensionStateFeed
import com.altnautica.gcs.core.extensions.data.PerceptionFeed
import com.altnautica.gcs.core.extensions.marks.CockpitMarksStore
import com.altnautica.gcs.core.extensions.mission.ExtensionMissionPort
import com.altnautica.gcs.core.extensions.mission.MissionApproval
import com.altnautica.gcs.core.extensions.repository
import com.altnautica.gcs.core.extensions.summary
import com.altnautica.gcs.core.model.Attitude
import com.altnautica.gcs.core.model.CommandResult
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.FlightMode
import com.altnautica.gcs.core.model.HeartbeatState
import com.altnautica.gcs.core.model.Reach
import com.altnautica.gcs.core.model.Vehicle
import com.altnautica.gcs.core.model.VehicleCommand
import com.altnautica.gcs.core.link.mission.PilotPlanView
import com.altnautica.gcs.core.link.mission.VehiclePilotSource
import com.altnautica.gcs.core.model.PilotAction
import com.altnautica.gcs.core.model.PilotActionResult
import com.altnautica.gcs.core.model.PlaceClass
import com.altnautica.gcs.core.model.VehicleControl
import com.altnautica.gcs.core.model.VehicleProvider
import com.altnautica.gcs.core.model.VehicleTelemetry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionHandlersTest {

    private val attitude = MutableStateFlow<Attitude?>(null)
    private val heartbeat = MutableStateFlow<HeartbeatState?>(HeartbeatState(true, FlightMode(4, "GUIDED"), 2, 3, 0))
    private val commands = mutableListOf<VehicleCommand>()
    private var runResult: CommandResult = CommandResult.Accepted
    private val notifier = ExtensionNotifier()
    private val telemetry = mockk<VehicleTelemetry>(relaxed = true).also {
        every { it.attitude } returns attitude
        every { it.heartbeat } returns heartbeat
    }
    private val vehicle = mockk<Vehicle>().also { v ->
        every { v.telemetry } returns telemetry
        every { v.control } returns object : VehicleControl {
            override val availableModes = MutableStateFlow(emptyList<FlightMode>())
            override suspend fun run(command: VehicleCommand): CommandResult {
                commands += command
                return runResult
            }
        }
    }
    private val api = FakeExtensionApi()
    private val confirm = ConfirmBroker()
    private val bus = ExtensionEventBus()

    /** The vehicle's onboard pilot: present or not, and the actions sent to it. */
    private var pilotPresent = false
    private var pilotAgeMs: Long? = 0
    private val pilotActions = mutableListOf<PilotAction>()
    private val pilots = object : VehiclePilotSource {
        override fun observe(id: DeviceId) = flowOf(PilotPlanView(ageMs = pilotAgeMs, present = pilotPresent))
        override suspend fun read(id: DeviceId) = PilotPlanView(ageMs = pilotAgeMs, present = pilotPresent)
        override suspend fun setPlaceClass(id: DeviceId, placeClass: PlaceClass) = PilotActionResult.Unavailable
        override suspend fun action(id: DeviceId, action: PilotAction): PilotActionResult {
            pilotActions += action
            return PilotActionResult.Accepted
        }
    }

    private fun TestScope.factory(reaches: List<Reach> = listOf(Reach.Lan)): ExtensionHandlersFactory {
        val repo = repository(api, FakeNodeSource(listOf(summary(reaches))), backgroundScope)
        val mission = mockk<ExtensionMissionPort>()
        coEvery { mission.read(any()) } returns JsonObject(emptyMap())
        return ExtensionHandlersFactory(
            vehicles = object : VehicleProvider {
                override fun vehicle(id: DeviceId) = flowOf(if (id == NODE) vehicle else null)
            },
            repository = repo,
            stateFeed = ExtensionStateFeed(repo, bus, backgroundScope),
            perception = PerceptionFeed(repo, backgroundScope),
            bus = bus,
            marks = CockpitMarksStore(),
            confirm = confirm,
            notifier = notifier,
            mission = mission,
            pilots = pilots,
            appScope = backgroundScope,
        )
    }

    private class Recorder {
        val events = mutableListOf<Pair<String, JsonElement?>>()
        fun scope(cap: String?) = HandlerScope("com.example.ext", cap) { m, _, a -> events += m to a }
    }

    private suspend fun MountHandlers.call(method: String, args: String, rec: Recorder = Recorder(), cap: String? = null): JsonElement? =
        handlers.getValue(method).invoke(rec.scope(cap), Json.parseToJsonElement(args))

    @Test
    fun commandSendNeedsOperatorApproval() = runTest {
        val h = factory().create("com.example.ext", NODE, "m1")
        runCurrent()
        val pending = backgroundScope.launch { h.call("command.send", """{"command":"land"}""") }
        runCurrent()
        val prompt = confirm.pending.value.single()
        assertTrue(prompt.critical)
        assertTrue(prompt.body.contains("ARMED"))
        confirm.answer(prompt.id, false)
        runCurrent()
        pending.join()
        assertTrue(commands.isEmpty())

        var result: JsonElement? = null
        val approved = backgroundScope.launch { result = h.call("command.send", """{"command":"takeoff","args":{"alt":500}}""") }
        runCurrent()
        confirm.answer(confirm.pending.value.single().id, true)
        runCurrent()
        approved.join()
        assertEquals(listOf<VehicleCommand>(VehicleCommand.Takeoff(120f)), commands)
        assertEquals("true", result!!.jsonObject["ok"]!!.jsonPrimitive.content)
    }

    @Test
    fun takeoffWithAnOnboardPilotIsItsSupervisedLaunch() = runTest {
        pilotPresent = true
        val h = factory().create("com.example.ext", NODE, "m1")
        runCurrent()
        var result: JsonElement? = null
        val job = backgroundScope.launch { result = h.call("command.send", """{"command":"takeoff","args":{"alt":6}}""") }
        runCurrent()
        confirm.answer(confirm.pending.value.single().id, true)
        runCurrent()
        job.join()
        assertEquals(listOf(PilotAction(PilotAction.LAUNCH, mapOf("after" to "hold", "alt_m" to 6f))), pilotActions)
        assertTrue("nothing went straight to the flight controller", commands.isEmpty())
        assertEquals("true", result!!.jsonObject["ok"]!!.jsonPrimitive.content)
    }

    private fun TestScope.approvedTakeoff() {
        val h = factory().create("com.example.ext", NODE, "m1")
        runCurrent()
        val job = backgroundScope.launch { h.call("command.send", """{"command":"takeoff","args":{"alt":6}}""") }
        runCurrent()
        confirm.answer(confirm.pending.value.single().id, true)
        runCurrent()
        check(job.isCompleted)
    }

    @Test
    fun takeoffWithAStalePilotGoesToTheFlightController() = runTest {
        pilotPresent = true
        pilotAgeMs = 30_000
        approvedTakeoff()
        assertTrue("a stopped pilot gets no launch", pilotActions.isEmpty())
        assertEquals(listOf<VehicleCommand>(VehicleCommand.Takeoff(6f)), commands)
    }

    @Test
    fun takeoffWhileDisarmedNeverArmsThroughThePilot() = runTest {
        pilotPresent = true
        heartbeat.value = HeartbeatState(false, FlightMode(4, "GUIDED"), 2, 3, 0)
        approvedTakeoff()
        assertTrue("the pilot's launch would arm; an extension never arms", pilotActions.isEmpty())
        assertEquals("the FC refuses a take-off while disarmed", listOf<VehicleCommand>(VehicleCommand.Takeoff(6f)), commands)
    }

    @Test
    fun queuedCommandIsReportedAsQueuedNotFailed() = runTest {
        runResult = CommandResult.Queued("r-42")
        val notices = mutableListOf<ExtensionNotice>()
        backgroundScope.launch { notifier.notices.collect { notices += it } }
        val h = factory().create("com.example.ext", NODE, "m1")
        runCurrent()
        var result: JsonElement? = null
        val job = backgroundScope.launch { result = h.call("command.send", """{"command":"land"}""") }
        runCurrent()
        confirm.answer(confirm.pending.value.single().id, true)
        runCurrent()
        job.join()
        val r = result!!.jsonObject
        assertEquals("true", r["ok"]!!.jsonPrimitive.content)
        assertEquals("true", r["queued"]!!.jsonPrimitive.content)
        assertEquals("r-42", r["receipt"]!!.jsonPrimitive.content)
        runCurrent()
        assertEquals(NoticeLevel.Info, notices.single().level)
        assertTrue(notices.single().message.contains("queued"))
    }

    @Test
    fun armingIsNeverReachableFromExtensions() = runTest {
        val h = factory().create("com.example.ext", NODE, "m1")
        val r = h.call("command.send", """{"command":"arm"}""")!!.jsonObject
        assertEquals("false", r["ok"]!!.jsonPrimitive.content)
        assertTrue(confirm.pending.value.isEmpty())
    }

    @Test
    fun configWriteIsScopedToTheExtensionAndHonoursReach() = runTest {
        val h = factory().create("com.example.ext", NODE, "m1")
        runCurrent()
        val r = h.call("command.send", """{"command":"plugin.config.write","args":{"key":"gain","value":3}}""")!!.jsonObject
        assertEquals("true", r["ok"]!!.jsonPrimitive.content)
        assertEquals(Triple("com.example.ext", "gain", JsonPrimitive(3) as JsonElement), api.writes.single())

        val cloud = factory(listOf(Reach.CloudLive)).create("com.example.ext", NODE, "m2")
        runCurrent()
        val denied = cloud.call("command.send", """{"command":"plugin.config.write","args":{"key":"gain","value":4}}""")!!.jsonObject
        assertEquals("false", denied["ok"]!!.jsonPrimitive.content)
        assertEquals(1, api.writes.size)
    }

    @Test
    fun telemetrySubscriptionStreamsVehicleTopicUntilUnsubscribed() = runTest {
        val h = factory().create("com.example.ext", NODE, "m1")
        val rec = Recorder()
        h.call("telemetry.subscribe", """{"topic":"mavlink.attitude"}""", rec, "telemetry.subscribe.mavlink.attitude")
        runCurrent()
        attitude.value = Attitude(0f, 0f, (Math.PI / 2).toFloat(), 10)
        runCurrent()
        val (method, payload) = rec.events.single()
        assertEquals("telemetry.mavlink.attitude", method)
        assertEquals(90.0, payload!!.jsonObject["yaw"]!!.jsonPrimitive.content.toDouble(), 1e-3)
        h.call("telemetry.unsubscribe", """{"topic":"mavlink.attitude"}""", rec)
        attitude.value = Attitude(1f, 0f, 0f, 20)
        runCurrent()
        assertEquals(1, rec.events.size)
        h.dispose()
    }

    @Test
    fun eventsPublishOnlyUnderOwnPrefixAndNeverEchoAgentState() = runTest {
        val h = factory().create("com.example.ext", NODE, "m1")
        val rec = Recorder()
        h.call("events.subscribe", """{"topic":"plugin.com.example.ext.*"}""", rec, "event.subscribe")
        val bad = h.call("events.publish", """{"topic":"plugin.other.x","payload":1}""")!!.jsonObject
        assertEquals("false", bad["ok"]!!.jsonPrimitive.content)
        h.call("events.publish", """{"topic":"plugin.com.example.ext.ping","payload":1}""")
        bus.publish("plugin.com.example.ext.state", JsonPrimitive(2), ExtensionEventBus.agentStateOrigin("com.example.ext", NODE))
        assertEquals(listOf("plugin.com.example.ext.ping"), rec.events.map { it.first })
    }

    @Test
    fun cloudRecordsAnswerUnsupported() = runTest {
        val h = factory().create("com.example.ext", NODE, "m1")
        val e = runCatching { h.call("records.list", "{}") }.exceptionOrNull() as BridgeException
        assertEquals(ExtensionHandlersFactory.UNSUPPORTED, e.code)
    }
}
