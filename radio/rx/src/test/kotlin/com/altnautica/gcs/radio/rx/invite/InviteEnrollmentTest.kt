package com.altnautica.gcs.radio.rx.invite

import com.altnautica.gcs.core.data.vault.CredentialVault
import com.altnautica.gcs.core.model.DeviceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class InviteEnrollmentTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() = scope.cancel()

    private fun store() = InviteStore(CredentialVault(MemoryPreferences(), FlakyCipher(), scope))

    private val bundleJson = Json.encodeToString(InviteBundle.serializer(), sampleBundle())

    /** A ground station that seals [plaintext] for whichever phone registers, after [pendingPolls] polls. */
    private inner class FakeGround(
        private val plaintext: ByteArray,
        private val pendingPolls: Int,
        private val answer: InvitePoll? = null,
        private val fingerprintOverride: String? = null,
    ) : PhoneInviteApi {
        var phonePublic: ByteArray? = null
        var polls = 0

        override suspend fun create(ground: DeviceId, phonePublic: ByteArray, label: String): InviteCallResult<CreatedInvite> {
            this.phonePublic = phonePublic
            return InviteCallResult.Ok(CreatedInvite(INVITE_ID, fingerprintOverride ?: InviteFingerprint.of(phonePublic), 120_000))
        }

        override suspend fun poll(ground: DeviceId, inviteId: String): InviteCallResult<InvitePoll> {
            polls++
            if (polls <= pendingPolls) return InviteCallResult.Ok(InvitePoll.Pending)
            answer?.let { return InviteCallResult.Ok(it) }
            val sealed = JdkInviteCrypto.seal(plaintext, ByteArray(32) { 5 }, phonePublic!!, ByteArray(12) { 2 }, InviteContext.of(inviteId))
            return InviteCallResult.Ok(InvitePoll.Approved(sealed))
        }

        override suspend fun pending(ground: DeviceId) = InviteCallResult.Ok(emptyList<PendingInvite>())
        override suspend fun approve(ground: DeviceId, inviteId: String) = InviteCallResult.Ok(Unit)
        override suspend fun reject(ground: DeviceId, inviteId: String) = InviteCallResult.Ok(Unit)
    }

    @Test
    fun `approved request stores the opened keys`() = runTest {
        val store = store()
        val api = FakeGround(bundleJson.toByteArray(), pendingPolls = 2)
        val states = InviteEnrollment(api, JdkInviteCrypto, store).apply { wallClock = { 42L } }
            .run(DeviceId("gs-1"), "Field GS", "Test phone").toList()

        assertEquals(EnrollmentState.Requesting, states[0])
        val awaiting = states[1] as EnrollmentState.AwaitingApproval
        assertEquals(InviteFingerprint.of(api.phonePublic!!), awaiting.fingerprint)
        val approved = states.last() as EnrollmentState.Approved
        assertEquals("gs-1", approved.invite.groundId)
        assertEquals("Field GS", approved.invite.groundName)
        assertEquals(42L, approved.invite.approvedAtMs)
        assertEquals(157, approved.invite.bundle.channel)
        assertEquals(3, api.polls)
        assertEquals(approved.invite, store.invite.value)
    }

    @Test
    fun `rejection and expiry end the request without keys`() = runTest {
        val rejected = InviteEnrollment(FakeGround(ByteArray(0), 0, InvitePoll.Rejected), JdkInviteCrypto, store())
            .run(DeviceId("gs"), null, "p").toList()
        assertEquals(EnrollmentState.Rejected, rejected.last())
        val expired = InviteEnrollment(FakeGround(ByteArray(0), 1, InvitePoll.Expired), JdkInviteCrypto, store())
            .run(DeviceId("gs"), null, "p").toList()
        assertEquals(EnrollmentState.Expired, expired.last())
    }

    @Test
    fun `a bundle that does not validate is not stored`() = runTest {
        val store = store()
        val bad = """{"v":2,"rx_key_b64":"","hop_key_b64":"","fleet_id":1,"slots":[],"channel":1,"rendezvous_channel":1,"ports":{"video":0,"control":1,"aux_down":2}}"""
        val states = InviteEnrollment(FakeGround(bad.toByteArray(), 0), JdkInviteCrypto, store).run(DeviceId("gs"), null, "p").toList()
        assertTrue(states.last() is EnrollmentState.InvalidInvite)
        assertNull(store.load())
    }

    @Test
    fun `a swapped phone key is refused before polling`() = runTest {
        val api = FakeGround(bundleJson.toByteArray(), 0, fingerprintOverride = "0000-0000-0000-0000")
        val states = InviteEnrollment(api, JdkInviteCrypto, store()).run(DeviceId("gs"), null, "p").toList()
        assertTrue(states.last() is EnrollmentState.InvalidInvite)
        assertEquals(0, api.polls)
    }

    @Test
    fun `keys that open but do not save are kept and saved again`() = runTest {
        val prefs = MemoryPreferences()
        val cipher = FlakyCipher()
        val vault = CredentialVault(prefs, cipher, scope)
        vault.putCloudRefreshToken("other-secret")
        val store = InviteStore(vault)
        val enrollment = InviteEnrollment(FakeGround(bundleJson.toByteArray(), 0), JdkInviteCrypto, store)

        cipher.failing = true
        val notSaved = enrollment.run(DeviceId("gs-1"), "Field GS", "p").toList().last() as EnrollmentState.NotSaved
        assertEquals(157, notSaved.invite.bundle.channel)
        assertNull(store.invite.value)

        // The ground station would not hand the bundle out again: the held invite is what gets stored.
        cipher.failing = false
        assertEquals(EnrollmentState.Approved(notSaved.invite), enrollment.save(notSaved.invite))
        assertEquals(notSaved.invite, store.invite.value)
        assertEquals(notSaved.invite, InviteStore(CredentialVault(prefs, FlakyCipher(), scope)).load())
        assertEquals("other-secret", vault.cloudRefreshToken())
    }

    @Test
    fun `poll bodies decode`() {
        assertEquals(InvitePoll.Pending, AgentPhoneInviteApi.parsePoll(buildJsonObject { put("state", JsonPrimitive("pending")) }))
        assertEquals(InvitePoll.Rejected, AgentPhoneInviteApi.parsePoll(buildJsonObject { put("state", JsonPrimitive("rejected")) }))
        val approved = AgentPhoneInviteApi.parsePoll(
            buildJsonObject {
                put("state", JsonPrimitive("approved"))
                put("gs_eph_pub_b64", JsonPrimitive(Base64.getEncoder().encodeToString(ByteArray(32) { 1 })))
                put("nonce_b64", JsonPrimitive(Base64.getEncoder().encodeToString(ByteArray(12) { 2 })))
                put("ciphertext_b64", JsonPrimitive(Base64.getEncoder().encodeToString(ByteArray(40) { 3 })))
            },
        ) as InvitePoll.Approved
        assertEquals(12, approved.sealed.nonce.size)
        assertEquals(40, approved.sealed.ciphertext.size)
        assertNull(AgentPhoneInviteApi.parsePoll(JsonPrimitive("x")))
    }

    @Test(expected = InvalidInviteException::class)
    fun `bundle with a short receive key is invalid`() {
        InviteBundle.parse(bundleJson.replace(Base64.getEncoder().encodeToString(ByteArray(64) { it.toByte() }), "AAAA").toByteArray())
    }

    private companion object {
        const val INVITE_ID = "0123456789abcdef0123456789abcdef"
    }
}
