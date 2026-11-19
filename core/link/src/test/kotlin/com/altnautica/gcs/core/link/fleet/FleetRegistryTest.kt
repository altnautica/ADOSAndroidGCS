package com.altnautica.gcs.core.link.fleet

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The cases of the agent's fleet registry tests, against the phone's port. */
class FleetRegistryTest {

    @Test
    fun `allocation hands out the lowest free slot from one`() {
        val reg = FleetRegistry()
        assertEquals(1, reg.allocate("drone-a"))
        assertEquals(2, reg.allocate("drone-b"))
        assertEquals(3, reg.allocate("drone-c"))
        assertEquals(listOf(1, 2, 3), reg.slots.map { it.slot })
    }

    @Test
    fun `allocation is idempotent by device id`() {
        val reg = FleetRegistry()
        val first = reg.allocate("drone-a")
        reg.allocate("drone-b")
        assertEquals(first, reg.allocate("drone-a"))
        assertEquals(2, reg.size)
    }

    @Test
    fun `each drone gets its own relay secret`() {
        val reg = FleetRegistry()
        reg.allocate("aaaa")
        reg.allocate("bbbb")
        val a = reg.slots.first { it.deviceId == "aaaa" }.relaySecret
        val b = reg.slots.first { it.deviceId == "bbbb" }.relaySecret
        assertNotNull(a)
        assertNotNull(b)
        assertNotEquals(a, b)
    }

    @Test
    fun `a relay secret is 32 bytes of lowercase hex`() {
        val reg = FleetRegistry()
        reg.allocate("aaaa")
        val secret = reg.slots.single().relaySecret!!
        assertEquals(FleetRegistry.RELAY_SECRET_BYTES * 2, secret.length)
        assertTrue(secret.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `re-pairing keeps the secret and the issue time`() {
        var now = 1_000L
        val reg = FleetRegistry(clockMs = { now })
        reg.allocate("aaaa")
        val first = reg.slots.single()
        now = 9_000L
        reg.allocate("aaaa")
        assertEquals(first, reg.slots.single())
    }

    @Test
    fun `release frees the slot for reuse`() {
        val reg = FleetRegistry()
        reg.allocate("drone-a")
        reg.allocate("drone-b")
        reg.allocate("drone-c")
        assertTrue(reg.release("drone-b"))
        assertNull(reg.slotOf("drone-b"))
        assertEquals(2, reg.allocate("drone-d"))
        assertEquals(3, reg.size)
    }

    @Test
    fun `releasing an unregistered device is a no-op`() {
        val reg = FleetRegistry()
        reg.allocate("drone-a")
        assertFalse(reg.release("drone-nobody"))
        assertEquals(1, reg.size)
    }

    @Test
    fun `a shared prefix is a different aircraft`() {
        val reg = FleetRegistry()
        val slot = reg.allocate("f6aa0aa41193")
        assertEquals(slot, reg.slotOf("f6aa0aa41193"))
        assertNull(reg.slotOf("f6aa0aa4"))
        assertNotEquals(slot, reg.allocate("f6aa0aa4"))
    }

    @Test
    fun `an empty id matches nothing`() {
        val reg = FleetRegistry()
        reg.allocate("f6aa0aa41193")
        assertNull(reg.slotOf(""))
        assertNull(reg.slotOf("   "))
    }

    @Test
    fun `a full registry refuses a new device but still serves a known one`() {
        val reg = FleetRegistry()
        for (i in 1..FleetRegistry.MAX_SLOTS) assertEquals(i, reg.allocate("drone-$i"))
        assertNull(reg.allocate("drone-25"))
        assertEquals(7, reg.allocate("drone-7"))
        assertEquals(FleetRegistry.MAX_SLOTS, reg.size)
    }

    @Test
    fun `the stored form round trips exactly`() {
        val reg = FleetRegistry(clockMs = { 1_785_251_808_084L })
        reg.allocate("ados-abc123")
        reg.allocate("drone-b")
        val json = Json.encodeToString(ListSerializer(FleetSlot.serializer()), reg.slots)
        val back = FleetRegistry(Json.decodeFromString(ListSerializer(FleetSlot.serializer()), json))
        assertEquals(reg.slots, back.slots)
        assertEquals(2, back.slotOf("drone-b"))
        assertTrue(json.contains("\"device_id\":\"ados-abc123\""))
        assertTrue(json.contains("\"paired_at_ms\":1785251808084"))
    }

    @Test
    fun `a registration stored without a secret still loads`() {
        val slots = Json.decodeFromString(ListSerializer(FleetSlot.serializer()), """[{"slot":1,"device_id":"aaaa","paired_at_ms":123}]""")
        val reg = FleetRegistry(slots)
        assertEquals("aaaa", reg.slots.single().deviceId)
        assertNull(reg.slots.single().relaySecret)
    }

    @Test
    fun `device ids that cannot name an aircraft are refused`() {
        for (bad in listOf("", " ", "d", "drone-1", "drone 0001", "drone/0001", "a".repeat(33))) {
            assertFalse(bad, FleetRegistry.isValidDeviceId(bad))
        }
        assertTrue(FleetRegistry.isValidDeviceId("40bb1a5a4484"))
        assertTrue(FleetRegistry.isValidDeviceId("drone-01.b_c"))
        assertTrue(FleetRegistry.isValidDeviceId("a".repeat(32)))
    }
}
