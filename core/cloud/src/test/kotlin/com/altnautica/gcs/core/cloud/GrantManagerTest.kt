package com.altnautica.gcs.core.cloud

import com.altnautica.gcs.core.cloud.grant.AuthorityReason
import com.altnautica.gcs.core.cloud.grant.CapabilityState
import com.altnautica.gcs.core.cloud.grant.ControlLane
import com.altnautica.gcs.core.cloud.grant.GrantManager
import com.altnautica.gcs.core.cloud.grant.GrantState
import com.altnautica.gcs.core.cloud.grant.HeldGrant
import com.altnautica.gcs.core.cloud.grant.MqttControlAuthority
import com.altnautica.gcs.core.cloud.auth.CloudSessionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GrantManagerTest {

    private val ttl = 60 * 60 * 1000L

    private fun TestScope.setup(failAfter: Int = Int.MAX_VALUE): Triple<FakeGateway, GrantManager, FakeAuth> {
        val gateway = FakeGateway()
        var minted = 0
        gateway.onAction = { name, _ ->
            check(name == "cmdMqttControlGrants:mint")
            if (minted >= failAfter) throw RuntimeException("mint refused")
            minted++
            buildJsonObject {
                put("principal", "gcs-op-$minted")
                put("secret", "s$minted")
                putJsonArray("deviceIds") { add("d1") }
                put("expiresAt", (testScheduler.currentTime + ttl).toDouble())
            }
        }
        val auth = FakeAuth()
        val manager = GrantManager(gateway, auth, WallClock { testScheduler.currentTime }, backgroundScope)
        return Triple(gateway, manager, auth)
    }

    private fun FakeGateway.mints() = calls.filter { it.name == "cmdMqttControlGrants:mint" }

    @Test
    fun renewsTenMinutesBeforeExpiryNamingTheReplacedGrant() = runTest {
        val (gateway, manager, _) = setup()
        runCurrent()
        assertEquals(1, gateway.mints().size)
        assertEquals(emptyMap<String, Any?>(), gateway.mints()[0].args)
        assertEquals("gcs-op-1", manager.state.value.grant?.principal)
        assertFalse(manager.state.value.grant!!.writeConfirmed)

        advanceTimeBy(ttl - GrantManager.RENEW_LEAD_MS - 1)
        runCurrent()
        assertEquals(1, gateway.mints().size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, gateway.mints().size)
        assertEquals(mapOf("replaces" to "gcs-op-1"), gateway.mints()[1].args)
        assertEquals("gcs-op-2", manager.state.value.grant?.principal)
    }

    @Test
    fun failedRenewalRetriesEverySixtySecondsAndKeepsTheLiveGrant() = runTest {
        val (gateway, manager, _) = setup(failAfter = 1)
        runCurrent()
        advanceTimeBy(ttl - GrantManager.RENEW_LEAD_MS)
        runCurrent()
        assertEquals(2, gateway.mints().size)
        assertTrue(manager.state.value.renewalFailed)
        assertEquals("gcs-op-1", manager.state.value.grant?.principal)

        advanceTimeBy(GrantManager.RETRY_MS - 1)
        runCurrent()
        assertEquals(2, gateway.mints().size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(3, gateway.mints().size)
    }

    @Test
    fun confirmWriteMarksTheGrantProven() = runTest {
        val (gateway, manager, _) = setup()
        runCurrent()
        manager.confirmWrite("gcs-op-1")
        assertTrue(manager.state.value.grant!!.writeConfirmed)
        assertEquals(mapOf("principal" to "gcs-op-1"), gateway.calls.last { it.name == "cmdMqttControlGrants:confirmWrite" }.args)
    }

    @Test
    fun signOutDropsTheGrantAndReleaseRevokes() = runTest {
        val (gateway, manager, auth) = setup()
        runCurrent()
        manager.release()
        assertNull(manager.state.value.grant)
        assertTrue(gateway.calls.any { it.name == "cmdMqttControlGrants:revoke" })
        auth.state.value = CloudSessionState.SignedOut
        runCurrent()
        assertNull(manager.state.value.grant)
    }

    @Test
    fun aMintThatCompletesAfterReleaseIsRevokedNotStored() = runTest {
        val (gateway, manager, _) = setup()
        val gate = CompletableDeferred<Unit>()
        val mint = gateway.onAction
        gateway.onAction = { name, args -> gate.await(); mint(name, args) }
        runCurrent()
        assertTrue("first mint in flight", manager.state.value.minting)

        manager.release()
        assertTrue("nothing held yet, so release revokes nothing", gateway.calls.none { it.name == "cmdMqttControlGrants:revoke" })

        gate.complete(Unit)
        runCurrent()
        assertNull(manager.state.value.grant)
        val names = gateway.calls.map { it.name }
        assertEquals(listOf("cmdMqttControlGrants:mint", "cmdMqttControlGrants:revoke"), names)
    }

    @Test
    fun authorityFollowsTheGrant() {
        val now = 1_000_000L
        val held = HeldGrant("p", "s", listOf("d1"), now + 3_600_000, writeConfirmed = false)
        fun r(state: GrantState, device: String = "d1") = MqttControlAuthority.resolve(ControlLane.CloudRelay, device, state, now)

        assertEquals(AuthorityReason.NoGrant, r(GrantState()).reason)
        assertEquals(CapabilityState.Provisioning, r(GrantState(minting = true)).fcFrames)
        assertEquals(AuthorityReason.NoGrant, r(GrantState(grant = held), device = "other").reason)
        assertEquals(CapabilityState.Unconfirmed, r(GrantState(grant = held)).fcFrames)
        assertEquals(CapabilityState.Available, r(GrantState(grant = held.copy(writeConfirmed = true))).fcFrames)
        assertEquals(AuthorityReason.GrantExpired, r(GrantState(grant = held.copy(expiresAtMs = now))).reason)
        assertEquals(
            CapabilityState.Expiring,
            r(GrantState(grant = held.copy(expiresAtMs = now + 60_000), renewalFailed = true)).fcFrames,
        )
        assertEquals(AuthorityReason.DirectLink, MqttControlAuthority.resolve(ControlLane.Direct, "d1", GrantState(), now).reason)
        assertFalse(r(GrantState()).canPublish)
        assertTrue(r(GrantState(grant = held)).canPublish)
    }
}
