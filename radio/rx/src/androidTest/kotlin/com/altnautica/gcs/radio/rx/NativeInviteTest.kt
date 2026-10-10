package com.altnautica.gcs.radio.rx

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altnautica.gcs.radio.rx.invite.InviteContext
import com.altnautica.gcs.radio.rx.invite.InviteFingerprint
import com.altnautica.gcs.radio.rx.invite.NativeInviteCipher
import com.altnautica.gcs.radio.rx.invite.SealedInvite
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** The native invite opener and key derivation against the ground station's vector. */
@RunWith(AndroidJUnit4::class)
class NativeInviteTest {

    private val vector = JSONObject(checkNotNull(javaClass.classLoader!!.getResource("phone-invite-vector.json")).readText())

    private fun hex(key: String) = vector.getString(key).chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun publicKeyAndFingerprintMatch() {
        WfbReceiver.loadLibrary()
        val pub = WfbReceiver.nativePublicKey(hex("phone_secret_key_hex"))!!
        assertArrayEquals(hex("phone_public_key_hex"), pub)
        assertEquals(vector.getString("phone_fingerprint"), InviteFingerprint.of(pub))
    }

    @Test
    fun sealedVectorOpens() {
        val sealed = SealedInvite(hex("gs_ephemeral_public_hex"), hex("nonce_hex"), hex("ciphertext_hex"))
        val plain = NativeInviteCipher().open(hex("phone_secret_key_hex"), sealed, InviteContext.of(vector.getString("invite_id")))
        assertEquals(vector.getString("expected_plaintext_json"), plain!!.decodeToString())
    }

    @Test
    fun wrongContextDoesNotOpen() {
        val sealed = SealedInvite(hex("gs_ephemeral_public_hex"), hex("nonce_hex"), hex("ciphertext_hex"))
        assertNull(NativeInviteCipher().open(hex("phone_secret_key_hex"), sealed, InviteContext.of("ffffffffffffffffffffffffffffffff")))
    }

    @Test
    fun generatedKeyPairIsConsistent() {
        val pair = NativeInviteCipher().generateKeyPair()
        assertArrayEquals(pair.public, WfbReceiver.nativePublicKey(pair.secret))
    }

    @Test
    fun receiverAcceptsTheInviteKeyLayout() {
        val receiver = WfbReceiver(ByteArray(64) { it.toByte() }, 0x0701, intArrayOf(0, 1, 2)) { _, _ -> }
        val stats = receiver.stats()
        receiver.close()
        assertEquals(0L, stats.malformedRecords)
    }
}
