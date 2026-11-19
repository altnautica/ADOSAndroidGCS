package com.altnautica.gcs.radio.rx.uplink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class UplinkMacTest {

    private val key = ByteArray(32) { (0x80 + it).toByte() }

    /**
     * The agent's own output: `ados-control`
     * `routes::ground_station_uplink::tests::the_uplink_mac_vector` printed
     * `uplink mac vector: bb00e6…` for this key, time and body.
     */
    @Test
    fun theMacMatchesTheAgentsVector() {
        val mac = UplinkMac.mac(key, NOW, TAKE.encodeToByteArray())
        assertEquals("bb00e64b4ccd8554d9c7f466b08f7c3546bd6ae53dd395fdeeb1f629820ac163", mac.toHex())
    }

    @Test
    fun aSignedTakeCarriesTheExactBodyAndHeadersTheBoxReads() {
        val phonePub = ByteArray(32) { 7 }
        val req = SignedRequest.take("a1b2c3d4e5f6", "Ops phone", 47811, PhoneAuth(phonePub, key), NOW)
        assertEquals(TAKE, req.body.decodeToString())
        assertEquals(Base64.getEncoder().encodeToString(phonePub), req.headers.getValue("X-ADOS-Phone-Key"))
        assertEquals(NOW.toString(), req.headers.getValue("X-ADOS-Uplink-Ts"))
        val mac = Base64.getDecoder().decode(req.headers.getValue("X-ADOS-Uplink-Mac"))
        assertEquals("bb00e64b4ccd8554d9c7f466b08f7c3546bd6ae53dd395fdeeb1f629820ac163", mac.toHex())
    }

    @Test
    fun everyInputIsBound() {
        val good = UplinkMac.macBase64(key, NOW, TAKE.encodeToByteArray())
        assertTrue(UplinkMac.verify(key, NOW, TAKE.encodeToByteArray(), good))
        assertFalse(UplinkMac.verify(ByteArray(32), NOW, TAKE.encodeToByteArray(), good))
        assertFalse(UplinkMac.verify(key, NOW + 1, TAKE.encodeToByteArray(), good))
        assertFalse(UplinkMac.verify(key, NOW, "{}".encodeToByteArray(), good))
        assertFalse(UplinkMac.verify(key, NOW, TAKE.encodeToByteArray(), "not base64!"))
    }

    @Test
    fun theGuardAcceptsEachGoodCallOnceInsideTheClockWindow() {
        val guard = UplinkReplayGuard()
        val body = YIELD.encodeToByteArray()
        fun sign(ts: Long) = UplinkMac.macBase64(key, ts, body)
        assertEquals(MacVerdict.Missing, guard.check(key, null, body, sign(NOW), NOW))
        assertEquals(MacVerdict.Mismatch, guard.check(key, NOW, body, UplinkMac.macBase64(ByteArray(32), NOW, body), NOW))
        assertEquals(MacVerdict.Clock, guard.check(key, NOW - 120_001, body, sign(NOW - 120_001), NOW))
        assertEquals(MacVerdict.Clock, guard.check(key, NOW + 120_001, body, sign(NOW + 120_001), NOW))
        assertEquals(MacVerdict.Accepted, guard.check(key, NOW, body, sign(NOW), NOW))
        assertEquals(MacVerdict.Replay, guard.check(key, NOW, body, sign(NOW), NOW))
        assertEquals(MacVerdict.Replay, guard.check(key, NOW - 1, body, sign(NOW - 1), NOW))
        assertEquals(MacVerdict.Accepted, guard.check(key, NOW + 1, body, sign(NOW + 1), NOW))
    }

    @Test
    fun boxRepliesMapToOkOrItsOneSentence() {
        assertEquals(BoxReply.Ok("standby"), HttpBoxUplinkApi.parseReply(200, """{"ok":true,"state":"standby"}"""))
        assertEquals(
            BoxReply.Refused("E_BIND_ACTIVE", "A bind session is running."),
            HttpBoxUplinkApi.parseReply(409, """{"error":"E_BIND_ACTIVE","detail":"A bind session is running."}"""),
        )
        val view = HttpBoxUplinkApi.parseView(
            kotlinx.serialization.json.Json.parseToJsonElement(
                """{"state":"standby","holder_device_id":"a1b2c3d4e5f6","holder_name":"Ops phone","since_ms":5,"watcher":{"last_beacon_ms":9}}""",
            ) as kotlinx.serialization.json.JsonObject,
        )
        assertEquals(BoxUplinkView("standby", "a1b2c3d4e5f6", "Ops phone", 5, 9), view)
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    companion object {
        const val NOW = 1_800_000_000_000L
        const val TAKE = """{"op":"take","holder_device_id":"a1b2c3d4e5f6","holder_name":"Ops phone","yield_port":47811}"""
        const val YIELD = """{"op":"yield","box_device_id":"gs-box-1"}"""
    }
}
