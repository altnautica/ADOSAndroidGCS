package com.altnautica.gcs.core.demo.node

import com.altnautica.gcs.core.demo.DemoScenario
import com.altnautica.gcs.core.demo.DemoScenarioStore
import com.altnautica.gcs.core.demo.node.FakeDemoFc.Companion.ARM_DISARM
import com.altnautica.gcs.core.demo.node.FakeDemoFc.Companion.GUIDED
import com.altnautica.gcs.core.demo.node.FakeDemoFc.Companion.SET_MODE
import com.altnautica.gcs.core.demo.node.FakeDemoFc.Companion.TAKEOFF
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DemoPilotTest {
    private var t = 1_000L
    private val fc = FakeDemoFc()
    private val scenarios = DemoScenarioStore()
    private val pilot = DemoPilot(fc, scenarios = scenarios) { t }

    private fun state(): JsonObject = Json.parseToJsonElement(pilot.frame()).jsonObject["data"]!!.jsonObject

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.obj(key: String): JsonObject = this[key]!!.jsonObject

    private fun JsonObject.strings(key: String): List<String> = this[key]!!.jsonArray.map { it.jsonPrimitive.content }

    private fun JsonObject.readiness(id: String): JsonObject = this["readiness"]!!.jsonArray.map { it.jsonObject }.first { it.str("id") == id }

    private fun arItems(ar: JsonObject): List<JsonObject> = ar.obj("cameras").obj("main")["items"]!!.jsonArray.map { it.jsonObject }

    private fun action(name: String, args: String = ""): JsonObject {
        val a = if (args.isEmpty()) "" else ""","args":$args"""
        return Json.parseToJsonElement(pilot.handle("""{"op":"action","name":"$name","src":"android"$a}""")!!).jsonObject
    }

    private fun input(src: String, vararg axes: Float) {
        val list = axes.joinToString(",")
        pilot.handle("""{"op":"input","src":"$src","session":7,"seq":1,"t_ms":$t,"axes":[$list],"buttons":0}""")
    }

    /** Advances time in 100 ms frames, streaming centred sticks from Android. */
    private fun fly(ms: Long, vararg axes: Float = floatArrayOf(0f, 0f, 0f, 0f)) {
        var left = ms
        while (left > 0) {
            t += 100
            left -= 100
            input("android", *axes)
            state()
        }
    }

    @Test
    fun `arm then take off leaves the pilot holding with the android sticks`() {
        assertTrue(action("arm")["ok"]!!.jsonPrimitive.boolean)
        assertTrue(fc.armed)
        assertEquals("idle", state().str("owner"))

        assertTrue(action("takeoff", """{"alt_m":1.5}""")["ok"]!!.jsonPrimitive.boolean)
        fly(1_000)

        val s = state()
        assertEquals("pilot", s.str("owner"))
        assertEquals("android", s.str("input_src"))
        assertEquals(false, s["landed"]!!.jsonPrimitive.boolean)
        assertEquals("GUIDED", s.str("fc_mode_name"))
        assertEquals("done", s["sequence"]!!.jsonObject.str("step"))
        assertEquals(listOf(ARM_DISARM, SET_MODE, TAKEOFF), fc.sent)
    }

    @Test
    fun `take off arms by itself and refuses in the air`() {
        action("takeoff")
        assertTrue(fc.armed)
        fly(500)
        assertEquals("pilot", state().str("owner"))
        assertEquals("airborne", action("takeoff")["err"]!!.jsonPrimitive.content)
        assertEquals("armed", action("arm")["err"]!!.jsonPrimitive.content)
    }

    @Test
    fun `sticks take an armed idle vehicle in guided only in ados mode, behind the centring gate`() {
        fc.armed = true
        fc.altitudeM = 5.0
        fc.mode = GUIDED
        action("control_mode:fc_manual")
        input("android", 0f, 0.8f, 0f, 0f)
        assertEquals("idle", state().str("owner"))

        action("control_mode:ados")
        t += 100
        input("android", 0f, 0.8f, 0f, 0f)
        val taken = state()
        assertEquals("pilot", taken.str("owner"))
        assertEquals(true, taken["gate"]!!.jsonObject["pending"]!!.jsonPrimitive.boolean)

        fly(700)
        assertTrue(state()["gate"] is kotlinx.serialization.json.JsonNull)
    }

    @Test
    fun `a second surface is ignored until it takes the session`() {
        action("takeoff")
        fly(500)
        input("mission-control", 0.5f, 0f, 0f, 0f)
        assertEquals("android", state().str("input_src"))

        pilot.handle("""{"op":"session.take","src":"mission-control","session":9}""")
        assertEquals("mission-control", state().str("input_src"))
    }

    @Test
    fun `a flight controller mode change hands the owner to the flight controller, and land disarms to idle`() {
        action("takeoff")
        fly(500)
        assertTrue(action("rth")["ok"]!!.jsonPrimitive.boolean)
        fc.mode = 6
        t += 2_000
        assertEquals("fc_mode", state().str("owner"))

        fc.armed = false
        fc.altitudeM = 0.0
        t += 100
        val s = state()
        assertEquals("idle", s.str("owner"))
        assertEquals(true, s["landed"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `pause during a launch on the ground disarms and marks the sequence aborted`() {
        fc.mode = 9
        action("launch", """{"after":"hold","alt_m":3}""")
        // Arming from LAND first switches to GUIDED, which arms.
        assertEquals(listOf(SET_MODE, ARM_DISARM), fc.sent)
        assertTrue(fc.armed)
        action("pause")
        assertFalse(fc.armed)
        val seq = state()["sequence"]!!.jsonObject
        assertEquals("aborted", seq.str("error"))
    }

    @Test
    fun `stale input while the pilot owns shows the controller-lost hold`() {
        action("takeoff")
        fly(500)
        t += 400
        assertEquals("controller-lost", state().str("hold_reason"))
        pilot.handle("""{"op":"release","src":"android","session":7}""")
        assertNull(state().str("input_src"))
    }

    @Test
    fun `the live frame names the link-loss action, offers goto in ADOS control and reports height above ground`() {
        val ground = state()
        assertEquals("rth", ground.str("link_loss_action"))
        assertTrue(ground["link_loss_in_s"] is JsonNull)
        assertEquals(listOf("goto"), ground.strings("surface_ops"))
        assertTrue(ground["statustext"]!!.jsonArray.isEmpty())
        assertEquals(0.0, ground["agl_m"]!!.jsonPrimitive.double, 0.0)
        assertEquals("not_armed", action("goto", GOTO_ARGS).str("err"))
        action("arm")
        assertEquals("landed", action("goto", GOTO_ARGS).str("err"))

        action("takeoff", """{"alt_m":1.5}""")
        fly(1_000)
        assertEquals(1.5, state()["agl_m"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(1.5, pilot.view().heightM, 1e-9)
    }

    @Test
    fun `unpinned, the pilot flies a surface's goto in the air and refuses it outside ADOS control`() {
        action("takeoff", """{"alt_m":1.5}""")
        fly(1_000)
        assertEquals("bad_args", action("goto", """{"lon":77.6,"alt_m":10}""").str("err"))
        assertTrue(action("goto", GOTO_ARGS)["ok"]!!.jsonPrimitive.boolean)
        assertEquals("goto", state().str("owner"))

        action("control_mode:fc_manual")
        assertTrue(state().strings("surface_ops").isEmpty())
        assertEquals("control_mode", action("goto", GOTO_ARGS).str("err"))
    }

    @Test
    fun `the transition log runs outside the pilot's lock, so another thread never waits on it`() {
        val answered = java.util.concurrent.atomic.AtomicBoolean(false)
        val answeredInTime = java.util.concurrent.atomic.AtomicBoolean(false)
        lateinit var p: DemoPilot
        p = DemoPilot(FakeDemoFc(), onTransition = {
            val other = Thread {
                p.handle("""{"op":"action","name":"profile:sport","src":"android"}""")
                answered.set(true)
            }
            other.start()
            other.join(2_000)
            answeredInTime.set(answered.get())
        }, scenarios = DemoScenarioStore()) { 1_000L }
        p.frame()
        assertTrue(answeredInTime.get())
    }

    @Test
    fun `every scenario has its own operator label`() {
        val labels = DemoScenario.entries.map { it.label }
        assertTrue(labels.all { it != 0 })
        assertEquals(labels.size, labels.toSet().size)
    }

    @Test
    fun `the flight controller's brake mode is named`() {
        fc.armed = true
        fc.altitudeM = 5.0
        fc.mode = 17
        assertEquals("BRAKE", state().str("fc_mode_name"))
    }

    @Test
    fun `the readiness pin fails the position block item and refuses to arm until unpinned`() {
        scenarios.pin(DemoScenario.ReadinessBlocked)
        val s = state()
        assertEquals("idle", s.str("owner"))
        val position = s.readiness("position")
        assertFalse(position["ok"]!!.jsonPrimitive.boolean)
        assertEquals("block", position.str("severity"))
        assertEquals("not_ready:position", action("arm").str("err"))
        assertEquals("not_ready:position", action("launch", """{"after":"hold"}""").str("err"))
        assertFalse(fc.armed)

        scenarios.pin(null)
        assertTrue(state().readiness("position")["ok"]!!.jsonPrimitive.boolean)
        assertTrue(action("arm")["ok"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `mission pins report waypoint 4 of 6 active with an ETA, paused, and complete`() {
        scenarios.pin(DemoScenario.MissionActive)
        val active = state()
        assertEquals("mission_fc", active.str("owner"))
        assertEquals("AUTO", active.str("fc_mode_name"))
        assertEquals(true, active["armed"]!!.jsonPrimitive.boolean)
        assertEquals(false, active["landed"]!!.jsonPrimitive.boolean)
        val mission = active.obj("mission")
        assertEquals("active", mission.str("state"))
        assertEquals(4, mission["seq"]!!.jsonPrimitive.int)
        assertEquals(6, mission["total"]!!.jsonPrimitive.int)
        assertEquals("mission_state", mission.str("source"))
        assertEquals(42.0, pilot.view().missionEtaS!!, 1e-9)
        t += 2_000
        assertEquals(40.0, pilot.view().missionEtaS!!, 1e-9)

        scenarios.pin(DemoScenario.MissionPaused)
        val paused = state()
        assertEquals("paused", paused.obj("mission").str("state"))
        assertEquals(4, paused.obj("mission")["seq"]!!.jsonPrimitive.int)
        assertEquals("pilot", paused.str("owner"))
        assertNull(pilot.view().missionEtaS)
        assertTrue(pilot.view().missionRoute)

        scenarios.pin(DemoScenario.MissionComplete)
        val complete = state().obj("mission")
        assertEquals("complete", complete.str("state"))
        assertEquals(6, complete["seq"]!!.jsonPrimitive.int)
        assertEquals(6, complete["total"]!!.jsonPrimitive.int)
        assertFalse(pilot.view().missionRoute)
    }

    @Test
    fun `the failsafe pins report a flight controller failsafe and refuse pause`() {
        assertFalse(state()["fc_failsafe"]!!.jsonPrimitive.boolean)
        for (scenario in listOf(DemoScenario.FcFailsafeRtl, DemoScenario.GotoRefusedFailsafe)) {
            scenarios.pin(scenario)
            val s = state()
            assertEquals(scenario.name, true, s["fc_failsafe"]?.jsonPrimitive?.boolean)
            assertEquals(scenario.name, "fc", s.str("failsafe_cause"))
            assertEquals(scenario.name, "failsafe", action("pause").str("err"))
        }
        scenarios.pin(null)
        val live = state()
        assertFalse(live["fc_failsafe"]!!.jsonPrimitive.boolean)
        assertTrue(live["failsafe_cause"] is JsonNull)
        assertNull(action("pause").str("err"))
    }

    @Test
    fun `actions answer from the pinned flight state, not the flight controller's`() {
        // A disarmed vehicle on the ground pinned to a flying mission.
        scenarios.pin(DemoScenario.MissionActive)
        assertEquals("armed", action("arm").str("err"))
        assertEquals("not_landed", action("disarm").str("err"))
        assertEquals("airborne", action("takeoff").str("err"))
        assertTrue(action("rth")["ok"]!!.jsonPrimitive.boolean)

        // A flying vehicle pinned disarmed on the ground.
        scenarios.pin(null)
        fc.armed = true
        fc.altitudeM = 10.0
        fc.mode = GUIDED
        scenarios.pin(DemoScenario.GotoRefusedNotArmed)
        assertEquals("disarmed", action("land").str("err"))
        assertEquals("disarmed", action("take_control").str("err"))

        scenarios.pin(null)
        assertTrue(action("land")["ok"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `the failsafe pin holds an RTL the flight controller entered on its own`() {
        scenarios.pin(DemoScenario.FcFailsafeRtl)
        repeat(2) {
            val s = state()
            assertEquals("fc_mode", s.str("owner"))
            assertEquals(6, s["fc_mode"]!!.jsonPrimitive.int)
            assertEquals("RTL", s.str("fc_mode_name"))
            assertEquals(true, s["armed"]!!.jsonPrimitive.boolean)
            assertEquals(false, s["landed"]!!.jsonPrimitive.boolean)
            val banner = s.obj("banner")
            assertEquals("pilot.banner.fc_mode_change", banner.str("text_key"))
            assertEquals("RTL", banner.obj("args").str("mode"))
            t += 30_000
        }
    }

    @Test
    fun `a stalled pilot answers nothing and sends no frames while its stream stays open`() = runTest {
        scenarios.pin(DemoScenario.PilotStalled)
        assertNull(pilot.snapshot().frame)
        assertNull(pilot.handle("""{"op":"action","name":"arm","src":"android"}"""))
        assertFalse(fc.armed)

        val got = mutableListOf<String>()
        backgroundScope.launch { pilot.stream(emptyFlow()).collect { got += it } }
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(got.isEmpty())

        scenarios.pin(null)
        advanceTimeBy(300)
        runCurrent()
        assertTrue(got.isNotEmpty())
        assertNotNull(pilot.snapshot().frame)
    }

    @Test
    fun `the link-loss pin counts down to a return home`() {
        scenarios.pin(DemoScenario.LinkLoss)
        val s = state()
        assertEquals(10.0, s["link_loss_in_s"]!!.jsonPrimitive.double, 1e-9)
        assertEquals("rth", s.str("link_loss_action"))
        t += 3_000
        assertEquals(7.0, state()["link_loss_in_s"]!!.jsonPrimitive.double, 1e-9)
    }

    @Test
    fun `the no-GNSS pin keeps position from the EKF origin and only the pilot's own readiness ids`() {
        scenarios.pin(DemoScenario.NoGnss)
        val s = state()
        val position = s.readiness("position")
        assertTrue(position["ok"]!!.jsonPrimitive.boolean)
        assertTrue(position.str("msg")!!.contains("EKF origin"))
        val ids = s["readiness"]!!.jsonArray.map { it.jsonObject.str("id") }
        assertEquals(listOf("fc_profile", "position"), ids)
        assertFalse(fc.gnssFix)
    }

    @Test
    fun `the status text pin reports eight or more distinct non-PreArm warnings, one long`() {
        scenarios.pin(DemoScenario.StatusTexts)
        val texts = state()["statustext"]!!.jsonArray.map { it.jsonObject }
        assertTrue(texts.all { it["severity"]!!.jsonPrimitive.int == 4 })
        assertTrue(texts.any { it.str("text")!!.length > 100 })
        // The pill keeps one PreArm text at a time, so only the others count towards the eight.
        val listed = texts.map { it.str("text")!! }.filterNot { it.startsWith("PreArm") }.toSet()
        assertTrue(listed.toString(), listed.size >= 8)
    }

    @Test
    fun `the fly-here pin offers goto, flies an accepted point and checks its arguments and control mode`() {
        scenarios.pin(DemoScenario.FlyHere)
        assertEquals(listOf("goto"), state().strings("surface_ops"))
        assertEquals("bad_args", action("goto", """{"lon":77.6,"alt_m":10}""").str("err"))
        assertTrue(action("goto", GOTO_ARGS)["ok"]!!.jsonPrimitive.boolean)
        assertEquals("goto", state().str("owner"))

        action("control_mode:fc_manual")
        assertTrue(state().strings("surface_ops").isEmpty())
        assertEquals("control_mode", action("goto", GOTO_ARGS).str("err"))
    }

    @Test
    fun `each goto refusal pin refuses goto with its code, offering it only in ADOS control`() {
        val refusing = DemoScenario.entries.filter { it.gotoRefusal != null }
        assertEquals(
            setOf("bad_args", "not_armed", "landed", "tuning", "no_home", "launching", "control_mode", "failsafe"),
            refusing.map { it.gotoRefusal }.toSet(),
        )
        for (scenario in refusing) {
            scenarios.pin(scenario)
            val s = state()
            val offered = if (scenario.gotoRefusal == "control_mode") emptyList() else listOf("goto")
            assertEquals(scenario.name, offered, s.strings("surface_ops"))
            assertEquals(scenario.name, scenario.gotoRefusal, action("goto", GOTO_ARGS).str("err"))
            when (scenario.gotoRefusal) {
                "not_armed" -> assertEquals(false, s["armed"]!!.jsonPrimitive.boolean)
                "landed" -> assertEquals(true, s["landed"]!!.jsonPrimitive.boolean)
                "tuning" -> assertEquals("tuning", s.str("owner"))
                "launching" -> assertEquals("takeoff", s.obj("sequence").str("step"))
                "control_mode" -> assertEquals("fc_manual", s.str("control_mode"))
                "failsafe" -> assertEquals("RTL", s.str("fc_mode_name"))
            }
        }
    }

    @Test
    fun `hold pins use the pilot's hold reason wire strings and its banners`() {
        val holds = DemoScenario.entries.filter { it.holdReason != null }
        assertEquals(
            listOf("controller-lost", "link-loss", "obstacle-close", "lease-revoked", "blocked"),
            holds.map { it.holdReason },
        )
        for (scenario in holds) {
            scenarios.pin(scenario)
            val s = state()
            assertEquals(scenario.name, scenario.holdReason, s.str("hold_reason"))
            assertEquals(scenario.name, "pilot", s.str("owner"))
        }
        scenarios.pin(DemoScenario.HoldLinkLoss)
        assertEquals("pilot.banner.link_loss", state().obj("banner").str("text_key"))
    }

    @Test
    fun `demo layers show the follow, tracks and re-acquired events only while the follow skill flies`() {
        val layers = DemoLayerFrames()
        val feed = DemoFollowFeed()
        val followKinds = setOf("fpath", "fgoal", "orbit")

        val idle = pilot.view()
        assertFalse(idle.followRunning)
        assertTrue(arItems(layers.ar(t, idle)).none { it.str("t") in followKinds })
        var last: Int? = null
        for (now in 0L until 32_000L step 100) {
            val detections = feed.detections(now, 0, idle.followRunning)["detections"]!!.jsonArray.map { it.jsonObject }
            assertTrue(detections.all { it["track_id"] is JsonNull && it["lock_state"] is JsonNull })
            assertNull(feed.event(last, now, idle.followRunning))
            last = null
        }

        scenarios.pin(DemoScenario.Follow)
        val following = pilot.view()
        assertTrue(following.followRunning)
        assertEquals("skill", state().str("owner"))
        assertTrue(arItems(layers.ar(t, following)).map { it.str("t") }.containsAll(followKinds))
        val tracked = feed.detections(4_100L, 0, true)["detections"]!!.jsonArray.map { it.jsonObject }
        assertTrue(tracked.any { it.str("lock_state") == "locked" })
        val events = mutableListOf<String>()
        last = null
        for (now in 0L until 32_000L step 100) {
            feed.event(last, now, true)?.let { events += it.first }
            last = feed.step(now)
        }
        assertTrue("follow.reacquired" in events)
    }

    @Test
    fun `the demo route draws only while the pilot's mission is active or paused, with its ETA`() {
        val layers = DemoLayerFrames()
        val idle = pilot.view()
        val idleAr = layers.ar(t, idle)
        assertEquals("none", idleAr.obj("mission").str("state"))
        assertTrue(arItems(idleAr).none { it.str("t") == "wp" || it.str("t") == "leg" })

        scenarios.pin(DemoScenario.MissionActive)
        val active = pilot.view()
        val ar = layers.ar(t, active)
        assertEquals("active", ar.obj("mission").str("state"))
        val next = arItems(ar).first { it.str("t") == "wp" && it.str("role") == "next" }
        assertEquals(4, next["seq"]!!.jsonPrimitive.int)
        assertEquals(active.missionEtaS!!, next["eta_s"]!!.jsonPrimitive.double, 1e-9)

        scenarios.pin(DemoScenario.MissionComplete)
        assertTrue(arItems(layers.ar(t, pilot.view())).none { it.str("t") == "wp" || it.str("t") == "leg" })
    }

    private companion object {
        const val GOTO_ARGS = """{"lat":12.9,"lon":77.6,"alt_m":10}"""
    }
}
