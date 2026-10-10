package com.altnautica.gcs.radio.rx.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Vectors mirror the agent's fleet link-id tests. */
class LinkParamsTest {

    @Test
    fun `link id packs fleet over slot`() {
        assertEquals(0x000100, LinkParams.linkId(1, 0))
        assertEquals(0x000118, LinkParams.linkId(1, 24))
        assertEquals(259, LinkParams.linkId(1, 3))
        assertEquals(0x07_0C, LinkParams.linkId(7, 12))
        assertEquals(0xFFFF_18, LinkParams.linkId(0xFFFF, 24))
        assertNotEquals(LinkParams.linkId(1, LinkParams.SLOT_GROUND), LinkParams.linkId(1, 1))
    }

    @Test
    fun `channel id is link id shifted over the radio port`() {
        assertEquals((259L shl 8) + 0, LinkParams.channelId(259, LinkParams.PORT_VIDEO))
        assertEquals(0x0001_0302L, LinkParams.channelId(LinkParams.linkId(1, 3), LinkParams.PORT_AUX_DOWN))
    }

    @Test
    fun `every slot and port of a fleet has its own channel id`() {
        val ids = HashSet<Long>()
        for (slot in 0..LinkParams.FLEET_MAX_SLOTS) {
            for (port in 0..3) {
                check(ids.add(LinkParams.channelId(LinkParams.linkId(1, slot), port))) { "collision at slot $slot port $port" }
            }
        }
        assertEquals((LinkParams.FLEET_MAX_SLOTS + 1) * 4, ids.size)
    }

    @Test
    fun `fleets sharing a channel never alias`() {
        assertNotEquals(LinkParams.linkId(1, 24), LinkParams.linkId(2, 24))
        assertNotEquals(LinkParams.channelId(LinkParams.linkId(1, 1), 0), LinkParams.channelId(LinkParams.linkId(2, 1), 0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `fleet zero is unprovisioned`() {
        LinkParams.linkId(0, 1)
    }
}
