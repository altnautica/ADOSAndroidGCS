package com.altnautica.gcs.core.video.rtp

import java.io.ByteArrayOutputStream

/** One decodable picture in Annex-B form (each NAL prefixed `00 00 00 01`). */
class H264AccessUnit(
    val data: ByteArray,
    /** RTP timestamp (90 kHz) of the picture. */
    val rtpTimestamp: Long,
    /** True when the unit carries an IDR slice. */
    val keyFrame: Boolean,
    /** True when the unit holds only SPS/PPS and no slice. */
    val configOnly: Boolean,
)

/**
 * RTP (RFC 3550) to H.264 access units per RFC 6184: single NAL unit packets
 * (types 1-23), STAP-A (24) and FU-A (28).
 *
 * A unit is emitted when the marker bit closes it or the timestamp moves on.
 * After any sequence gap, or before the first picture, nothing is emitted
 * until an IDR arrives: a decoder fed a P-frame without its reference shows
 * smeared garbage, which is worse on a flight display than a held frame.
 * SPS/PPS seen while waiting are kept and emitted with the IDR.
 */
class H264RtpDepacketizer {

    private val current = ByteArrayOutputStream()
    private var currentTs: Long = -1
    private var currentHasIdr = false
    private var currentHasSlice = false
    private var lastSeq = -1
    private var awaitingIdr = true
    private var fuOpen = false
    private var fuBroken = false

    /** Packets that arrived after a gap, counted for link stats. */
    var lossEvents: Long = 0
        private set

    /**
     * Feeds one RTP packet. Returns the access units it completed (usually
     * zero or one).
     */
    fun push(packet: ByteArray, length: Int = packet.size): List<H264AccessUnit> {
        if (length < RTP_HEADER) return emptyList()
        val b0 = packet[0].toInt() and 0xFF
        if (b0 ushr 6 != 2) return emptyList()
        val padding = b0 and 0x20 != 0
        val extension = b0 and 0x10 != 0
        val csrc = b0 and 0x0F
        val marker = packet[1].toInt() and 0x80 != 0
        val seq = ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)
        val ts = ((packet[4].toLong() and 0xFF) shl 24) or ((packet[5].toLong() and 0xFF) shl 16) or
            ((packet[6].toLong() and 0xFF) shl 8) or (packet[7].toLong() and 0xFF)

        var offset = RTP_HEADER + csrc * 4
        if (extension) {
            if (offset + 4 > length) return emptyList()
            val words = ((packet[offset + 2].toInt() and 0xFF) shl 8) or (packet[offset + 3].toInt() and 0xFF)
            offset += 4 + words * 4
        }
        var end = length
        if (padding) end -= packet[length - 1].toInt() and 0xFF
        if (offset >= end) return emptyList()

        val out = ArrayList<H264AccessUnit>(1)
        if (lastSeq >= 0 && seq != (lastSeq + 1) and 0xFFFF) onLoss()
        lastSeq = seq

        if (currentTs != -1L && ts != currentTs) flush(out)
        currentTs = ts

        val nalHeader = packet[offset].toInt() and 0xFF
        when (val type = nalHeader and 0x1F) {
            in 1..23 -> appendNal(packet, offset, end - offset, type)
            STAP_A -> {
                var p = offset + 1
                while (p + 2 <= end) {
                    val size = ((packet[p].toInt() and 0xFF) shl 8) or (packet[p + 1].toInt() and 0xFF)
                    p += 2
                    if (size == 0 || p + size > end) break
                    appendNal(packet, p, size, packet[p].toInt() and 0x1F)
                    p += size
                }
            }
            FU_A -> handleFuA(packet, offset, end, nalHeader)
            else -> Unit
        }
        if (marker) flush(out)
        return out
    }

    private fun handleFuA(packet: ByteArray, offset: Int, end: Int, indicator: Int) {
        if (offset + 2 > end) return
        val fuHeader = packet[offset + 1].toInt() and 0xFF
        val start = fuHeader and 0x80 != 0
        val stop = fuHeader and 0x40 != 0
        val type = fuHeader and 0x1F
        if (start) {
            fuOpen = true
            fuBroken = false
            noteType(type)
            if (accepting(type)) {
                current.write(START_CODE)
                current.write((indicator and 0xE0) or type)
            } else {
                fuBroken = true
            }
        } else if (!fuOpen) {
            return
        }
        if (!fuBroken && accepting(type)) current.write(packet, offset + 2, end - offset - 2)
        if (stop) fuOpen = false
    }

    private fun appendNal(packet: ByteArray, offset: Int, size: Int, type: Int) {
        noteType(type)
        if (!accepting(type)) return
        current.write(START_CODE)
        current.write(packet, offset, size)
    }

    private fun noteType(type: Int) {
        if (type == NAL_IDR) {
            currentHasIdr = true
            awaitingIdr = false
        }
        if (type in 1..5) currentHasSlice = true
    }

    /** While waiting for an IDR only parameter sets and the IDR itself are kept. */
    private fun accepting(type: Int): Boolean =
        !awaitingIdr || type == NAL_IDR || type == NAL_SPS || type == NAL_PPS

    private fun onLoss() {
        lossEvents++
        awaitingIdr = true
        if (fuOpen) fuBroken = true
        if (!currentHasIdr) {
            // The picture under assembly lost a piece: drop its slices but keep
            // parameter sets, which the IDR will need.
            val kept = keepParameterSets(current.toByteArray())
            current.reset()
            current.write(kept)
            currentHasSlice = false
        }
    }

    private fun flush(out: MutableList<H264AccessUnit>) {
        if (current.size() > 0) {
            val configOnly = !currentHasSlice
            if (!awaitingIdr || configOnly) {
                out += H264AccessUnit(current.toByteArray(), currentTs, currentHasIdr, configOnly)
            }
        }
        current.reset()
        currentHasIdr = false
        currentHasSlice = false
        fuOpen = false
        fuBroken = false
    }

    private fun keepParameterSets(annexB: ByteArray): ByteArray {
        val kept = ByteArrayOutputStream()
        for (nal in splitAnnexB(annexB)) {
            val t = nal[0].toInt() and 0x1F
            if (t == NAL_SPS || t == NAL_PPS) {
                kept.write(START_CODE)
                kept.write(nal)
            }
        }
        return kept.toByteArray()
    }

    companion object {
        const val NAL_IDR = 5
        const val NAL_SPS = 7
        const val NAL_PPS = 8
        private const val STAP_A = 24
        private const val FU_A = 28
        private const val RTP_HEADER = 12
        val START_CODE = byteArrayOf(0, 0, 0, 1)

        /** Splits Annex-B data on 4-byte start codes into bare NAL units. */
        fun splitAnnexB(data: ByteArray): List<ByteArray> {
            val starts = ArrayList<Int>()
            var i = 0
            while (i + 3 < data.size) {
                if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1) {
                    starts += i + 4
                    i += 4
                } else {
                    i++
                }
            }
            return starts.mapIndexed { idx, s ->
                val e = if (idx + 1 < starts.size) starts[idx + 1] - 4 else data.size
                data.copyOfRange(s, e)
            }.filter { it.isNotEmpty() }
        }
    }
}
