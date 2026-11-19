package com.altnautica.gcs.radio.rx.bench

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The bytes the fleet bench's emulated air carries, and their translation to
 * and from the phone radio's own pipe records. Debug builds only.
 *
 * The relay moves wfb-ng's UDP-emulation datagrams, which carry no 802.11
 * header: a 17-byte forwarder header, then the wfb packet. A real receiver
 * finds the frame's `channel_id` in the 802.11 MAC fields; on the bench the
 * relay routes on it instead, so a datagram names its channel beside the
 * payload.
 *
 * - Transmit record (the radio's TX pipe, after the length):
 *   `radiotap header | 802.11 header (24) | wfb packet`.
 * - To the relay (UDP 15801): `u32 channel_id | u8 name_len | name | fwd header | wfb packet`.
 * - From the relay (UDP 15802): `u32 channel_id | fwd header | wfb packet`.
 * - Receive record (the radio's frames pipe, after the length):
 *   `u8 antennas | i8 rssi x n | i8 snr x n | 802.11 header | wfb packet`.
 */
object BenchAirCodec {
    /** `wrxfwd_t`: wlan index, 4 antenna ids, 4 RSSI, 4 noise, u16 freq, MCS, bandwidth. */
    const val FWD_HEADER_LEN = 17
    const val IEEE80211_HEADER_LEN = 24
    private const val SEQ_OFFSET = 22
    private const val MAC_CHANNEL_OFFSETS_SRC = 12
    private const val MAC_CHANNEL_OFFSETS_DST = 18

    /** The radio's received signal on the bench, as wfb-ng's UDP transmitter reports it. */
    private const val BENCH_RSSI_DBM: Byte = -42
    private const val BENCH_NOISE_DBM: Byte = -70

    /** A transmit record's channel and wfb packet, or null when the record is malformed. */
    fun fromTxRecord(record: ByteArray, length: Int = record.size): Pair<Long, ByteArray>? {
        if (length < 4) return null
        val radiotapLen = (record[2].toInt() and 0xFF) or ((record[3].toInt() and 0xFF) shl 8)
        val frame = radiotapLen
        if (radiotapLen < 8 || length < frame + IEEE80211_HEADER_LEN) return null
        if (record[frame + 10] != 0x57.toByte() || record[frame + 11] != 0x42.toByte()) return null
        val channel = ByteBuffer.wrap(record, frame + MAC_CHANNEL_OFFSETS_SRC, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFF_FFFFL
        return channel to record.copyOfRange(frame + IEEE80211_HEADER_LEN, length)
    }

    /** The forwarder header wfb-ng's UDP transmitter puts in front of every packet (one antenna). */
    fun fwdHeader(mcs: Int, bandwidthMhz: Int, freqMhz: Int): ByteArray {
        val h = ByteArray(FWD_HEADER_LEN)
        h[0] = 0 // wlan index
        h.fill(0xFF.toByte(), 1, 5) // antenna ids: unused
        h[1] = 0
        h.fill(Byte.MIN_VALUE, 5, 9) // RSSI: unused
        h[5] = BENCH_RSSI_DBM
        h.fill(Byte.MAX_VALUE, 9, 13) // noise: unused
        h[9] = BENCH_NOISE_DBM
        h[13] = (freqMhz shr 8).toByte()
        h[14] = freqMhz.toByte()
        h[15] = mcs.toByte()
        h[16] = bandwidthMhz.toByte()
        return h
    }

    /** One datagram to the relay's data port. */
    fun toRelay(channel: Long, node: String, fwd: ByteArray, packet: ByteArray): ByteArray {
        val name = node.toByteArray(Charsets.UTF_8)
        require(name.size <= 255) { "node name too long" }
        return ByteBuffer.allocate(4 + 1 + name.size + fwd.size + packet.size)
            .putInt(channel.toInt())
            .put(name.size.toByte())
            .put(name)
            .put(fwd)
            .put(packet)
            .array()
    }

    /**
     * A receive record (without its length prefix) for one datagram from the
     * relay, or null when the datagram is malformed: the forwarder header's
     * first antenna's RSSI and SNR, then the 802.11 header a real radio would
     * have captured, with the channel in both MAC fields.
     */
    fun toRxRecord(datagram: ByteArray, length: Int = datagram.size, seq: Int): ByteArray? {
        if (length < 4 + FWD_HEADER_LEN + 1) return null
        val channel = ByteBuffer.wrap(datagram, 0, 4).order(ByteOrder.BIG_ENDIAN).int
        val fwd = 4
        val rssi = datagram[fwd + 5]
        val snr = (datagram[fwd + 5] - datagram[fwd + 9]).toByte()
        val packet = 4 + FWD_HEADER_LEN
        val body = ByteBuffer.allocate(1 + 2 + IEEE80211_HEADER_LEN + (length - packet))
        body.put(1).put(rssi).put(snr)
        body.put(ieee80211Header(channel, seq))
        body.put(datagram, packet, length - packet)
        return body.array()
    }

    /** The 802.11 header wfb-ng writes: data frame, broadcast receiver, the channel in both MAC addresses. */
    fun ieee80211Header(channel: Int, seq: Int): ByteArray {
        val h = byteArrayOf(
            0x08, 0x01, 0x00, 0x00,
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0x57, 0x42, 0, 0, 0, 0,
            0x57, 0x42, 0, 0, 0, 0,
            0, 0,
        )
        ByteBuffer.wrap(h, MAC_CHANNEL_OFFSETS_SRC, 4).putInt(channel)
        ByteBuffer.wrap(h, MAC_CHANNEL_OFFSETS_DST, 4).putInt(channel)
        h[SEQ_OFFSET] = seq.toByte()
        h[SEQ_OFFSET + 1] = (seq shr 8).toByte()
        return h
    }
}
