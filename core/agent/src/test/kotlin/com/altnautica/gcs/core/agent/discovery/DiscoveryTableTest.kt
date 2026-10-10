package com.altnautica.gcs.core.agent.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryTableTest {

    private fun txt(vararg pairs: Pair<String, String?>): Map<String, ByteArray?> =
        pairs.associate { (k, v) -> k to v?.encodeToByteArray() }

    private fun agent(service: String, host: String, deviceId: String?, now: Long = 1L, code: String? = null) =
        AgentTxt.toAgent(
            service,
            host,
            8080,
            AgentTxt.parse(txt("device_id" to deviceId, "name" to "Bench", "paired" to "false", "profile" to "drone", "code" to code)),
            now,
        )

    @Test
    fun `txt record parses every advertised key`() {
        val parsed = AgentTxt.parse(
            txt(
                "device_id" to "abcdef0123456789",
                "version" to "1.2.3",
                "board" to "rock-5c-lite",
                "name" to "Bench drone",
                "paired" to "false",
                "profile" to "ground-station",
                "role" to "direct",
                "code" to "k7m2qx",
                "empty" to "",
                "nullvalue" to null,
            ),
        )
        val a = AgentTxt.toAgent("ados-1", "192.168.1.50", 8080, parsed, 10L)
        assertEquals("abcdef0123456789", a.deviceId)
        assertEquals("1.2.3", a.version)
        assertEquals("rock-5c-lite", a.board)
        assertEquals(false, a.paired)
        assertEquals("ground-station", a.profile)
        assertEquals("direct", a.role)
        assertEquals("K7M2QX", a.code)
        assertEquals("http://192.168.1.50:8080/", a.baseUrl)
        assertFalse(parsed.containsKey("empty"))
        assertFalse(parsed.containsKey("nullvalue"))
    }

    @Test
    fun `codes outside the alphabet are dropped`() {
        assertNull(agent("s", "192.168.1.50", "a", code = "OI01AB").code)
        assertTrue(PairingCode.isValid(PairingCode.normalize(" k7m-2qx ")))
    }

    @Test
    fun `one row per device id with the latest address`() {
        val table = DiscoveryTable()
        table.upsert(agent("ados-1", "192.168.1.50", "dev1", now = 1))
        table.upsert(agent("ados-1 (2)", "192.168.1.51", "dev1", now = 2))
        val rows = table.upsert(agent("ados-2", "192.168.1.60", "dev2", now = 3))
        assertEquals(2, rows.size)
        val dev1 = rows.first { it.deviceId == "dev1" }
        assertEquals("192.168.1.51", dev1.host)
        assertEquals(2L, dev1.lastSeenMs)
    }

    @Test
    fun `lost service removes its row`() {
        val table = DiscoveryTable()
        table.upsert(agent("ados-1", "192.168.1.50", "dev1"))
        table.upsert(agent("ados-2", "192.168.1.60", null))
        val rows = table.remove("ados-1")
        assertEquals(listOf("ados-2"), rows.map { it.serviceName })
        assertTrue(table.remove("ados-2").isEmpty())
    }

    @Test
    fun `ipv6 base url is bracketed`() {
        assertEquals("http://[fe80::1]:8080/", agent("s", "fe80::1", "d").baseUrl)
    }
}
