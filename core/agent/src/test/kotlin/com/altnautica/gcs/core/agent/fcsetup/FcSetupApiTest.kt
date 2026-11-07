package com.altnautica.gcs.core.agent.fcsetup

import com.altnautica.gcs.core.agent.AgentClient
import com.altnautica.gcs.core.agent.VersionCache
import com.altnautica.gcs.core.agent.http.AgentEndpoint
import com.altnautica.gcs.core.agent.http.AgentError
import com.altnautica.gcs.core.agent.http.AgentHttp
import com.altnautica.gcs.core.agent.http.AgentResult
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.MissionState
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FcSetupApiTest {

    private val server = MockWebServer()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private fun client() =
        AgentClient(AgentEndpoint(DeviceId("d1"), server.url("/"), "k"), AgentHttp(OkHttpClient()), VersionCache())

    private fun respond(code: Int, body: String) =
        server.enqueue(MockResponse.Builder().code(code).body(body).build())

    @Test
    fun `overview parses wizards steps and report`() = runBlocking {
        respond(200, OVERVIEW)
        val o = client().fcSetup.overview().getOrNull()!!
        assertEquals("/api/v1/fc-setup", server.takeRequest().url.encodedPath)
        assertEquals("0011223344556677", o.uid)
        assertEquals(SetupWizardState.Running, o.wizard("ground")!!.state)
        assertFalse(o.wizard("air")!!.available)
        assertTrue(o.needsGroundSetup)
        val accel = o.wizard("ground")!!.steps.single()
        assertEquals(SetupStepState.WaitingUser, accel.state)
        assertEquals(0.5f, accel.progress)
        assertEquals("accel_level", accel.image)
        assertEquals(SetupConfirm.None, accel.actions[0].confirm)
        assertEquals(SetupActionStyle.Primary, accel.actions[0].style)
        assertEquals("3", accel.live["setup.live.pose"]!!.jsonPrimitive.content)
        assertEquals("2026-11-06T10:00:00Z.param", o.report!!.snapshots.single().file)
    }

    @Test
    fun `step schema parses every confirm style and nulls`() {
        val step = FcSetupApi.parseEvent(
            """{"kind":"step","ts_ms":5,"data":{"wizard":"ground","step":"outputs","title_key":"t","body_key":null,
            "args":{"motor":1},"image":null,"state":"running","progress":null,"live":{},
            "actions":[{"id":"test","label_key":"a","style":"danger","confirm":"hold_props_off"},
            {"id":"x","label_key":"b","style":"secondary","confirm":"hold"}],"blockers":["setup.blocker.armed"],"result":null}}""",
        ) as FcSetupEvent.Step
        assertEquals(5L, step.tsMs)
        assertNull(step.step.progress)
        assertNull(step.step.result)
        assertEquals(SetupConfirm.HoldPropsOff, step.step.actions[0].confirm)
        assertEquals(SetupActionStyle.Danger, step.step.actions[0].style)
        assertEquals(SetupConfirm.Hold, step.step.actions[1].confirm)
        assertEquals(listOf("setup.blocker.armed"), step.step.blockers)
        assertNull(FcSetupApi.parseEvent("""{"kind":"keepalive","ts_ms":1}"""))
        assertTrue(FcSetupApi.parseEvent("""{"kind":"wizard","ts_ms":1,"data":{"id":"air","state":"done"}}""") is FcSetupEvent.Wizard)
    }

    @Test
    fun `start action run abort and revert hit their routes`() = runBlocking {
        val api = client().fcSetup
        respond(200, """{"ok":true,"wizard":{"id":"ground","state":"running"}}""")
        assertEquals(SetupWizardState.Running, api.start("ground").getOrNull()!!.wizard!!.state)
        assertEquals("/api/v1/fc-setup/ground/start", server.takeRequest().url.encodedPath)

        respond(200, """{"ok":true,"step":{"wizard":"ground","step":"battery","title_key":"t","state":"done"}}""")
        api.action("ground", "battery", "apply", buildJsonObject { put("measured_v", 16.4) })
        val action = server.takeRequest()
        assertEquals("/api/v1/fc-setup/ground/steps/battery/actions/apply", action.url.encodedPath)
        assertEquals("""{"args":{"measured_v":16.4}}""", action.body!!.utf8())

        respond(200, """{"ok":true,"step":{"wizard":"ground","step":"compass","title_key":"t"}}""")
        api.runStep("ground", "compass")
        assertEquals("/api/v1/fc-setup/ground/steps/compass/run", server.takeRequest().url.encodedPath)

        respond(200, """{"ok":true,"wizard":{"id":"air","state":"aborted"}}""")
        api.abort("air")
        assertEquals("/api/v1/fc-setup/air/abort", server.takeRequest().url.encodedPath)

        respond(200, """{"ok":true,"restored":812}""")
        assertEquals(812, api.revert("a.param").getOrNull()!!.restored)
        val revert = server.takeRequest()
        assertEquals("/api/v1/fc-setup/revert", revert.url.encodedPath)
        assertEquals("""{"snapshot":"a.param"}""", revert.body!!.utf8())
    }

    @Test
    fun `refusal is an error result`() = runBlocking {
        respond(409, """{"ok":false,"err":"setup.err.armed"}""")
        val result = client().fcSetup.start("air")
        assertTrue(result is AgentResult.Err && result.error !is AgentError.Network)
    }

    @Test
    fun `mission mirror parses status and items`() = runBlocking {
        respond(
            200,
            """{"rev":3,"count":3,"home_slot":true,"uploads_seen":2,
            "mission":{"state":"active","seq":1,"total":2,"item_reached":null,"id":0,"source":"mission_state","age_ms":200,"landing_seq":false},
            "items":[{"param1":0,"param2":0,"param3":0,"param4":0,"x":473977000,"y":85455000,"z":10,"seq":1,"command":16,"frame":3,"current":0,"autocontinue":1,"mission_type":0}]}""",
        )
        val m = client().mission.mirror().getOrNull()!!
        assertEquals("/api/v1/mission", server.takeRequest().url.encodedPath)
        assertEquals(MissionState.Active, m.status!!.state)
        assertEquals(2L, m.status!!.uploadsSeen)
        assertTrue(m.status!!.fromMissionState)
        assertEquals(473977000, m.items.single().x)
    }

    private companion object {
        val OVERVIEW = """
            {"v":1,"uid":"0011223344556677","firmware":"ardupilot","firmware_version":"4.5.7","vehicle_class":"multirotor",
             "running":{"wizard":"ground","step":"accel"},
             "wizards":[
              {"id":"ground","state":"running","available":true,"blockers":[],"completed_at":null,
               "steps":[{"wizard":"ground","step":"accel","title_key":"setup.accel.title","body_key":"setup.accel.body",
                 "args":{},"image":"accel_level","state":"waiting_user","progress":0.5,"live":{"setup.live.pose":3},
                 "actions":[{"id":"next_pose","label_key":"setup.accel.next","style":"primary","confirm":"none"}],
                 "blockers":[],"result":null}]},
              {"id":"air","state":"not_started","available":false,"blockers":["setup.blocker.ground_first"],"completed_at":null,"steps":[]}],
             "report":{"date":null,"firmware":"ardupilot","steps":{},"snapshots":[{"file":"2026-11-06T10:00:00Z.param","ts":"2026-11-06T10:00:00Z"}]}}
        """.trimIndent()
    }
}
