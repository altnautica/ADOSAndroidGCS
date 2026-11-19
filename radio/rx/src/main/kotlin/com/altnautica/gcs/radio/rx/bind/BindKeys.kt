package com.altnautica.gcs.radio.rx.bind

import com.altnautica.gcs.core.link.fleet.FleetKeys
import com.altnautica.gcs.radio.rx.WfbReceiver
import java.security.MessageDigest

/**
 * The fleet's radio keys as an over-the-air bind makes them: a keypair as
 * wfb-ng's `wfb_keygen` makes one, the hop/presence pair key the agent
 * derives from `drone.key`, and the key fingerprint the agent records.
 */
object BindKeys {
    const val KEY_BYTES = 64
    private const val HALF = 32
    private val PAIR_KEY_PREFIX = "ados/wfb/hop/v2\n".toByteArray(Charsets.US_ASCII)

    /** A fresh fleet keypair from libsodium's random source. */
    fun generate(): FleetKeys {
        WfbReceiver.loadLibrary()
        val both = checkNotNull(nativeGenerate()) { "key generation failed" }
        check(both.size == 2 * KEY_BYTES) { "key generation returned ${both.size} bytes" }
        val droneKey = both.copyOfRange(0, KEY_BYTES)
        val gsKey = both.copyOfRange(KEY_BYTES, 2 * KEY_BYTES)
        both.fill(0)
        return FleetKeys(droneKey, gsKey, pairKey(droneKey), fingerprint(gsKey))
    }

    /** The hop and presence pair key: SHA-256 of `"ados/wfb/hop/v2\n"` followed by the 64-byte `drone.key`. */
    fun pairKey(droneKey: ByteArray): ByteArray {
        require(droneKey.size == KEY_BYTES) { "drone key must be $KEY_BYTES bytes" }
        return MessageDigest.getInstance("SHA-256").run {
            update(PAIR_KEY_PREFIX)
            update(droneKey)
            digest()
        }
    }

    /** BLAKE2b-8 of a key file's peer-public half, 16 lowercase hex characters, as the agent fingerprints keys. */
    fun fingerprint(keyFile: ByteArray): String {
        require(keyFile.size == KEY_BYTES) { "key must be $KEY_BYTES bytes" }
        return Blake2b.digest(keyFile.copyOfRange(HALF, KEY_BYTES), 8).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** drone.key (64) followed by gs.key (64), or null when libsodium failed. */
    @JvmStatic external fun nativeGenerate(): ByteArray?
}
