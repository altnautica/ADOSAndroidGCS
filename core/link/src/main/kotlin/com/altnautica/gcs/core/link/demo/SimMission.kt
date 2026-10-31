package com.altnautica.gcs.core.link.demo

import com.altnautica.gcs.core.mavlink.MavlinkFrame
import com.altnautica.gcs.core.mavlink.enums.MavCmd
import com.altnautica.gcs.core.mavlink.enums.MavFrame
import com.altnautica.gcs.core.mavlink.enums.MavMissionResult
import com.altnautica.gcs.core.mavlink.messages.MissionAck
import com.altnautica.gcs.core.mavlink.messages.MissionClearAll
import com.altnautica.gcs.core.mavlink.messages.MissionCount
import com.altnautica.gcs.core.mavlink.messages.MissionItemInt
import com.altnautica.gcs.core.mavlink.messages.MissionRequestInt
import com.altnautica.gcs.core.mavlink.messages.MissionRequestList

/** The vehicle side of the mission protocol: stored plans per mission type and the upload handshake. */
internal class SimMission(private val sim: SimState) {
    private val missions = mutableMapOf<Int, List<MissionItemInt>>()
    private var upload: Upload? = null

    private class Upload(val type: Int, val count: Int, val items: Array<MissionItemInt?>, var next: Int)

    fun missionFor(type: Int): List<MissionItemInt> =
        missions[type] ?: if (type == MISSION) listOf(homeItem()) else emptyList()

    fun homeItem() = MissionItemInt(
        targetSystem = 255,
        targetComponent = 190,
        seq = 0,
        frame = MavFrame.MAV_FRAME_GLOBAL,
        command = MavCmd.MAV_CMD_NAV_WAYPOINT,
        current = 0,
        autocontinue = 1,
        x = (sim.options.homeLatDeg * 1e7).toInt(),
        y = (sim.options.homeLonDeg * 1e7).toInt(),
        z = sim.options.homeAltMslM,
        missionType = MISSION,
    )

    fun onRequestList(frame: MavlinkFrame, msg: MissionRequestList) =
        sim.send(MissionCount(frame.sysId, frame.compId, missionFor(msg.missionType).size, msg.missionType))

    fun onClearAll(frame: MavlinkFrame, msg: MissionClearAll) {
        missions[msg.missionType] = if (msg.missionType == MISSION) listOf(homeItem()) else emptyList()
        sim.currentSeq = 1
        sim.send(MissionAck(frame.sysId, frame.compId, MavMissionResult.MAV_MISSION_ACCEPTED, msg.missionType))
    }

    fun onCount(frame: MavlinkFrame, msg: MissionCount) {
        if (msg.count == 0) {
            missions[msg.missionType] = if (msg.missionType == MISSION) listOf(homeItem()) else emptyList()
            sim.send(MissionAck(frame.sysId, frame.compId, MavMissionResult.MAV_MISSION_ACCEPTED, msg.missionType))
            return
        }
        upload = Upload(msg.missionType, msg.count, arrayOfNulls(msg.count), 0)
        sim.send(MissionRequestInt(frame.sysId, frame.compId, 0, msg.missionType))
    }

    fun onItem(frame: MavlinkFrame, msg: MissionItemInt) {
        val u = upload ?: return
        if (msg.missionType != u.type) return
        if (msg.seq != u.next) {
            // Out of order: ask again for the one we need.
            sim.send(MissionRequestInt(frame.sysId, frame.compId, u.next, u.type))
            return
        }
        u.items[msg.seq] = msg
        u.next++
        if (u.next < u.count) {
            sim.send(MissionRequestInt(frame.sysId, frame.compId, u.next, u.type))
        } else {
            val items = u.items.map { it!! }.toMutableList()
            // ArduPilot keeps its own home at seq 0 whatever the GCS wrote there.
            if (u.type == MISSION) items[0] = homeItem()
            missions[u.type] = items
            upload = null
            sim.currentSeq = 1
            sim.send(MissionAck(frame.sysId, frame.compId, MavMissionResult.MAV_MISSION_ACCEPTED, u.type))
        }
    }

    fun sendItem(frame: MavlinkFrame, type: Int, seq: Int) {
        val items = missionFor(type)
        if (seq !in items.indices) {
            sim.send(MissionAck(frame.sysId, frame.compId, MavMissionResult.MAV_MISSION_INVALID_SEQUENCE, type))
            return
        }
        sim.send(
            items[seq].copy(
                targetSystem = frame.sysId,
                targetComponent = frame.compId,
                seq = seq,
                missionType = type,
                current = if (seq == sim.currentSeq) 1 else 0,
            ),
        )
    }

    companion object {
        /** MAV_MISSION_TYPE_MISSION. */
        const val MISSION = 0
    }
}
