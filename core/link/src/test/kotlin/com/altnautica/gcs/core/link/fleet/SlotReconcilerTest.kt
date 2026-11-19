package com.altnautica.gcs.core.link.fleet

import com.altnautica.gcs.core.data.vault.CredentialVault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotReconcilerTest {

    private data class Call(val deviceId: String, val method: String, val path: String, val contentType: String?, val body: String, val secret: String?)

    /** A Request lane standing in for the radio: answers per (device, path) and records every call. */
    private class FakeLane(var answer: (Call) -> Int?) : FleetRequestLane {
        val calls = mutableListOf<Call>()
        override suspend fun request(deviceId: String, method: String, path: String, contentType: String?, body: ByteArray, relaySecretHex: String?): Int? {
            val c = Call(deviceId, method, path, contentType, body.decodeToString(), relaySecretHex)
            calls += c
            return answer(c)
        }
    }

    @Test
    fun `the secret goes first without a ticket and then the slot under a ticket from it`() = runTest {
        val store = boundStore()
        store.enrol("drone-aa01")
        val secret = store.fleet.value!!.slots.single().relaySecret!!
        val lane = FakeLane { 200 }
        reconciler(store, lane).tick(lane)

        assertEquals(2, lane.calls.size)
        val (offer, slot) = lane.calls
        assertEquals(Call("drone-aa01", "POST", "/api/relay/peer-secret", "application/json", """{"secret":"$secret"}""", null), offer)
        assertEquals(Call("drone-aa01", "PUT", "/api/config", "application/json", """{"key":"video.wfb.fleet_slot","value":1}""", secret), slot)
    }

    @Test
    fun `an agreeing fleet goes quiet`() = runTest {
        val store = boundStore()
        store.enrol("drone-aa01")
        val lane = FakeLane { 200 }
        val r = reconciler(store, lane)
        r.tick(lane)
        lane.calls.clear()
        r.tick(lane)
        assertTrue(lane.calls.isEmpty())
        assertEquals(RelaySecretState.Held, r.secrets.value["drone-aa01"])
        assertEquals(1, r.slotsConfirmed.value["drone-aa01"])
    }

    @Test
    fun `a drone that does not answer is restated next tick`() = runTest {
        val store = boundStore()
        store.enrol("drone-aa01")
        val lane = FakeLane { null }
        val r = reconciler(store, lane)
        r.tick(lane)
        assertEquals(RelaySecretState.Pending, r.secrets.value["drone-aa01"])
        lane.calls.clear()
        lane.answer = { 200 }
        r.tick(lane)
        assertEquals(2, lane.calls.size)
        assertEquals(1, r.slotsConfirmed.value["drone-aa01"])
    }

    @Test
    fun `a drone holding another ground's secret is reported once and not retried`() = runTest {
        val store = boundStore()
        store.enrol("drone-aa01")
        val lane = FakeLane { if (it.path == SlotReconciler.SECRET_PATH) 409 else 200 }
        val r = reconciler(store, lane)
        r.tick(lane)
        assertEquals(RelaySecretState.Conflict, r.secrets.value["drone-aa01"])
        assertEquals("no slot write a conflicted drone would refuse", 1, lane.calls.size)
        lane.calls.clear()
        r.tick(lane)
        assertTrue(lane.calls.isEmpty())
    }

    @Test
    fun `a refused slot write is retried`() = runTest {
        val store = boundStore()
        store.enrol("drone-aa01")
        val lane = FakeLane { if (it.path == SlotReconciler.CONFIG_PATH) 401 else 200 }
        val r = reconciler(store, lane)
        r.tick(lane)
        assertNull(r.slotsConfirmed.value["drone-aa01"])
        lane.calls.clear()
        r.tick(lane)
        assertEquals(listOf(SlotReconciler.CONFIG_PATH), lane.calls.map { it.path })
    }

    @Test
    fun `every enrolled drone is told its own slot`() = runTest {
        val store = boundStore()
        store.enrol("drone-aa01")
        store.enrol("drone-bb02")
        val lane = FakeLane { 200 }
        reconciler(store, lane).tick(lane)
        val slotWrites = lane.calls.filter { it.path == SlotReconciler.CONFIG_PATH }.associate { it.deviceId to it.body }
        assertEquals("""{"key":"video.wfb.fleet_slot","value":1}""", slotWrites["drone-aa01"])
        assertEquals("""{"key":"video.wfb.fleet_slot","value":2}""", slotWrites["drone-bb02"])
    }

    @Test
    fun `with no Request lane bound the reconciler does nothing`() = runTest {
        val store = boundStore()
        store.enrol("drone-aa01")
        SlotReconciler(store, null, 10).run()
    }

    private fun reconciler(store: FleetGroundStore, lane: FleetRequestLane) = SlotReconciler(store, lane, SlotReconciler.INTERVAL_MS)

    private suspend fun kotlinx.coroutines.test.TestScope.boundStore(): FleetGroundStore {
        val vault = CredentialVault(MemoryPreferences(), PlainCipher, CoroutineScope(backgroundScope.coroutineContext))
        return FleetGroundStore(vault).apply { adopt(FleetKeys(ByteArray(64) { 1 }, ByteArray(64) { 2 }, ByteArray(32) { 3 }, "fp")) }
    }
}
