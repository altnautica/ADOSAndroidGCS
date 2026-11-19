package com.altnautica.gcs.radio.rx.aux

import kotlin.math.roundToInt

/**
 * What the phone measured of one drone's downlink in the last second, sent
 * up so the drone's bitrate ladder steps on the loss the receiver saw. The
 * uplink reaches the whole fleet, so [targetSlot] names the drone the record
 * describes; a drone acts only on its own.
 *
 * Wire form: a fixed 21-byte big-endian record, version 2: version, flags
 * (bit 0: a real measurement), loss in hundredths of a percent (u16), RSSI
 * dBm (i16), SNR in tenths of a dB (i16), packets received (u32), FEC
 * blocks lost (u32), delivered kbps (u32), target slot (u8). Out-of-range
 * inputs clamp to the representable edge rather than wrap.
 */
data class LinkFeedback(
    val lossPercent: Double,
    val rssiDbm: Double,
    val snrDb: Double,
    val packetsReceived: Long,
    val fecFailed: Long,
    val bitrateKbps: Long,
    val hasMeasurement: Boolean,
    val targetSlot: Int,
) {
    fun encode(): ByteArray {
        val out = ByteArray(LEN)
        out[0] = VERSION.toByte()
        out[1] = (if (hasMeasurement) FLAG_HAS_MEASUREMENT else 0).toByte()
        putU16(out, 2, (lossPercent.coerceIn(0.0, 100.0) * 100.0).roundHalfAway())
        putU16(out, 4, rssiDbm.coerceIn(-32768.0, 32767.0).roundHalfAway())
        putU16(out, 6, (snrDb.coerceIn(-3276.8, 3276.7) * 10.0).roundHalfAway())
        putU32(out, 8, packetsReceived)
        putU32(out, 12, fecFailed)
        putU32(out, 16, bitrateKbps)
        out[20] = targetSlot.toByte()
        return out
    }

    companion object {
        const val VERSION = 2
        const val LEN = 21
        const val FLAG_HAS_MEASUREMENT = 1

        /** One record a second, the drone ladder's own tick. */
        const val INTERVAL_MS = 1_000L

        /**
         * The record for one second of one drone's video plane: [delivered]
         * data packets and [lost] beyond-FEC losses. No packet decoded means
         * "listening and heard nothing", never "a clean link".
         */
        fun of(
            delivered: Long,
            lost: Long,
            rssiDbm: Int?,
            snrDb: Int?,
            bytesDelivered: Long,
            targetSlot: Int,
        ): LinkFeedback {
            val measured = delivered > 0
            val denominator = delivered + lost
            return LinkFeedback(
                lossPercent = if (measured && denominator > 0) lost * 100.0 / denominator else 0.0,
                rssiDbm = rssiDbm?.toDouble() ?: NO_SIGNAL_DBM,
                snrDb = snrDb?.toDouble() ?: 0.0,
                packetsReceived = delivered,
                fecFailed = lost,
                bitrateKbps = bytesDelivered * 8 / 1000,
                hasMeasurement = measured,
                targetSlot = targetSlot,
            )
        }

        private const val NO_SIGNAL_DBM = -100.0

        /** Rust's `f64::round`: halves away from zero. */
        private fun Double.roundHalfAway(): Int = if (this < 0) -((-this).roundToInt()) else roundToInt()

        private fun putU16(b: ByteArray, at: Int, v: Int) {
            b[at] = (v ushr 8).toByte()
            b[at + 1] = v.toByte()
        }

        private fun putU32(b: ByteArray, at: Int, v: Long) {
            val c = v.coerceIn(0, 0xFFFF_FFFFL)
            for (i in 0 until 4) b[at + i] = (c ushr (24 - 8 * i)).toByte()
        }
    }
}
