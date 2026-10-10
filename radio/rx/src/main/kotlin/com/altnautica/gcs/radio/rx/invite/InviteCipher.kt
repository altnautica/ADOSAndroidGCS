package com.altnautica.gcs.radio.rx.invite

import com.altnautica.gcs.radio.rx.WfbReceiver
import javax.inject.Inject
import javax.inject.Singleton

/** The phone's X25519 key pair for one invite request; the secret never leaves memory. */
class PhoneKeyPair(val secret: ByteArray, val public: ByteArray) {
    val fingerprint: String get() = InviteFingerprint.of(public)

    fun wipe() = secret.fill(0)
}

/** The sealed bundle a ground station returns once its operator approves the phone. */
class SealedInvite(val gsEphemeralPublic: ByteArray, val nonce: ByteArray, val ciphertext: ByteArray)

/**
 * Key agreement and opening for phone invites:
 * X25519(phone secret, ground ephemeral public) → shared;
 * key = HMAC-SHA256(HMAC-SHA256(0x00 × 32, shared), context || 0x01);
 * ChaCha20-Poly1305 with a 12-byte nonce and no associated data.
 */
interface InviteCipher {
    fun generateKeyPair(): PhoneKeyPair

    /** The plaintext, or null when the ciphertext does not authenticate. */
    fun open(phoneSecret: ByteArray, sealed: SealedInvite, context: ByteArray): ByteArray?
}

/** [InviteCipher] backed by the native libsodium build. */
@Singleton
class NativeInviteCipher @Inject constructor() : InviteCipher {

    override fun generateKeyPair(): PhoneKeyPair {
        WfbReceiver.loadLibrary()
        val pair = checkNotNull(WfbReceiver.nativeGenerateKeyPair()) { "key generation failed" }
        return PhoneKeyPair(pair.copyOfRange(0, KEY_BYTES), pair.copyOfRange(KEY_BYTES, 2 * KEY_BYTES)).also { pair.fill(0) }
    }

    override fun open(phoneSecret: ByteArray, sealed: SealedInvite, context: ByteArray): ByteArray? {
        WfbReceiver.loadLibrary()
        return WfbReceiver.nativeOpenInvite(phoneSecret, sealed.gsEphemeralPublic, sealed.nonce, sealed.ciphertext, context)
    }

    private companion object {
        const val KEY_BYTES = 32
    }
}
