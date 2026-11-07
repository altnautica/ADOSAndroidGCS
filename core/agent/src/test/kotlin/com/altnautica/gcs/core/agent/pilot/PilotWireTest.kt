package com.altnautica.gcs.core.agent.pilot

import com.altnautica.gcs.core.model.AvoidMode
import com.altnautica.gcs.core.model.AvoidState
import com.altnautica.gcs.core.model.ControlMode
import com.altnautica.gcs.core.model.InputSession
import com.altnautica.gcs.core.model.MissionState
import com.altnautica.gcs.core.model.PilotAction
import com.altnautica.gcs.core.model.PilotInput
import com.altnautica.gcs.core.model.PilotOwner
import com.altnautica.gcs.core.model.PilotProfile
import com.altnautica.gcs.core.model.PilotSkillRef
import com.altnautica.gcs.core.model.PilotVehicleClass
import com.altnautica.gcs.core.model.ReadinessSeverity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
        assertTrue(s.armed)
        assertFalse(s.landed)
        assertEquals("mc", s.inputSrc)
        assertEquals(40L, s.inputAgeMs)
        assertNull(s.holdReason)
        assertEquals(AvoidState.Detour, s.avoidState)
        assertEquals(2.4f, s.minClearanceM!!, 1e-6f)
        assertEquals(listOf(12.0f, -3.5f, -4.0f), s.goalNed)
        assertEquals(listOf(10.0f to -2.0f, 10.8f to -2.6f, 11.5f to -3.1f, 12.0f to -3.5f), s.planNed)
        assertEquals(listOf(10.8f, -2.6f, -4.0f), s.setpointNed)
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
        assertEquals(InputSession("mc", "Mission Control"), s.inputSession)
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
    fun `client frames encode the pilot wire`() {
        assertEquals(
            """{"op":"input","src":"android","session":7,"seq":12,"t_ms":3400,"axes":[0.25,-1.0,0.0,0.5],"buttons":65537}""",
            PilotWire.input("android", 7, 12, 3400, PilotInput(0.25f, -1f, 0f, 0.5f, 65537)),
        )
        assertEquals("""{"op":"release","src":"android","session":7}""", PilotWire.release("android", 7))
        assertEquals("""{"op":"session.take","src":"android","session":8}""", PilotWire.sessionTake("android", 8))
        assertEquals("""{"op":"hb","src":"android"}""", PilotWire.hb("android"))
        assertEquals("""{"op":"action","name":"pause","src":"android"}""", PilotWire.action(PilotAction(PilotAction.PAUSE), "android", null))
        assertEquals(
            """{"op":"action","name":"hand_to_fc","src":"android","args":{"mode":"loiter"},"id":"3"}""",
            PilotWire.action(PilotAction(PilotAction.HAND_TO_FC, mapOf("mode" to "loiter")), "android", "3"),
        )
        assertEquals(
            """{"op":"action","name":"launch","src":"android","args":{"after":"goto","goto":{"n":1.5,"e":-2,"d":-3},"alt_m":3}}""",
            PilotWire.action(PilotAction(PilotAction.LAUNCH, mapOf("after" to "goto", "goto" to mapOf("n" to 1.5, "e" to -2, "d" to -3), "alt_m" to 3)), "android", null),
        )
    }
}
