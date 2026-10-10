package com.altnautica.gcs.radio.rx.invite

import com.altnautica.gcs.radio.rx.link.LinkParams
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The phone-invite contract against the vector the ground station's sealing
 * code produced (`phone-invite-vector.json`), and the session-key KDF
 * against the agent's pinned mesh-invite vector.
 */
class InviteVectorTest {

    private val vector: JsonObject = Json.parseToJsonElement(
        checkNotNull(javaClass.classLoader!!.getResource("phone-invite-vector.json")).readText(),
    ) as JsonObject

    private fun hex(key: String) = vector.getValue(key).jsonPrimitive.content.unhex()
    private fun text(key: String) = vector.getValue(key).jsonPrimitive.content

    @Test
    fun `session key matches the agent's pinned vector`() {
        val context = "ados-mesh-invite".toByteArray() + byteArrayOf(0) + "123456".toByteArray()
        val key = JdkInviteCrypto.sessionKey(ByteArray(32) { 0x11 }, context)
        assertEquals("b286a016663b9570232bda114a1f86cced6679ca9e7357ca17b118ea9a71c200", key.hex())
    }

    @Test
    fun `context is the label followed by the invite id`() {
        assertArrayEquals(hex("context_hex"), InviteContext.of(text("invite_id")))
    }

    @Test
    fun `phone public key and fingerprint match the ground station`() {
        val pub = JdkInviteCrypto.publicKey(hex("phone_secret_key_hex"))
        assertArrayEquals(hex("phone_public_key_hex"), pub)
        assertEquals(text("phone_fingerprint"), InviteFingerprint.of(pub))
    }

    @Test
    fun `the sealed vector opens to the expected plaintext`() {
        val sealed = SealedInvite(hex("gs_ephemeral_public_hex"), hex("nonce_hex"), hex("ciphertext_hex"))
        val plain = JdkInviteCrypto.open(hex("phone_secret_key_hex"), sealed, InviteContext.of(text("invite_id")))
        assertEquals(text("expected_plaintext_json"), plain!!.decodeToString())
    }

    @Test
    fun `a tampered ciphertext or wrong context does not open`() {
        val ct = hex("ciphertext_hex").also { it[3] = (it[3].toInt() xor 1).toByte() }
        val secret = hex("phone_secret_key_hex")
        assertNull(JdkInviteCrypto.open(secret, SealedInvite(hex("gs_ephemeral_public_hex"), hex("nonce_hex"), ct), InviteContext.of(text("invite_id"))))
        val good = SealedInvite(hex("gs_ephemeral_public_hex"), hex("nonce_hex"), hex("ciphertext_hex"))
        assertNull(JdkInviteCrypto.open(secret, good, InviteContext.of("ffffffffffffffffffffffffffffffff")))
    }

    @Test
    fun `the plaintext parses into a usable bundle`() {
        val bundle = InviteBundle.parse(text("expected_plaintext_json").toByteArray())
        assertEquals(1, bundle.v)
        assertEquals(7, bundle.fleetId)
        assertEquals(149, bundle.channel)
        assertEquals(149, bundle.rendezvousChannel)
        assertEquals(20, bundle.bandwidthMhz)
        assertEquals(1, bundle.mcs)
        assertEquals(InviteFec(8, 12), bundle.videoFec)
        assertEquals(InviteFec(1, 2), bundle.auxFec)
        assertEquals(64, bundle.rxKey.size)
        assertEquals(32, bundle.hopKey.size)
        assertArrayEquals(intArrayOf(0, 1, 2), bundle.radioPorts.asArray())
        assertEquals(
            listOf(
                DroneLinkSpec("drone-a1b2c3", 1, LinkParams.linkId(7, 1)),
                DroneLinkSpec("drone-d4e5f6", 2, LinkParams.linkId(7, 2)),
            ),
            bundle.links(),
        )
        assertEquals(0x0701, bundle.links()[0].linkId)
    }

    @Test
    fun `a round trip through the reference sealer opens`() {
        val phone = JdkInviteCrypto.generateKeyPair()
        val context = InviteContext.of("00112233445566778899aabbccddeeff")
        val sealed = JdkInviteCrypto.seal("hello".toByteArray(), ByteArray(32) { 7 }, phone.public, ByteArray(12) { 1 }, context)
        assertEquals("hello", JdkInviteCrypto.open(phone.secret, sealed, context)!!.decodeToString())
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun String.unhex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
