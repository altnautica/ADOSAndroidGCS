package com.altnautica.gcs.core.agent.pilot

import com.altnautica.gcs.core.model.AvoidMode
import com.altnautica.gcs.core.model.AvoidState
import com.altnautica.gcs.core.model.BatteryReserve
import com.altnautica.gcs.core.model.ControlMode
import com.altnautica.gcs.core.model.InputSession
import com.altnautica.gcs.core.model.MissionGap
import com.altnautica.gcs.core.model.MissionStall
import com.altnautica.gcs.core.model.MissionStallKind
import com.altnautica.gcs.core.model.MissionState
import com.altnautica.gcs.core.model.MissionTarget
import com.altnautica.gcs.core.model.OverheadAhead
import com.altnautica.gcs.core.model.PilotAction
import com.altnautica.gcs.core.model.PilotInput
import com.altnautica.gcs.core.model.PilotOwner
import com.altnautica.gcs.core.model.PilotProfile
import com.altnautica.gcs.core.model.PilotSkillRef
import com.altnautica.gcs.core.model.PilotVehicleClass
import com.altnautica.gcs.core.model.ReadinessSeverity
import com.altnautica.gcs.core.model.PlaceClass
import com.altnautica.gcs.core.model.RcReceiver
import com.altnautica.gcs.core.model.RouteCheck
import com.altnautica.gcs.core.model.RouteCheckState
import com.altnautica.gcs.core.model.RoutePinch
import com.altnautica.gcs.core.model.ProfileLimits
import com.altnautica.gcs.core.model.Sensing
import com.altnautica.gcs.core.model.SensingTier
import com.altnautica.gcs.core.model.SpeedCap
import com.altnautica.gcs.core.model.StickCommand
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PilotWireTest {
    private val fixture: String = checkNotNull(javaClass.classLoader!!.getResource("fixtures/pilot-frame.json")).readText()
    private val data = Json.parseToJsonElement(fixture) as JsonObject

    @Test
    fun `golden pilot state parses every field`() {
        val s = checkNotNull(PilotWire.parseState(data, receivedAtMs = 42L))
        assertEquals(PilotOwner.Skill, s.owner)
        assertEquals("FOLLOW · ORBIT 8 m", s.ownerLabel)
        assertEquals(PilotSkillRef("com.example.follow", "follow-me", "Follow"), s.skill)
        assertEquals(PilotVehicleClass.Multirotor, s.vehicleClass)
        assertEquals(ControlMode.Ados, s.controlMode)
        assertEquals(PilotProfile.Normal, s.profile)
        assertEquals(AvoidMode.Brake, s.avoid)
        assertEquals(4, s.fcMode)
        assertEquals("GUIDED", s.fcModeName)
        assertEquals(true, s.armed)
        assertEquals(false, s.landed)
        assertEquals("mc", s.inputSrc)
        assertEquals(40L, s.inputAgeMs)
        assertNull(s.holdReason)
        assertEquals(AvoidState.Detour, s.avoidState)
        assertEquals(2.4f, s.minClearanceM!!, 1e-6f)
        assertEquals(listOf(12.0f, -3.5f, -4.0f), s.goalNed)
        assertEquals(listOf(10.0f to -2.0f, 10.8f to -2.6f, 11.5f to -3.1f, 12.0f to -3.5f), s.planNed)
        assertEquals(listOf(10.8f, -2.6f, -4.0f), s.setpointNed)
        assertEquals(setOf("goto"), s.surfaceOps)
        assertEquals(4.2f, s.aglM!!, 1e-6f)
        assertEquals("rangefinder", s.aglSource)
        assertEquals(listOf(com.altnautica.gcs.core.model.PilotStatusText(4, "PreArm: Compass not calibrated", 1200L)), s.statusTexts)
        assertEquals(3, s.readiness.size)
        assertEquals("agl", s.readiness[2].id)
        assertFalse(s.readiness[2].ok)
        assertEquals(ReadinessSeverity.Warn, s.readiness[2].severity)
        assertNull(s.firstBlock)
        assertNull(s.linkLossInS)
        val m = checkNotNull(s.mission)
        assertEquals(MissionState.None, m.state)
        assertEquals(0, m.seq)
        assertEquals(0, m.total)
        assertNull(m.itemReached)
        assertNull(m.id)
        assertFalse(m.fromMissionState)
        assertEquals(0L, m.ageMs)
        assertFalse(m.landingSeq)
        assertNull(m.uploadsSeen)
        assertEquals(InputSession("mc", "Mission Control", 7L), s.inputSession)
        assertNull(s.gate)
        assertNull(s.sequence)
        val b = checkNotNull(s.banner)
        assertEquals("pilot.banner.ados_engaged", b.textKey)
        assertEquals(mapOf("source" to "GCS"), b.args)
        assertEquals(1_793_960_003_000L, b.untilMs)
        assertNull(s.rthCountdownS)
        assertEquals(42L, s.receivedAtMs)
    }

    @Test
    fun `stale obstacle data parses as the stale avoid state with its hold reason`() {
        val stale = JsonObject(data + mapOf(
            "avoid_state" to JsonPrimitive("stale"),
            "hold_reason" to JsonPrimitive("obstacle-data-stale"),
        ))
        val s = checkNotNull(PilotWire.parseState(stale, 0L))
        assertEquals(AvoidState.Stale, s.avoidState)
        assertEquals("obstacle-data-stale", s.holdReason)
    }

    @Test
    fun `a drone with no lidar parses as the unavailable avoid state`() {
        val none = JsonObject(data + mapOf("avoid_state" to JsonPrimitive("unavailable")))
        val s = checkNotNull(PilotWire.parseState(none, 0L))
        assertEquals(AvoidState.Unavailable, s.avoidState)
    }

    @Test
    fun `a pilot that has not heard the flight controller reports its mode as unknown`() {
        val unheard = JsonObject(
            data + mapOf("fc_mode" to JsonNull, "fc_mode_name" to JsonNull, "armed" to JsonNull, "landed" to JsonNull),
        )
        val s = checkNotNull(PilotWire.parseState(unheard, receivedAtMs = 42L))
        assertNull(s.fcMode)
        assertNull(s.fcModeName)
        assertNull(s.armed)
        assertNull(s.landed)
    }

    @Test
    fun `link loss action, VTOL transition and setup step parse from the pilot frame`() {
        val golden = checkNotNull(PilotWire.parseState(data, 0L))
        assertEquals("rth", golden.linkLossAction)
        assertFalse(golden.vtolTransitioning)
        assertNull(golden.setupStep)

        val tuning = JsonObject(data + mapOf(
            "owner" to JsonPrimitive("tuning"),
            "setup_step" to JsonPrimitive("autotune_roll"),
            "link_loss_action" to JsonPrimitive("land"),
            "vtol_transitioning" to JsonPrimitive(true),
        ))
        val s = checkNotNull(PilotWire.parseState(tuning, 0L))
        assertEquals("autotune_roll", s.setupStep)
        assertEquals("land", s.linkLossAction)
        assertTrue(s.vtolTransitioning)

        val bare = checkNotNull(PilotWire.parseState(Json.parseToJsonElement("""{"owner":"idle"}""") as JsonObject, 0L))
        assertNull(bare.linkLossAction)
        assertNull(bare.setupStep)
        assertFalse(bare.vtolTransitioning)
    }

    @Test
    fun `FC failsafe, its cause and a paused mission parse, a missing fc_failsafe is false`() {
        val golden = checkNotNull(PilotWire.parseState(data, 0L))
        assertFalse(golden.fcFailsafe)
        assertNull(golden.failsafeCause)

        val failsafe = JsonObject(data + mapOf(
            "fc_failsafe" to JsonPrimitive(true),
            "failsafe_cause" to JsonPrimitive("rc_or_failsafe"),
        ))
        val f = checkNotNull(PilotWire.parseState(failsafe, 0L))
        assertTrue(f.fcFailsafe)
        assertEquals("rc_or_failsafe", f.failsafeCause)

        val paused = Json.parseToJsonElement(
            """{"owner":"pilot","mission":{"state":"paused","seq":3,"total":6}}""",
        ) as JsonObject
        val p = checkNotNull(PilotWire.parseState(paused, 0L))
        assertEquals(MissionState.Paused, p.mission?.state)
        assertFalse(p.fcFailsafe)
    }

    @Test
    fun `a setup abort parses as its setup-strings key, null in the golden frame`() {
        assertNull(checkNotNull(PilotWire.parseState(data, 0L)).setupAbort)
        val aborted = JsonObject(data + mapOf("setup_abort" to JsonPrimitive("setup.blocker.aborted_fc_failsafe")))
        assertEquals("setup.blocker.aborted_fc_failsafe", checkNotNull(PilotWire.parseState(aborted, 0L)).setupAbort)
    }

    @Test
    fun `the low-battery action parses land or rth only, and is null while none`() {
        assertNull(checkNotNull(PilotWire.parseState(data, 0L)).lowBatteryAction)
        for (action in listOf("land", "rth")) {
            val s = checkNotNull(PilotWire.parseState(JsonObject(data + ("low_battery_action" to JsonPrimitive(action))), 0L))
            assertEquals(action, s.lowBatteryAction)
        }
        for (other in listOf(JsonPrimitive("hold"), JsonPrimitive(1), JsonNull)) {
            val s = checkNotNull(PilotWire.parseState(JsonObject(data + ("low_battery_action" to other)), 0L))
            assertNull(other.toString(), s.lowBatteryAction)
        }
    }

    @Test
    fun `the battery reserve parses need, basis and time home, and is null while none`() {
        assertNull(checkNotNull(PilotWire.parseState(data, 0L)).batteryReserve)
        val reserve = JsonObject(
            mapOf("need_pct" to JsonPrimitive(52), "basis" to JsonPrimitive("energy"), "return_s" to JsonPrimitive(248)),
        )
        val s = checkNotNull(PilotWire.parseState(JsonObject(data + ("battery_reserve" to reserve)), 0L))
        assertEquals(BatteryReserve(52, "energy", 248), s.batteryReserve)
        val odd = JsonObject(reserve + ("basis" to JsonPrimitive("guess")))
        assertNull(checkNotNull(PilotWire.parseState(JsonObject(data + ("battery_reserve" to odd)), 0L)).batteryReserve)
    }

    @Test
    fun `the overhead ahead parses the fly-under height and distance, and is null while nothing lies ahead`() {
        assertNull(checkNotNull(PilotWire.parseState(data, 0L)).overheadAhead)
        val ahead = JsonObject(
            mapOf("lease_id" to JsonPrimitive(7), "fly_under_agl_m" to JsonPrimitive(3.87), "ahead_m" to JsonPrimitive(2.5)),
        )
        val s = checkNotNull(PilotWire.parseState(JsonObject(data + ("overhead_ahead" to ahead)), 0L))
        assertEquals(OverheadAhead(7L, 3.87f, 2.5f), s.overheadAhead)
        val clear = JsonObject(ahead + ("fly_under_agl_m" to JsonNull) + ("ahead_m" to JsonNull))
        assertNull(checkNotNull(PilotWire.parseState(JsonObject(data + ("overhead_ahead" to clear)), 0L)).overheadAhead)
    }

    @Test
    fun `the sensing tier and its speed limit parse, and an absent or unknown tier reads as none`() {
        assertNull(checkNotNull(PilotWire.parseState(data, 0L)).sensing)
        val sensing = JsonObject(
            mapOf("tier" to JsonPrimitive("lidar3d"), "clear_range_m" to JsonPrimitive(11.6), "speed_limit_mps" to JsonPrimitive(4.2)),
        )
        val s = checkNotNull(PilotWire.parseState(JsonObject(data + ("sensing" to sensing)), 0L))
        assertEquals(Sensing(SensingTier.Lidar3d, 11.6f, 4.2f), s.sensing)
        val radar = JsonObject(sensing + ("tier" to JsonPrimitive("radar")))
        assertNull(checkNotNull(PilotWire.parseState(JsonObject(data + ("sensing" to radar)), 0L)).sensing)
    }

    @Test
    fun `pilot frame envelope wraps the golden state`() {
        val text = """{"kind":"pilot","ts_ms":1793960000000,"data":$fixture}"""
        val f = PilotWire.parseFrame(text, receivedAtMs = 7L) as PilotFrame.State
        assertEquals(1_793_960_000_000L, f.tsMs)
        assertEquals(PilotOwner.Skill, f.state.owner)
        assertEquals(7L, f.state.receivedAtMs)
    }

    @Test
    fun `keepalive and action results parse, other frames do not`() {
        assertEquals(PilotFrame.Keepalive(5L), PilotWire.parseFrame("""{"kind":"keepalive","ts_ms":5}""", 0L))
        assertEquals(
            PilotFrame.ActionResult("arm", "a1", false, "disarmed"),
            PilotWire.parseFrame("""{"kind":"action_result","name":"arm","id":"a1","ok":false,"err":"disarmed"}""", 0L),
        )
        assertEquals(PilotFrame.ActionResult("pause", null, true, null), PilotWire.parseFrame("""{"kind":"action_result","name":"pause","ok":true}""", 0L))
        assertNull(PilotWire.parseFrame("""{"kind":"pose","ts_ms":1,"data":{}}""", 0L))
        assertNull(PilotWire.parseFrame("""{"kind":"pilot","data":{"armed":true}}""", 0L))
        assertNull(PilotWire.parseFrame("not json", 0L))
    }

    @Test
    fun `unknown owner and full mission parse`() {
        val text = """{"owner":"warp","armed":false,"landed":true,"mission":{"state":"active","seq":3,"total":9,
            |"item_reached":2,"id":77,"source":"mission_state","age_ms":120,"landing_seq":true,"uploads_seen":4},
            |"gate":{"pending":true,"progress":0.5},"sequence":{"step":"takeoff","after":"hold","error":null}}""".trimMargin()
        val s = checkNotNull(PilotWire.parseState(Json.parseToJsonElement(text) as JsonObject, 0L))
        assertEquals(PilotOwner.Unknown, s.owner)
        val m = checkNotNull(s.mission)
        assertEquals(MissionState.Active, m.state)
        assertEquals(3, m.seq)
        assertEquals(9, m.total)
        assertEquals(2, m.itemReached)
        assertEquals(77L, m.id)
        assertTrue(m.fromMissionState)
        assertTrue(m.landingSeq)
        assertEquals(4L, m.uploadsSeen)
        assertTrue(s.gate!!.pending)
        assertEquals(0.5f, s.gate!!.progress, 0f)
        assertEquals("hold", s.sequence!!.after)
    }

    @Test
    fun `a null or absent arm state stays unknown and reads as possibly airborne`() {
        for (text in listOf("""{"owner":"idle","armed":null,"landed":null}""", """{"owner":"idle"}""")) {
            val s = checkNotNull(PilotWire.parseState(Json.parseToJsonElement(text) as JsonObject, 0L))
            assertNull(text, s.armed)
            assertNull(text, s.landed)
            assertTrue(text, s.armStateUnknown)
            assertTrue(text, s.possiblyAirborne)
            assertFalse(text, s.knownAirborne)
        }
        val ground = checkNotNull(PilotWire.parseState(Json.parseToJsonElement("""{"owner":"idle","armed":false,"landed":true}""") as JsonObject, 0L))
        assertFalse(ground.armStateUnknown)
        assertFalse(ground.possiblyAirborne)
    }

    @Test
    fun `the stall golden frame parses the mission stall, its target and the skipped waypoints`() {
        val text = checkNotNull(javaClass.classLoader!!.getResource("fixtures/pilot-frame-stall.json")).readText()
        val s = checkNotNull(PilotWire.parseState(Json.parseToJsonElement(text) as JsonObject, 0L))
        assertEquals("mission-stalled", s.holdReason)
        assertEquals(MissionStall(MissionStallKind.Mission, 3, "3", 1793960000000L, 32.5f, 15f), s.missionStall)
        val m = checkNotNull(s.mission)
        assertEquals(MissionTarget(3, 16, listOf(42.5f, -18f, -15f), null), m.target)
        assertEquals(listOf(MissionGap(2, "stall_timeout", 1793959950000L)), m.gaps)
        assertEquals("pilot.banner.mission_stalled", s.banner?.textKey)
    }

    @Test
    fun `a frame without a stall leaves the stall, target and gaps absent`() {
        val s = checkNotNull(PilotWire.parseState(data, 0L))
        assertNull(s.missionStall)
        assertNull(s.mission?.target)
        assertEquals(emptyList<MissionGap>(), s.mission?.gaps)
    }

    @Test
    fun `a confined operator wait reads its countdown to the return home`() {
        val wait = """{"owner":"pilot","mission_stall":{"kind":"mission","seq":3,"wp_label":"3","since_ms":5,"skip_in_s":null,"return_in_s":44.2,"window_s":15.0}}"""
        val s = checkNotNull(PilotWire.parseState(Json.parseToJsonElement(wait) as JsonObject, 0L))
        assertEquals(MissionStall(MissionStallKind.Mission, 3, "3", 5L, null, 15f, returnInS = 44.2f), s.missionStall)
    }

    @Test
    fun `a return stall holds with no countdown, a malformed stall is dropped`() {
        val ret = """{"owner":"pilot","mission_stall":{"kind":"return","seq":null,"wp_label":null,"since_ms":5,"skip_in_s":null,"window_s":15.0},
            |"mission":{"state":"returning","seq":0,"total":4,"target":{"seq":0,"cmd":20,"ned":null,"radius_m":30.0}}}""".trimMargin()
        val s = checkNotNull(PilotWire.parseState(Json.parseToJsonElement(ret) as JsonObject, 0L))
        assertEquals(MissionStall(MissionStallKind.Return, null, null, 5L, null, 15f), s.missionStall)
        assertEquals(MissionTarget(0, 20, null, 30f), s.mission?.target)
        for (bad in listOf("""{"kind":"warp","window_s":15}""", """{"kind":"mission","seq":3}""")) {
            val b = checkNotNull(PilotWire.parseState(Json.parseToJsonElement("""{"owner":"pilot","mission_stall":$bad}""") as JsonObject, 0L))
            assertNull(bad, b.missionStall)
        }
    }

    @Test
    fun `the confined golden frame parses the place class, the route check and the confined speed cap`() {
        val text = checkNotNull(javaClass.classLoader!!.getResource("fixtures/pilot-frame-confined.json")).readText()
        val s = checkNotNull(PilotWire.parseState(Json.parseToJsonElement(text) as JsonObject, 0L))
        assertEquals(PlaceClass.Confined, s.placeClass)
        assertEquals(
            RouteCheck(
                state = RouteCheckState.Infeasible,
                requiredM = 0.577,
                marginM = 0.477,
                sigmaM = 0.02,
                pinch = RoutePinch(3, 4, listOf(41.25, -17.5, -2.0), 0.52, 0.577),
                unverifiedLegs = listOf(5, 6),
            ),
            s.routeCheck,
        )
        assertEquals(SpeedCap("confined-near", 0.5f), s.speedCap)
        assertEquals("route-infeasible", s.holdReason)
        assertEquals("pilot.banner.route_infeasible", s.banner?.textKey)
        assertEquals(mapOf("leg" to "3→4", "clearance_m" to "0.52", "required_m" to "0.58"), s.banner?.args)
    }

    @Test
    fun `a pilot without the confined profile reads open with no route check, an unknown sigma or no pinch stays null`() {
        val s = checkNotNull(PilotWire.parseState(data, 0L))
        assertEquals(PlaceClass.Open, s.placeClass)
        assertNull(s.routeCheck)
        val rc = """{"owner":"pilot","place_class":"confined","route_check":{"state":"unverified","required_m":0.577,"margin_m":0.477,"sigma_m":null,"pinch":null,"unverified_legs":[2]}}"""
        val u = checkNotNull(PilotWire.parseState(Json.parseToJsonElement(rc) as JsonObject, 0L))
        assertEquals(RouteCheck(RouteCheckState.Unverified, 0.577, 0.477, null, null, listOf(2)), u.routeCheck)
        val bad = """{"owner":"pilot","place_class":"cave","route_check":{"state":"maybe","required_m":0.5}}"""
        val b = checkNotNull(PilotWire.parseState(Json.parseToJsonElement(bad) as JsonObject, 0L))
        assertEquals(PlaceClass.Open, b.placeClass)
        assertNull(b.routeCheck)
    }

    @Test
    fun `the route check names the waypoints it cannot place, a mission too long to hold and a stale feed, all absent by default`() {
        val rc = """{"owner":"pilot","place_class":"confined","route_check":{"state":"unverified","required_m":0.577,"unverified_legs":[],
            |"unchecked":[3,"x",5,null],"truncated":true,"stale":true}}""".trimMargin()
        val u = checkNotNull(PilotWire.parseState(Json.parseToJsonElement(rc) as JsonObject, 0L)).routeCheck!!
        assertEquals(listOf(3, 5), u.unchecked)
        assertTrue(u.truncated)
        assertTrue(u.stale)
        val plain = """{"owner":"pilot","route_check":{"state":"feasible","required_m":0.577,"unverified_legs":[]}}"""
        val p = checkNotNull(PilotWire.parseState(Json.parseToJsonElement(plain) as JsonObject, 0L)).routeCheck!!
        assertEquals(emptyList<Int>(), p.unchecked)
        assertFalse(p.truncated)
        assertFalse(p.stale)
    }

    @Test
    fun `the rc declaration and the link-loss window parse, a pilot without them reads present`() {
        val absent = """{"owner":"pilot","rc_receiver":"absent","link_loss_s":12}"""
        val a = checkNotNull(PilotWire.parseState(Json.parseToJsonElement(absent) as JsonObject, 0L))
        assertEquals(RcReceiver.Absent, a.rcReceiver)
        assertTrue(a.noRc)
        assertEquals(12f, a.linkLossS)
        // The golden frame carries the link-loss window now; a pilot without the declaration omits both.
        val bare = checkNotNull(PilotWire.parseState(JsonObject(data - "rc_receiver" - "link_loss_s"), 0L))
        assertEquals(RcReceiver.Present, bare.rcReceiver)
        assertFalse(bare.noRc)
        assertNull(bare.linkLossS)
        val odd = """{"owner":"pilot","rc_receiver":"maybe"}"""
        assertEquals(RcReceiver.Present, checkNotNull(PilotWire.parseState(Json.parseToJsonElement(odd) as JsonObject, 0L)).rcReceiver)
    }

    @Test
    fun `the process instance id is 32 lowercase hex and fresh ids differ`() {
        assertTrue(Regex("[0-9a-f]{32}").matches(PilotWire.PROCESS_SID))
        assertTrue(PilotWire.newSid() != PilotWire.newSid())
    }

    @Test
    fun `the effective profile limits parse, absent on an older pilot or without a horizontal limit`() {
        assertNull(checkNotNull(PilotWire.parseState(data, 0L)).profileLimits)
        fun parse(limits: String) = checkNotNull(PilotWire.parseState(Json.parseToJsonElement("""{"owner":"pilot","profile_limits":$limits}""") as JsonObject, 0L)).profileLimits
        assertEquals(
            ProfileLimits(PilotProfile.Sport, 8f, 1.5f),
            parse("""{"profile":"sport","v_h_mps":8.0,"v_up_mps":3.0,"v_down_mps":2.0,"yaw_dps":150.0,"stop_m":20.1,"margin_m":1.5}"""),
        )
        assertNull(parse("""{"profile":"sport"}"""))
    }

    @Test
    fun `the stick command parses its input age, absent while the sticks do not own`() {
        assertNull(checkNotNull(PilotWire.parseState(data, 0L)).stick)
        val frame = """{"owner":"pilot","stick":{"ask_ne_mps":[1.0,0.0],"v_ne_mps":[0.5,0.0],"vz_mps":0.0,"braking":false,"input_age_ms":140,"input_seq":9}}"""
        assertEquals(StickCommand(140L), checkNotNull(PilotWire.parseState(Json.parseToJsonElement(frame) as JsonObject, 0L)).stick)
    }

    @Test
    fun `the place class action encodes by name and value`() {
        assertEquals(
            """{"op":"action","name":"place_class:confined","src":"android","sid":"$SID"}""",
            PilotWire.action(PilotAction.placeClass(PlaceClass.Confined), "android", SID, null),
        )
    }

    @Test
    fun `the waypoint skip action encodes by name`() {
        assertEquals("""{"op":"action","name":"mission_skip","src":"android","sid":"$SID"}""", PilotWire.action(PilotAction(PilotAction.MISSION_SKIP), "android", SID, null))
    }

    @Test
    fun `client frames encode the pilot wire`() {
        assertEquals(
            """{"op":"input","src":"android","sid":"$SID","session":7,"seq":12,"t_ms":3400,"axes":[0.25,-1.0,0.0,0.5],"buttons":65537}""",
            PilotWire.input("android", SID, 7, 12, 3400, PilotInput(0.25f, -1f, 0f, 0.5f, 65537)),
        )
        assertEquals("""{"op":"release","src":"android","sid":"$SID","session":7}""", PilotWire.release("android", SID, 7))
        assertEquals("""{"op":"session.take","src":"android","sid":"$SID","session":8}""", PilotWire.sessionTake("android", SID, 8))
        assertEquals("""{"op":"hb","src":"android","sid":"$SID"}""", PilotWire.hb("android", SID))
        assertEquals("""{"op":"bye","src":"android","sid":"$SID"}""", PilotWire.bye("android", SID))
        assertEquals("""{"op":"action","name":"pause","src":"android","sid":"$SID"}""", PilotWire.action(PilotAction(PilotAction.PAUSE), "android", SID, null))
        assertEquals(
            """{"op":"action","name":"hand_to_fc","src":"android","sid":"$SID","args":{"mode":"loiter"},"id":"3"}""",
            PilotWire.action(PilotAction(PilotAction.HAND_TO_FC, mapOf("mode" to "loiter")), "android", SID, "3"),
        )
        assertEquals(
            """{"op":"action","name":"launch","src":"android","sid":"$SID","args":{"after":"goto","goto":{"n":1.5,"e":-2,"d":-3},"alt_m":3}}""",
            PilotWire.action(PilotAction(PilotAction.LAUNCH, mapOf("after" to "goto", "goto" to mapOf("n" to 1.5, "e" to -2, "d" to -3), "alt_m" to 3)), "android", SID, null),
        )
    }

    @Test
    fun `the launch sequence carries the steady take-off`() {
        val text = """{"owner":"idle","armed":true,"landed":true,"sequence":{"step":"takeoff","after":"hold","error":null,
            |"liftoff":{"phase":"level","text":"Setting trim","trim":"learned","tilt_deg":1.5}}}""".trimMargin()
        val s = checkNotNull(PilotWire.parseState(Json.parseToJsonElement(text) as JsonObject, 0L)).sequence!!
        assertEquals(com.altnautica.gcs.core.model.Liftoff(com.altnautica.gcs.core.model.LiftoffPhase.Level, "Setting trim", "learned", 1.5f, null), s.liftoff)

        val stopped = """{"owner":"idle","armed":false,"landed":true,"sequence":{"step":"takeoff","after":"hold","error":"liftoff_tipping",
            |"liftoff":{"phase":"stopped","text":"Take-off stopped","detail":"The drone started to tip. Motors off.","trim":"probe","tilt_deg":null}}}""".trimMargin()
        val l = checkNotNull(PilotWire.parseState(Json.parseToJsonElement(stopped) as JsonObject, 0L)).sequence!!.liftoff!!
        assertEquals(com.altnautica.gcs.core.model.LiftoffPhase.Stopped, l.phase)
        assertEquals("Take-off stopped", l.text)
        assertEquals("The drone started to tip. Motors off.", l.detail)
        assertNull(l.tiltDeg)

        val unknown = """{"owner":"idle","armed":true,"landed":true,"sequence":{"step":"takeoff","after":"hold","liftoff":{"phase":"hover"}}}"""
        assertNull(checkNotNull(PilotWire.parseState(Json.parseToJsonElement(unknown) as JsonObject, 0L)).sequence!!.liftoff)
    }

    private companion object {
        const val SID = "0123456789abcdef0123456789abcdef"
    }
}
