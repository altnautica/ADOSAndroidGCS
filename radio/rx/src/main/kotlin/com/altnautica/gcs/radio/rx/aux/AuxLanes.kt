package com.altnautica.gcs.radio.rx.aux

import com.altnautica.gcs.radio.rx.auxmux.AuxChannel
import com.altnautica.gcs.radio.rx.auxmux.AuxDecodeResult
import com.altnautica.gcs.radio.rx.auxmux.AuxMuxDecoder
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * The aux lane's writers and readers, both directions, for every drone the
 * phone radio hears. [send] puts one aux-mux frame on the uplink and answers
 * false while the phone cannot transmit.
 *
 * Down: MAVLink and pilot `state` per drone, relay-proxy responses to
 * [relay], config-tunnel frames to [tunnel]. Up: MAVLink frames, pilot
 * requests addressed to a drone's flight controller, relay requests, link
 * feedback and config-tunnel requests.
 */
class AuxLanes(private val send: (ByteArray) -> Boolean) {
    val relay = RelayRequestClient(send)
    val tunnel = ConfigTunnelClient(send)

    private val mavlinkFlows = ConcurrentHashMap<String, MutableSharedFlow<ByteArray>>()
    private val pilotFlows = ConcurrentHashMap<String, MutableSharedFlow<ByteArray>>()

    /** The flight-controller system id each drone's pilot last reported from. */
    private val pilotSysIds = ConcurrentHashMap<String, Int>()

    /** MAVLink bytes received from [deviceId] (whole aux payloads, frames back to back). */
    fun mavlink(deviceId: String): Flow<ByteArray> = flow(mavlinkFlows, deviceId, MAVLINK_BUFFER).asSharedFlow()

    /** `pilot.v1` bodies [deviceId]'s pilot sent down. */
    fun pilot(deviceId: String): Flow<ByteArray> = flow(pilotFlows, deviceId, PILOT_BUFFER).asSharedFlow()

    /** One aux-downlink datagram from [deviceId]; true when it carried MAVLink. */
    fun onAuxDown(deviceId: String, datagram: ByteArray): Boolean {
        val frame = AuxMuxDecoder.decode(datagram) as? AuxDecodeResult.Frame ?: return false
        when (frame.channel) {
            AuxChannel.MAVLINK -> {
                mavlinkFlows[deviceId]?.tryEmit(frame.payload)
                return true
            }
            AuxChannel.RESPONSE -> relay.onResponse(frame.payload)
            AuxChannel.CONFIG_TUNNEL -> tunnel.onFrame(frame.payload)
            AuxChannel.PILOT -> {
                if (frame.payload.size < 2) return false
                val sysId = frame.payload[0].toInt() and 0xFF
                if (sysId != 0) pilotSysIds[deviceId] = sysId
                pilotFlows[deviceId]?.tryEmit(frame.payload.copyOfRange(1, frame.payload.size))
            }
            else -> Unit
        }
        return false
    }

    /**
     * Sends MAVLink frames up, several per aux frame where they fit, never
     * splitting one. False when any part could not go out.
     */
    fun sendMavlink(bytes: ByteArray): Boolean {
        val frames = AuxMuxDecoder.splitMavlinkFrames(bytes).ifEmpty { return false }
        var batch = ByteArray(0)
        var ok = true
        for (f in frames) {
            if (batch.size + f.size > AuxMuxDecoder.MAX_PAYLOAD && batch.isNotEmpty()) {
                ok = sendFrame(AuxChannel.MAVLINK, batch) && ok
                batch = ByteArray(0)
            }
            batch += f
        }
        if (batch.isNotEmpty()) ok = sendFrame(AuxChannel.MAVLINK, batch) && ok
        return ok
    }

    /** One pilot request for [deviceId]'s flight controller (any, until its pilot reported). */
    fun sendPilot(deviceId: String, body: ByteArray): Boolean {
        val payload = ByteArray(body.size + 1)
        payload[0] = (pilotSysIds[deviceId] ?: 0).toByte()
        body.copyInto(payload, 1)
        return sendFrame(AuxChannel.PILOT, payload)
    }

    /** One link-feedback record. */
    fun sendFeedback(record: LinkFeedback): Boolean = sendFrame(AuxChannel.LINK_FEEDBACK, record.encode())

    private fun sendFrame(channel: AuxChannel, payload: ByteArray): Boolean =
        AuxMuxDecoder.encode(channel, payload)?.let(send) ?: false

    private fun flow(map: ConcurrentHashMap<String, MutableSharedFlow<ByteArray>>, id: String, buffer: Int) =
        map.getOrPut(id) { MutableSharedFlow(extraBufferCapacity = buffer, onBufferOverflow = BufferOverflow.DROP_OLDEST) }

    private companion object {
        const val MAVLINK_BUFFER = 256
        const val PILOT_BUFFER = 16
    }
}
