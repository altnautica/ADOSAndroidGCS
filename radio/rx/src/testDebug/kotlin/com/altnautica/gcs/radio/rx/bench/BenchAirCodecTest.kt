package com.altnautica.gcs.radio.rx.bench

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer

/**
 * The bench air's datagrams against wfb-ng's own layouts: the transmit record
 * the phone's transmitter writes (radiotap, then the 802.11 header wfb-ng
 * writes), `UdpTransmitter`'s forwarder header, and the receive record the
 * phone's receiver parses.
 */
class BenchAirCodecTest {

    /** A minimal radiotap header (version 0, length 13), as a transmitter prepends one. */
    private val radiotap = byteArrayOf(0, 0, 13, 0, 0x00, 0x80.toByte(), 0x08, 0, 0x08, 0, 0x37, 0x01, 0x01)
    private val channel = (0x300 shl 8) + 1
    private val packet = byteArrayOf(1, 2, 3, 4, 5)

    @Test
    fun `a transmit record yields its channel and the bare wfb packet`() {
        val record = radiotap + BenchAirCodec.ieee80211Header(channel, 0x1230) + packet
        val (ch, pkt) = BenchAirCodec.fromTxRecord(record)!!
        assertEquals(channel.toLong(), ch)
        assertArrayEquals(packet, pkt)
    }

    @Test
    fun `a record that is not a wfb frame is refused`() {
        val header = BenchAirCodec.ieee80211Header(channel, 0)
        header[10] = 0
        assertNull(BenchAirCodec.fromTxRecord(radiotap + header + packet))
        assertNull(BenchAirCodec.fromTxRecord(radiotap.copyOf(10)))
    }

    @Test
    fun `the 802_11 header carries the channel in both MAC fields and the sequence`() {
        val h = BenchAirCodec.ieee80211Header(channel, 0x1230)
        assertEquals(24, h.size)
        assertEquals(channel, ByteBuffer.wrap(h, 12, 4).int)
        assertEquals(channel, ByteBuffer.wrap(h, 18, 4).int)
        assertEquals(0x30, h[22].toInt() and 0xFF)
        assertEquals(0x12, h[23].toInt() and 0xFF)
    }

    @Test
    fun `the forwarder header matches wfb-ng's wrxfwd_t layout`() {
        val h = BenchAirCodec.fwdHeader(mcs = 1, bandwidthMhz = 20, freqMhz = 5805)
        assertEquals(17, h.size)
        assertEquals(0, h[1].toInt()) // first antenna
        assertEquals(-1, h[2].toInt()) // unused antenna slots are 0xff
        assertEquals(-42, h[5].toInt())
        assertEquals(-70, h[9].toInt())
        assertEquals(5805, ((h[13].toInt() and 0xFF) shl 8) or (h[14].toInt() and 0xFF))
        assertEquals(1, h[15].toInt())
        assertEquals(20, h[16].toInt())
    }

    @Test
    fun `a relay datagram names the sender after the channel`() {
        val fwd = BenchAirCodec.fwdHeader(1, 20, 5805)
        val d = BenchAirCodec.toRelay(channel.toLong(), "phone", fwd, packet)
        assertEquals(channel, ByteBuffer.wrap(d, 0, 4).int)
        assertEquals(5, d[4].toInt())
        assertEquals("phone", String(d, 5, 5, Charsets.UTF_8))
        assertArrayEquals(fwd + packet, d.copyOfRange(10, d.size))
    }

    @Test
    fun `a datagram from the relay becomes the receive record a dongle would deliver`() {
        val fwd = BenchAirCodec.fwdHeader(1, 20, 5805)
        val datagram = ByteBuffer.allocate(4).putInt(channel).array() + fwd + packet
        val body = BenchAirCodec.toRxRecord(datagram, seq = 16)!!
        assertEquals(1, body[0].toInt()) // one antenna
        assertEquals(-42, body[1].toInt()) // RSSI
        assertEquals(28, body[2].toInt()) // SNR = RSSI - noise
        assertArrayEquals(BenchAirCodec.ieee80211Header(channel, 16), body.copyOfRange(3, 27))
        assertArrayEquals(packet, body.copyOfRange(27, body.size))
        assertNull(BenchAirCodec.toRxRecord(datagram.copyOf(20), seq = 0))
    }
}
