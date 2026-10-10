package com.altnautica.gcs.core.link.mission

import com.altnautica.gcs.core.link.command.CommandService
import com.altnautica.gcs.core.link.mavlink.Inbox
import com.altnautica.gcs.core.link.mavlink.MavlinkLink
import com.altnautica.gcs.core.link.mavlink.VehicleTarget
import com.altnautica.gcs.core.link.mavlink.withInbox
import com.altnautica.gcs.core.mavlink.enums.MavCmd
import com.altnautica.gcs.core.mavlink.enums.MavFrame
import com.altnautica.gcs.core.mavlink.enums.MavMissionResult
import com.altnautica.gcs.core.mavlink.messages.MavMessage
import com.altnautica.gcs.core.mavlink.messages.MissionAck
import com.altnautica.gcs.core.mavlink.messages.MissionClearAll
import com.altnautica.gcs.core.mavlink.messages.MissionCount
import com.altnautica.gcs.core.mavlink.messages.MissionItemInt
import com.altnautica.gcs.core.mavlink.messages.MissionRequest
import com.altnautica.gcs.core.mavlink.messages.MissionRequestInt
import com.altnautica.gcs.core.mavlink.messages.MissionRequestList
import com.altnautica.gcs.core.mavlink.modes.FirmwareFamily
import com.altnautica.gcs.core.model.CommandResult
import com.altnautica.gcs.core.model.HomePosition
import com.altnautica.gcs.core.model.WriteResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

/**
 * MAVLink mission protocol (MISSION, FENCE and RALLY via `mission_type`).
 *
 * Upload: `MISSION_COUNT` → the vehicle requests each item with
 * `MISSION_REQUEST_INT` (legacy `MISSION_REQUEST` also served) → final
 * `MISSION_ACK`. Download: `MISSION_REQUEST_LIST` → `MISSION_COUNT` →
 * `MISSION_REQUEST_INT` per item → our `MISSION_ACK`. Every step waits 2 s and
 * retries 5 times. On ArduPilot, MISSION seq 0 is the home position: uploads
 * write a home placeholder there and downloads report it as [MissionPlan.home].
 */
class MavlinkMissionService(
    private val link: MavlinkLink,
    private val commands: CommandService,
    private val home: StateFlow<HomePosition?>,
    private val onMissionCount: (Int) -> Unit = {},
) : MissionService {

    private val _transfer = MutableStateFlow<MissionTransfer>(MissionTransfer.Idle)
    override val transfer: StateFlow<MissionTransfer> = _transfer.asStateFlow()

    private val busy = Mutex()

    override suspend fun upload(type: MissionType, items: List<MissionItem>): MissionResult<Unit> =
        guarded(type, TransferDirection.Upload) { target ->
            val homeSlot = hasHomeSlot(target, type)
            val wire = if (homeSlot) listOf(homePlaceholder()) + items else items
            link.withInbox(::isMissionReply) { inbox -> uploadItems(target, type, wire, inbox) }
                .also { if (it is MissionResult.Success && type == MissionType.Mission) onMissionCount(wire.size) }
        }

    override suspend fun download(type: MissionType): MissionResult<MissionPlan> =
        guarded(type, TransferDirection.Download) { target ->
            val result = link.withInbox(::isMissionReply) { inbox -> downloadItems(target, type, inbox) }
            when (result) {
                is MissionResult.Failure -> result
                is MissionResult.Success -> {
                    val all = result.value
                    if (type == MissionType.Mission) onMissionCount(all.size)
                    if (hasHomeSlot(target, type) && all.isNotEmpty()) {
                        MissionResult.Success(MissionPlan(home = all.first(), items = all.drop(1)))
                    } else {
                        MissionResult.Success(MissionPlan(home = null, items = all))
                    }
                }
            }
        }

    override suspend fun clear(type: MissionType): MissionResult<Unit> =
        guarded(type, TransferDirection.Clear) { target ->
            link.withInbox({ it is MissionAck }) { inbox ->
                repeat(RETRIES + 1) {
                    send(MissionClearAll(target.sysId, target.compId, type.wire))?.let { return@withInbox it }
                    val ack = inbox.receive(ITEM_TIMEOUT_MS) as? MissionAck
                    if (ack != null && ack.missionType == type.wire) return@withInbox ackResult(ack)
                }
                MissionResult.Failure(MissionError.Timeout)
            }.also { if (it is MissionResult.Success && type == MissionType.Mission) onMissionCount(0) }
        }

    override suspend fun setCurrent(index: Int): CommandResult {
        val target = link.target.value ?: return CommandResult.Denied(CommandService.REASON_NO_LINK)
        val seq = if (hasHomeSlot(target, MissionType.Mission)) index + 1 else index
        return commands.long(MavCmd.MAV_CMD_DO_SET_MISSION_CURRENT, seq.toFloat())
    }

    override suspend fun start(): CommandResult = commands.long(MavCmd.MAV_CMD_MISSION_START, 0f, 0f)

    private suspend fun uploadItems(
        target: VehicleTarget,
        type: MissionType,
        wire: List<MissionItem>,
        inbox: Inbox,
    ): MissionResult<Unit> {
        val count = MissionCount(target.sysId, target.compId, wire.size, type.wire)
        send(count)?.let { return it }
        var lastSent: MavMessage = count
        var retries = 0
        var highest = -1
        while (true) {
            val reply = inbox.receive(ITEM_TIMEOUT_MS)
            if (reply == null) {
                if (++retries > RETRIES) return MissionResult.Failure(MissionError.Timeout)
                send(lastSent)?.let { return it }
                continue
            }
            val seq = when (reply) {
                is MissionRequestInt -> reply.seq.takeIf { reply.missionType == type.wire }
                is MissionRequest -> reply.seq.takeIf { reply.missionType == type.wire }
                is MissionAck -> {
                    if (reply.missionType != type.wire) null else return ackResult(reply)
                }
                else -> null
            } ?: continue
            if (seq !in wire.indices) return MissionResult.Failure(MissionError.Rejected(MavMissionResult.MAV_MISSION_INVALID_SEQUENCE))
            retries = 0
            val item = wire[seq].toWire(target, type, seq)
            send(item)?.let { return it }
            lastSent = item
            if (seq > highest) {
                highest = seq
                _transfer.value = MissionTransfer.InProgress(type, TransferDirection.Upload, seq + 1, wire.size)
            }
        }
    }

    private suspend fun downloadItems(target: VehicleTarget, type: MissionType, inbox: Inbox): MissionResult<List<MissionItem>> {
        val list = MissionRequestList(target.sysId, target.compId, type.wire)
        var count: Int? = null
        for (attempt in 0..RETRIES) {
            send(list)?.let { return it }
            val reply = inbox.receive(ITEM_TIMEOUT_MS)
            if (reply is MissionCount && reply.missionType == type.wire) {
                count = reply.count
                break
            }
            if (reply is MissionAck && reply.missionType == type.wire && reply.type != MavMissionResult.MAV_MISSION_ACCEPTED) {
                return ackResult(reply).let { MissionResult.Failure((it as MissionResult.Failure).error) }
            }
        }
        val total = count ?: return MissionResult.Failure(MissionError.Timeout)
        val items = ArrayList<MissionItem>(total)
        _transfer.value = MissionTransfer.InProgress(type, TransferDirection.Download, 0, total)
        for (seq in 0 until total) {
            var got: MissionItemInt? = null
            for (attempt in 0..RETRIES) {
                send(MissionRequestInt(target.sysId, target.compId, seq, type.wire))?.let { return it }
                got = receiveItem(inbox, type, seq)
                if (got != null) break
            }
            val item = got ?: return MissionResult.Failure(MissionError.Timeout)
            items += item.toItem()
            _transfer.value = MissionTransfer.InProgress(type, TransferDirection.Download, seq + 1, total)
        }
        send(MissionAck(target.sysId, target.compId, MavMissionResult.MAV_MISSION_ACCEPTED, type.wire))
        return MissionResult.Success(items)
    }

    private suspend fun receiveItem(inbox: Inbox, type: MissionType, seq: Int): MissionItemInt? =
        withTimeoutOrNull(ITEM_TIMEOUT_MS) {
            var found: MissionItemInt? = null
            while (found == null) {
                val m = inbox.receive()
                if (m is MissionItemInt && m.missionType == type.wire && m.seq == seq) found = m
            }
            found
        }

    private suspend fun <T> guarded(
        type: MissionType,
        direction: TransferDirection,
        block: suspend (VehicleTarget) -> MissionResult<T>,
    ): MissionResult<T> {
        val target = link.target.value ?: return MissionResult.Failure(MissionError.NoLink)
        if (!busy.tryLock()) return MissionResult.Failure(MissionError.Busy)
        try {
            _transfer.value = MissionTransfer.InProgress(type, direction, 0, 0)
            val result = block(target)
            _transfer.value = when (result) {
                is MissionResult.Success -> MissionTransfer.Completed(type, direction)
                is MissionResult.Failure -> MissionTransfer.Failed(type, direction, result.error)
            }
            return result
        } finally {
            busy.unlock()
        }
    }

    /** Null when written; a failure result otherwise. */
    private suspend fun send(message: MavMessage): MissionResult.Failure? = when (val w = link.send(message)) {
        WriteResult.Sent -> null
        WriteResult.ReceiveOnly -> MissionResult.Failure(MissionError.WriteFailed("receiveOnly"))
        WriteResult.NotAuthorized -> MissionResult.Failure(MissionError.WriteFailed("notAuthorized"))
        is WriteResult.Failed -> MissionResult.Failure(MissionError.WriteFailed(w.message))
    }

    private fun ackResult(ack: MissionAck): MissionResult<Unit> =
        if (ack.type == MavMissionResult.MAV_MISSION_ACCEPTED) {
            MissionResult.Success(Unit)
        } else {
            MissionResult.Failure(MissionError.Rejected(ack.type))
        }

    private fun isMissionReply(m: MavMessage): Boolean =
        m is MissionRequestInt || m is MissionRequest || m is MissionAck || m is MissionCount || m is MissionItemInt

    private fun hasHomeSlot(target: VehicleTarget, type: MissionType): Boolean =
        type == MissionType.Mission && target.family != FirmwareFamily.Px4 && target.family != FirmwareFamily.Unknown

    private fun homePlaceholder(): MissionItem {
        val h = home.value
        return MissionItem(
            command = MavCmd.MAV_CMD_NAV_WAYPOINT,
            frame = MavFrame.MAV_FRAME_GLOBAL,
            x = h?.let { Math.round(it.latDeg * 1e7).toInt() } ?: 0,
            y = h?.let { Math.round(it.lonDeg * 1e7).toInt() } ?: 0,
            z = h?.altMslM ?: 0f,
        )
    }

    private fun MissionItem.toWire(target: VehicleTarget, type: MissionType, seq: Int) = MissionItemInt(
        targetSystem = target.sysId,
        targetComponent = target.compId,
        seq = seq,
        frame = frame,
        command = command,
        current = if (seq == 0) 1 else 0,
        autocontinue = if (autocontinue) 1 else 0,
        param1 = param1,
        param2 = param2,
        param3 = param3,
        param4 = param4,
        x = x,
        y = y,
        z = z,
        missionType = type.wire,
    )

    private fun MissionItemInt.toItem() = MissionItem(
        command = command,
        frame = frame,
        param1 = param1,
        param2 = param2,
        param3 = param3,
        param4 = param4,
        x = x,
        y = y,
        z = z,
        autocontinue = autocontinue != 0,
    )

    companion object {
        const val ITEM_TIMEOUT_MS = 2_000L
        const val RETRIES = 5
    }
}
