package com.altnautica.gcs.core.link.mission

import com.altnautica.gcs.core.link.command.CommandService
import com.altnautica.gcs.core.link.testing.ScriptedLink
import com.altnautica.gcs.core.link.testing.demoHarness
import com.altnautica.gcs.core.mavlink.enums.MavCmd
import com.altnautica.gcs.core.mavlink.enums.MavFrame
import com.altnautica.gcs.core.mavlink.enums.MavMissionResult
import com.altnautica.gcs.core.mavlink.messages.MissionAck
import com.altnautica.gcs.core.mavlink.messages.MissionCount
import com.altnautica.gcs.core.mavlink.messages.MissionItemInt
import com.altnautica.gcs.core.mavlink.messages.MissionRequestInt
import com.altnautica.gcs.core.model.HomePosition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MissionServiceTest {

    private val items = listOf(
        MissionItem(command = MavCmd.MAV_CMD_NAV_TAKEOFF, z = 20f),
        MissionItem.global(MavCmd.MAV_CMD_NAV_WAYPOINT, -35.3620, 149.1660, 30f, param1 = 2f),
        MissionItem.global(MavCmd.MAV_CMD_NAV_WAYPOINT, -35.3610, 149.1650, 35f),
    )

    private fun service(link: com.altnautica.gcs.core.link.mavlink.MavlinkLink, scope: CoroutineScope) =
        MavlinkMissionService(link, CommandService(scope, link, MutableStateFlow(null), MutableStateFlow(null)), MutableStateFlow(null))

    @Test
    fun `upload then download round-trips through the simulated vehicle`() = runTest {
        val h = demoHarness()
        val mission = service(h.link, backgroundScope)
        assertEquals(MissionResult.Success(Unit), mission.upload(MissionType.Mission, items))
        assertEquals(MissionTransfer.Completed(MissionType.Mission, TransferDirection.Upload), mission.transfer.value)

        val download = mission.download(MissionType.Mission)
        assertTrue(download is MissionResult.Success)
        val plan = (download as MissionResult.Success).value
        assertEquals(items, plan.items)
        // Seq 0 is the vehicle's home, reported separately and never the takeoff item.
        val home = assertNotNullAndGet(plan.home)
        assertEquals(MavCmd.MAV_CMD_NAV_WAYPOINT, home.command)
        assertEquals(-35.363261, home.latDeg, 1e-6)
    }

    @Test
    fun `upload writes a home placeholder at seq 0 and the takeoff at seq 1`() = runTest {
        val received = mutableListOf<MissionItemInt>()
        var count = 0
        val link = ScriptedLink { m ->
            when (m) {
                is MissionCount -> {
                    count = m.count
                    reply(MissionRequestInt(255, 190, 0, m.missionType))
                }
                is MissionItemInt -> {
                    received += m
                    if (m.seq + 1 < count) {
                        reply(MissionRequestInt(255, 190, m.seq + 1, m.missionType))
                    } else {
                        reply(MissionAck(255, 190, MavMissionResult.MAV_MISSION_ACCEPTED, m.missionType))
                    }
                }
                else -> Unit
            }
        }
        val home = MutableStateFlow(HomePosition(-35.0, 149.0, 600f, 0))
        val mission = MavlinkMissionService(link, CommandService(backgroundScope, link, MutableStateFlow(null), home), home)
        assertEquals(MissionResult.Success(Unit), mission.upload(MissionType.Mission, items))
        assertEquals(4, count)
        assertEquals(listOf(0, 1, 2, 3), received.map { it.seq })
        assertEquals(MavFrame.MAV_FRAME_GLOBAL, received[0].frame)
        assertEquals(-350_000_000, received[0].x)
        assertEquals(MavCmd.MAV_CMD_NAV_TAKEOFF, received[1].command)
    }

    @Test
    fun `fence uses mission_type and has no home slot`() = runTest {
        val h = demoHarness()
        val mission = service(h.link, backgroundScope)
        val fence = listOf(
            MissionItem.global(MavCmd.MAV_CMD_NAV_FENCE_CIRCLE_INCLUSION, -35.3632, 149.1652, 0f, param1 = 150f, frame = MavFrame.MAV_FRAME_GLOBAL_INT),
        )
        assertEquals(MissionResult.Success(Unit), mission.upload(MissionType.Fence, fence))
        val plan = (mission.download(MissionType.Fence) as MissionResult.Success).value
        assertEquals(null, plan.home)
        assertEquals(fence, plan.items)
        // The mission list is untouched by the fence upload.
        assertEquals(emptyList<MissionItem>(), (mission.download(MissionType.Mission) as MissionResult.Success).value.items)
    }

    @Test
    fun `silent vehicle fails after five retries`() = runTest {
        val link = ScriptedLink()
        val mission = service(link, backgroundScope)
        val start = testScheduler.currentTime
        assertEquals(MissionResult.Failure(MissionError.Timeout), mission.upload(MissionType.Mission, items))
        assertEquals(6, link.sent.count { it is MissionCount })
        assertEquals(6 * MavlinkMissionService.ITEM_TIMEOUT_MS, testScheduler.currentTime - start)
    }

    @Test
    fun `rejected upload reports the mission result code`() = runTest {
        val link = ScriptedLink { m ->
            if (m is MissionCount) reply(MissionAck(255, 190, MavMissionResult.MAV_MISSION_NO_SPACE, m.missionType))
        }
        val r = service(link, backgroundScope).upload(MissionType.Mission, items)
        assertEquals(MissionResult.Failure(MissionError.Rejected(MavMissionResult.MAV_MISSION_NO_SPACE)), r)
    }

    @Test
    fun `clear empties the stored mission`() = runTest {
        val h = demoHarness()
        val mission = service(h.link, backgroundScope)
        mission.upload(MissionType.Mission, items)
        assertEquals(MissionResult.Success(Unit), mission.clear(MissionType.Mission))
        assertEquals(emptyList<MissionItem>(), (mission.download(MissionType.Mission) as MissionResult.Success).value.items)
    }

    private fun <T : Any> assertNotNullAndGet(v: T?): T {
        assertNotNull(v)
        return v!!
    }
}
