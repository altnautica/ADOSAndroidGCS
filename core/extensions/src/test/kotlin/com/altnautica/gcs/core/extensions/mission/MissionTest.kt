package com.altnautica.gcs.core.extensions.mission

import com.altnautica.gcs.core.extensions.NODE
import com.altnautica.gcs.core.link.mission.MissionItem
import com.altnautica.gcs.core.link.mission.MissionPlan
import com.altnautica.gcs.core.link.mission.MissionResult
import com.altnautica.gcs.core.link.mission.MissionService
import com.altnautica.gcs.core.link.mission.MissionType
import com.altnautica.gcs.core.link.services.VehicleServices
import com.altnautica.gcs.core.link.services.VehicleServicesProvider
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.FlightMode
import com.altnautica.gcs.core.model.HeartbeatState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MissionTest {

    private val payload = """[
      {"id":"a","lat":12.5,"lon":77.5,"alt":30,"command":"TAKEOFF","speed":5},
      {"id":"b","lat":12.6,"lon":77.6,"alt":40,"holdTime":3,"frame":"absolute",
       "actions":[{"id":"j","command":"DO_JUMP","jumpTargetId":"a","param2":2},
                  {"id":"x","command":"DO_JUMP","jumpTargetId":"missing"}]},
      {"id":"c","lat":12.7,"lon":77.7,"alt":50,"speed":8}
    ]"""

    @Test
    fun sanitizeRejectsBadInput() {
        assertTrue(MissionCodec.sanitize(Json.parseToJsonElement("[]")).isFailure)
        val bad = MissionCodec.sanitize(Json.parseToJsonElement("""[{"id":"a","lat":95,"lon":0,"alt":1}]"""))
        assertTrue(bad.exceptionOrNull()!!.message!!.contains("out of range"))
        val cmd = MissionCodec.sanitize(Json.parseToJsonElement("""[{"id":"a","lat":1,"lon":1,"alt":1,"command":"ARM"}]"""))
        assertTrue(cmd.exceptionOrNull()!!.message!!.contains("not permitted"))
    }

    @Test
    fun expandAddsSpeedItemsAndResolvesJumps() {
        val wps = MissionCodec.sanitize(Json.parseToJsonElement(payload)).getOrThrow()
        val items = MissionCodec.expand(wps)
        assertEquals(listOf(178, 22, 16, 177, 178, 16), items.map { it.command })
        assertEquals(5f, items[0].param2)
        assertEquals(0f, items[3].param1) // jump to "a" lands on its opening speed item
        assertEquals(MissionCodec.FRAME_GLOBAL_INT, items[2].frame)
        assertEquals(3f, items[2].param1)
        assertEquals(125000000, items[1].x)
    }

    @Test
    fun collapseRoundTripsWaypoints() {
        val items = MissionCodec.expand(MissionCodec.sanitize(Json.parseToJsonElement(payload)).getOrThrow())
        val wps = MissionCodec.collapse(items)
        assertEquals(3, wps.size)
        val first = wps[0].jsonObject
        assertEquals("TAKEOFF", first["command"]!!.jsonPrimitive.content)
        assertEquals(5.0, first["speed"]!!.jsonPrimitive.content.toDouble(), 1e-6)
        val jump = (wps[1].jsonObject["actions"] as JsonArray)[0].jsonObject
        assertEquals("wp-1", jump["jumpTargetId"]!!.jsonPrimitive.content)
        assertEquals("absolute", wps[1].jsonObject["frame"]!!.jsonPrimitive.content)
    }

    private fun port(armed: Boolean?, mission: MissionService): VehicleMissionPort {
        val hb = MutableStateFlow(armed?.let { HeartbeatState(it, FlightMode(0, "STABILIZE"), 2, 3, 0) })
        val services = mockk<VehicleServices>(relaxed = true)
        every { services.mission } returns mission
        every { services.vehicle.telemetry.heartbeat } returns hb
        every { services.vehicle.telemetry.missionProgress } returns MutableStateFlow(null)
        return VehicleMissionPort(object : VehicleServicesProvider {
            override fun services(id: DeviceId) = flowOf(if (id == NODE) services else null)
        })
    }

    private val writeArgs = Json.parseToJsonElement("""{"payload":{"waypoints":$payload}}""")

    @Test
    fun writeUploadsOnlyAfterApprovalWhileDisarmed() = runTest {
        val mission = mockk<MissionService>()
        val uploaded = slot<List<MissionItem>>()
        coEvery { mission.upload(MissionType.Mission, capture(uploaded)) } returns MissionResult.Success(Unit)

        val declined = port(false, mission).write("ext", NODE, writeArgs) { _, _, _ -> false }
        assertEquals("false", declined.jsonObject["ok"]!!.jsonPrimitive.content)
        coVerify(exactly = 0) { mission.upload(any(), any()) }

        val ok = port(false, mission).write("ext", NODE, writeArgs) { _, _, _ -> true }
        assertEquals("true", ok.jsonObject["ok"]!!.jsonPrimitive.content)
        assertEquals(6, uploaded.captured.size)

        val armed = port(true, mission).write("ext", NODE, writeArgs) { _, _, _ -> true }
        assertTrue(armed.jsonObject["error"]!!.jsonPrimitive.content.contains("armed"))
    }

    @Test
    fun readCollapsesTheVehicleMission() = runTest {
        val mission = mockk<MissionService>()
        coEvery { mission.download(MissionType.Mission) } returns MissionResult.Success(
            MissionPlan(null, listOf(MissionItem.global(16, 1.0, 2.0, 10f))),
        )
        val r = port(false, mission).read(NODE).jsonObject
        assertEquals(1, (r["waypoints"] as JsonArray).size)
    }
}
