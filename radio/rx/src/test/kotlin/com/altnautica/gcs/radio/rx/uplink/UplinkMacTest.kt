package com.altnautica.gcs.radio.rx.uplink

import okhttp3.OkHttpClient
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
        val mac = UplinkMac.mac(UplinkMac.Domain.Request, key, NOW, TAKE.encodeToByteArray())
        assertEquals("bb00e64b4ccd8554d9c7f466b08f7c3546bd6ae53dd395fdeeb1f629820ac163", mac.toHex())
    }

    /**
     * The agent's take-back signing: the `ados-control` test
     * `the_yield_mac_vector` pins `af2ace…` for this key, time and the body
     * the box sends. The same inputs under the phone's own context give
     * `013cc7…`, which must not verify as a take-back.
     */
    @Test
    fun theTakeBackMacMatchesTheAgentsVectorAndOnlyItsOwnContext() {
        val body = YIELD_VECTOR.encodeToByteArray()
        assertEquals(
            "af2aceac943790b74e28d4692ab1c2301c8cf3f8bfa22f47511650cd2b7e9cfc",
            UplinkMac.mac(UplinkMac.Domain.Yield, key, NOW, body).toHex(),
        )
        val phoneSigned = UplinkMac.mac(UplinkMac.Domain.Request, key, NOW, body)
        assertEquals("013cc7565b5e4fce990998ca1b7ee74f21e41d94e5b10944a51c305fd2429331", phoneSigned.toHex())
        assertFalse(UplinkMac.verify(UplinkMac.Domain.Yield, key, NOW, body, Base64.getEncoder().encodeToString(phoneSigned)))
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
        val d = UplinkMac.Domain.Request
        val good = UplinkMac.macBase64(d, key, NOW, TAKE.encodeToByteArray())
        assertTrue(UplinkMac.verify(d, key, NOW, TAKE.encodeToByteArray(), good))
        assertFalse(UplinkMac.verify(UplinkMac.Domain.Yield, key, NOW, TAKE.encodeToByteArray(), good))
        assertFalse(UplinkMac.verify(d, ByteArray(32), NOW, TAKE.encodeToByteArray(), good))
        assertFalse(UplinkMac.verify(d, key, NOW + 1, TAKE.encodeToByteArray(), good))
        assertFalse(UplinkMac.verify(d, key, NOW, "{}".encodeToByteArray(), good))
        assertFalse(UplinkMac.verify(d, key, NOW, TAKE.encodeToByteArray(), "not base64!"))
    }

    @Test
    fun theGuardAcceptsEachGoodTakeBackOnceInsideTheClockWindow() {
        val guard = YieldGuard()
        val body = YIELD.encodeToByteArray()
        fun sign(ts: Long, b: ByteArray = body) = UplinkMac.macBase64(UplinkMac.Domain.Yield, key, ts, b)
        fun check(ts: Long?, mac: String?, b: ByteArray = body) = guard.check(key, ts, b, mac, BOX, NOW)
        assertEquals(MacVerdict.Missing, check(null, sign(NOW)))
        assertEquals(MacVerdict.Mismatch, check(NOW, UplinkMac.macBase64(UplinkMac.Domain.Yield, ByteArray(32), NOW, body)))
        assertEquals(MacVerdict.Mismatch, check(NOW, UplinkMac.macBase64(UplinkMac.Domain.Request, key, NOW, body)))
        assertEquals(MacVerdict.Clock, check(NOW - 120_001, sign(NOW - 120_001)))
        assertEquals(MacVerdict.Clock, check(NOW + 120_001, sign(NOW + 120_001)))
        val take = TAKE.encodeToByteArray()
        assertEquals(MacVerdict.NotAYield, check(NOW, sign(NOW, take), take))
        val otherBox = """{"box_device_id":"gs-other","op":"yield"}""".encodeToByteArray()
        assertEquals(MacVerdict.NotAYield, check(NOW, sign(NOW, otherBox), otherBox))
        assertEquals(MacVerdict.Accepted, check(NOW, sign(NOW)))
        assertEquals(MacVerdict.Replay, check(NOW, sign(NOW)))
        assertEquals(MacVerdict.Replay, check(NOW - 1, sign(NOW - 1)))
        assertEquals(MacVerdict.Accepted, check(NOW + 1, sign(NOW + 1)))
    }

    /** The box may hold its operation lock 8 s for a resume, then take 8 s to stop. */
    @Test
    fun aTakeWaitsOutTheBoxsSlowestAnswer() {
        assertTrue(HttpBoxUplinkApi(OkHttpClient()).client.readTimeoutMillis >= 16_000)
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
        const val BOX = "gs-box-1"
        const val TAKE = """{"op":"take","holder_device_id":"a1b2c3d4e5f6","holder_name":"Ops phone","yield_port":47811}"""
        const val YIELD = """{"box_device_id":"gs-box-1","op":"yield"}"""
        const val YIELD_VECTOR = """{"box_device_id":"gs-0a1b2c","op":"yield"}"""
    }
}
