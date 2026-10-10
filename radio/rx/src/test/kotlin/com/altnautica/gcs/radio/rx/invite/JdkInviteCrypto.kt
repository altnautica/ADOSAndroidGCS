package com.altnautica.gcs.radio.rx.invite

import java.math.BigInteger
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.NamedParameterSpec
import java.security.spec.XECPrivateKeySpec
import java.security.spec.XECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Reference implementation of the invite construction on the JDK (X25519,
 * HMAC-SHA256, ChaCha20-Poly1305), used on the host to check the agreed
 * wire contract against the ground station's fixture. The app itself uses
 * the native build ([NativeInviteCipher]) because the platform lacks X25519
 * before API 33.
 */
object JdkInviteCrypto : InviteCipher {

    private val keyFactory = KeyFactory.getInstance("XDH")

    fun publicKey(secret: ByteArray): ByteArray {
        // The JDK has no scalar-mult-base call; derive through a key agreement
        // with the base point u = 9.
        val base = ByteArray(32).also { it[0] = 9 }
        return x25519(secret, base)
    }

    fun x25519(secret: ByteArray, peerPublic: ByteArray): ByteArray {
        val priv = keyFactory.generatePrivate(XECPrivateKeySpec(NamedParameterSpec.X25519, secret))
        val pub = keyFactory.generatePublic(XECPublicKeySpec(NamedParameterSpec.X25519, littleEndian(peerPublic)))
        return KeyAgreement.getInstance("XDH").run {
            init(priv)
            doPhase(pub, true)
            generateSecret()
        }
    }

    fun sessionKey(shared: ByteArray, context: ByteArray): ByteArray {
        val prk = hmac(ByteArray(32), shared)
        return hmac(prk, context + byteArrayOf(0x01))
    }

    fun seal(plaintext: ByteArray, gsEphemeralSecret: ByteArray, phonePublic: ByteArray, nonce: ByteArray, context: ByteArray): SealedInvite {
        val key = sessionKey(x25519(gsEphemeralSecret, phonePublic), context)
        val cipher = Cipher.getInstance("ChaCha20-Poly1305")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
        return SealedInvite(publicKey(gsEphemeralSecret), nonce, cipher.doFinal(plaintext))
    }

    override fun generateKeyPair(): PhoneKeyPair {
        val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return PhoneKeyPair(secret, publicKey(secret))
    }

    override fun open(phoneSecret: ByteArray, sealed: SealedInvite, context: ByteArray): ByteArray? {
        val key = sessionKey(x25519(phoneSecret, sealed.gsEphemeralPublic), context)
        return try {
            val cipher = Cipher.getInstance("ChaCha20-Poly1305")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(sealed.nonce))
            cipher.doFinal(sealed.ciphertext)
        } catch (_: javax.crypto.AEADBadTagException) {
            null
        }
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }

    private fun littleEndian(u: ByteArray): BigInteger {
        val be = u.reversedArray()
        be[0] = (be[0].toInt() and 0x7F).toByte()
        return BigInteger(1, be)
    }
}
