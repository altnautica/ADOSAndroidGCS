package com.altnautica.gcs.radio.rx.tx

import android.os.ParcelFileDescriptor
import com.altnautica.gcs.radio.rx.WfbTransmitter
import com.altnautica.gcs.radio.rx.invite.InviteBundle
import com.altnautica.gcs.radio.rx.link.LinkParams

/**
 * The parameters of one ground transmitter. The box runs exactly two, with
 * fixed light FEC at 20 MHz under the fleet's ground link id; the phone runs
 * the same two with the same values, so a drone cannot tell them apart.
 */
data class GroundTxParams(
    val linkId: Int,
    val radioPort: Int,
    val k: Int,
    val n: Int,
    val mcsIndex: Int,
    val bandwidthMhz: Int,
) {
    val channelId: Long get() = LinkParams.channelId(linkId, radioPort)

    companion object {
        /** The box's default `mcs_index` when the fleet sets none. */
        const val DEFAULT_MCS = 1
        private const val K = 1
        private const val N = 2
        private const val BANDWIDTH_MHZ = 20

        /** The control plane (port 1) and the aux uplink (port 3) of [bundle]'s fleet. */
        fun of(bundle: InviteBundle): Pair<GroundTxParams, GroundTxParams> {
            val linkId = LinkParams.linkId(bundle.fleetId, LinkParams.SLOT_GROUND)
            val mcs = bundle.mcs ?: DEFAULT_MCS
            return GroundTxParams(linkId, LinkParams.PORT_CONTROL, K, N, mcs, BANDWIDTH_MHZ) to
                GroundTxParams(linkId, LinkParams.PORT_AUX_UP, K, N, mcs, BANDWIDTH_MHZ)
        }
    }
}

/** The phone's two ground transmitters, writing into one radio transmit pipe. */
class GroundTransmitters(pipe: ParcelFileDescriptor, bundle: InviteBundle) : AutoCloseable {
    val control: WfbTransmitter
    val auxUp: WfbTransmitter

    init {
        val (c, a) = GroundTxParams.of(bundle)
        val key = bundle.rxKey
        try {
            control = open(pipe, key, c)
            auxUp = try {
                open(pipe, key, a)
            } catch (e: RuntimeException) {
                control.close()
                throw e
            }
        } finally {
            key.fill(0)
        }
    }

    override fun close() {
        control.close()
        auxUp.close()
    }

    private fun open(pipe: ParcelFileDescriptor, key: ByteArray, p: GroundTxParams) =
        WfbTransmitter(pipe, key, p.linkId, p.radioPort, p.k, p.n, p.mcsIndex, p.bandwidthMhz)
}
