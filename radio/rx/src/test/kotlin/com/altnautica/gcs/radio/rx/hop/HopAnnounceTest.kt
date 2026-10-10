package com.altnautica.gcs.radio.rx.hop

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Vectors mirror the agent's hop-supervisor tests, plus fixed known-answer bytes. */
class HopAnnounceTest {

    private val coldKey = HopAnnounce.derivePairKey(null)

    @Test
    fun `cold start pair key is the pinned constant`() {
        assertEquals("a86908c2e9225bbd541ff25a12ea95c5f77da32fd2abc2977089155e4c0d9ce8", coldKey.hex())
    }

    @Test
    fun `bound pair key hashes the label and drone key`() {
        val bound = HopAnnounce.derivePairKey(ByteArray(64) { 0xAB.toByte() })
        assertEquals("9fead6743e5b4af31090f3e7d91ec5955dd59f7a378179b01130f98b02133329", bound.hex())
        assertNotEquals(coldKey.hex(), HopAnnounce.derivePairKey(ByteArray(64)).hex())
    }

    @Test
    fun `known answer packet`() {
        val expected = "41443035484f5031030000000000000bb89d015b36fd6c75d0537077fabb809e27dc1058558cce2926ce5f261cc46fb6666b52"
        assertEquals(expected, HopAnnounce.build(3000, 157, HopTrigger.REACTIVE, coldKey).hex())
        assertEquals(HopAnnounce(3000, 157, HopTrigger.REACTIVE), HopAnnounce.parse(expected.unhex(), coldKey))
    }

    @Test
    fun `announce is 51 bytes with magic, version, channel and trigger`() {
        val pkt = HopAnnounce.build(0, 36, HopTrigger.REACTIVE, coldKey)
        assertEquals(51, pkt.size)
        assertArrayEquals("AD05HOP1".toByteArray(), pkt.copyOfRange(0, 8))
        assertEquals(3, pkt[8].toInt())
        assertEquals(36, pkt[17].toInt())
        assertEquals(1, pkt[18].toInt())
    }

    @Test
    fun `verifies with the same key and fails with another`() {
        val pkt = HopAnnounce.build(999_000, 149, HopTrigger.PERIODIC, coldKey)
        assertTrue(HopAnnounce.verify(pkt, coldKey))
        assertFalse(HopAnnounce.verify(pkt, HopAnnounce.derivePairKey(ByteArray(64) { 0xAB.toByte() })))
    }

    @Test
    fun `delay is big endian`() {
        val delay = 0x0102_0304_0506_0708L
        val pkt = HopAnnounce.build(delay, 0, HopTrigger.PERIODIC, coldKey)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), pkt.copyOfRange(9, 17))
        assertEquals(delay, HopAnnounce.parse(pkt, coldKey)!!.delayMs)
    }

    @Test
    fun `a wall clock epoch announce is refused`() {
        val pkt = HopAnnounce.build(500, 149, HopTrigger.PERIODIC, coldKey)
        pkt[8] = 2
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(coldKey, "HmacSHA256")) }
        mac.doFinal(pkt.copyOfRange(0, 19)).copyInto(pkt, 19)
        assertFalse(HopAnnounce.verify(pkt, coldKey))
        assertNull(HopAnnounce.parse(pkt, coldKey))
    }

    @Test
    fun `parse returns channel and trigger`() {
        assertEquals(HopAnnounce(0, 153, HopTrigger.PERIODIC), HopAnnounce.parse(HopAnnounce.build(0, 153, HopTrigger.PERIODIC, coldKey), coldKey))
        assertEquals(HopTrigger.REACTIVE, HopAnnounce.parse(HopAnnounce.build(0, 161, HopTrigger.REACTIVE, coldKey), coldKey)!!.trigger)
        assertEquals(HopTrigger.MANUAL, HopAnnounce.parse(HopAnnounce.build(0, 153, HopTrigger.MANUAL, coldKey), coldKey)!!.trigger)
        assertNull(HopAnnounce.parse(HopAnnounce.build(0, 153, HopTrigger.PERIODIC, coldKey), HopAnnounce.derivePairKey(ByteArray(64) { 2 })))
    }

    @Test
    fun `an unknown trigger byte reads as periodic`() {
        assertEquals(HopTrigger.PERIODIC, HopTrigger.fromByte(7))
    }

    @Test
    fun `a 68 byte presence beacon does not parse as an announce`() {
        val presence = ByteArray(68).also { "AD05PRES".toByteArray().copyInto(it) }
        assertNull(HopAnnounce.parse(presence, coldKey))
    }

    @Test
    fun `a tampered channel fails verification`() {
        val pkt = HopAnnounce.build(3000, 157, HopTrigger.PERIODIC, coldKey)
        pkt[17] = 36
        assertNull(HopAnnounce.parse(pkt, coldKey))
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun String.unhex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
