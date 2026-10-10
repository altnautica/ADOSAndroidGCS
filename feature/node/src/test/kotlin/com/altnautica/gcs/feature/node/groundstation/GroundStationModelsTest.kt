package com.altnautica.gcs.feature.node.groundstation

import com.altnautica.gcs.feature.node.logs.NodeLogsViewModel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroundStationModelsTest {

    private fun obj(s: String) = Json.parseToJsonElement(s) as JsonObject

    @Test
    fun carriedDronesFromSlotsPeersAndStatus() {
        val c = CarriedDrones.parse(
            pair = obj("""{"slots":[{"slot":2,"device_id":"b","paired_at_ms":5,"system_id_conflict":true,"video_hero":false,"relay_credential":"held"},{"slot":1,"device_id":"a","video_hero":true}]}"""),
            relayed = obj("""{"peers":[{"device_id":"a","name":"Alpha","status_fresh":true,"status_age_s":1.5}]}"""),
            status = obj("""{"paired_drone":{"device_id":"a"}}"""),
        )
        assertEquals(listOf("a", "b"), c.slots.map { it.deviceId })
        assertTrue(c.slots[1].systemIdConflict)
        assertEquals("Alpha", c.peers!!.single().name)
        assertEquals("a", c.primaryDroneId)
    }

    @Test
    fun relayedStatusAbsentIsNull() {
        assertNull(CarriedDrones.parse(obj("""{"slots":[]}"""), null, null).peers)
    }

    @Test
    fun radioPrefersLiveWfbChannelAndPower() {
        val r = RadioLink.parse(
            obj("""{"link":{"channel":36,"rssi_dbm":-52,"fec_recovered":3,"link_diag":"healthy"},"role":{"current":"direct"}}"""),
            obj("""{"channel":149,"tx_power_dbm":18}"""),
        )
        assertEquals(149, r.channel)
        assertEquals(18.0, r.txPowerDbm)
        assertEquals(-52.0, r.rssiDbm)
        assertEquals("healthy", r.diagnosis)
        assertEquals("direct", r.role)
    }

    @Test
    fun networkCountsClientMacsAndScanDedupes() {
        val n = StationNetwork.parse(obj("""{"ap":{"enabled":true,"ssid":"ADOS-GS-1","connected_clients":["aa","bb"]},"priority":["eth0","wlan0_client"],"share_uplink":false}"""))
        assertEquals(2, n.ap!!.clients)
        assertEquals(listOf("eth0", "wlan0_client"), n.priority)
        val scan = ScannedNetwork.parse(obj("""{"networks":[{"ssid":"x","signal":-70},{"ssid":"x","signal":-40},{"ssid":"y","signal":-50,"security":"WPA2"},{"ssid":""}]}"""))
        assertEquals(listOf("x", "y"), scan.map { it.ssid })
        assertTrue(scan.first().open)
    }

    @Test
    fun meshReadsAgentRoleShape() {
        val m = MeshState.parse(
            obj("""{"role":"receiver","configured":"receiver","supported":["direct","relay","receiver"]}"""),
            obj("""{"up":true,"neighbors":[{"mac":"m1","tq":200}],"gateways":[{"mac":"g1","selected":true}]}"""),
        )
        assertEquals("receiver", m.role)
        assertEquals(1, m.neighbors.size)
        assertTrue(m.gateways.single().selected)
        assertEquals(PairWindow(true, 9L, "123456", listOf("d1"), listOf("d1")), PairWindow.parse(obj("""{"open":true,"closes_at_ms":9,"code":"123456","pending":[{"device_id":"d1"}],"approvals":{"d1":5}}""")))
    }

    @Test
    fun logEntriesParseWithDefaults() {
        val entries = NodeLogsViewModel.parse(obj("""{"entries":[{"seq":7,"timestamp":"2026-01-01T10:00:00Z","level":"warn","logger":"ados_video","message":"hi"},{"message":"x"}],"total":2}"""))
        assertEquals("WARN", entries[0].level)
        assertEquals("INFO", entries[1].level)
        assertEquals(7L, entries[0].seq)
    }
}
