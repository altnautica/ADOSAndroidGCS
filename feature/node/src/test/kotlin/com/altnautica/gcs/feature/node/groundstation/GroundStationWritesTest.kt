package com.altnautica.gcs.feature.node.groundstation

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroundStationWritesTest {

    @Test
    fun theConfirmedRetryCarriesTheArmedOverride() {
        assertNull(apUpdate(false, null, null, null, force = false).force)
        assertEquals(true, apUpdate(false, null, null, null, force = true).force)
        assertNull(joinBody("home", "secret12", force = false)["force"])
        assertEquals(JsonPrimitive(true), joinBody("home", null, force = true)["force"])
        assertEquals(JsonPrimitive(true), modemBody(true, null, null, force = true)["force"])
        assertNull(modemBody(true, null, null, force = false)["force"])
        assertEquals(JsonPrimitive(true), shareUplinkBody(true, force = true)["force"])
        assertNull(shareUplinkBody(true, force = false)["force"])
    }

    @Test
    fun accessPointChannelsReachThirteenAndKeepAnUnknownChannel() {
        assertEquals((1..13).toList(), apChannelOptions(13))
        assertEquals((1..13).toList(), apChannelOptions(null))
        assertEquals(listOf(14) + (1..13).toList(), apChannelOptions(14))
    }

    @Test
    fun turningTheAccessPointOffOrSharingTheUplinkAsksFirst() {
        assertTrue(apSwitchNeedsConfirm(turnOn = false))
        assertFalse(apSwitchNeedsConfirm(turnOn = true))
        assertTrue(shareNeedsConfirm(turnOn = true))
        assertFalse(shareNeedsConfirm(turnOn = false))
    }

    @Test
    fun txPowerIsCappedAtTheAgentCeiling() {
        val status = buildJsonObject { put("link", JsonObject(emptyMap())) }
        val capped = RadioLink.parse(status, buildJsonObject { put("tx_power_dbm", 10); put("tx_power_max_dbm", 12) })
        assertEquals(12, capped.txPowerMaxDbm)
        assertEquals(RadioLink.DEFAULT_TX_MAX_DBM, RadioLink.parse(status, null).txPowerMaxDbm)
        assertEquals(12, RadioLink.txPowerStart(25.0, 12))
        assertEquals(5, RadioLink.txPowerStart(null, 15))
    }
}
