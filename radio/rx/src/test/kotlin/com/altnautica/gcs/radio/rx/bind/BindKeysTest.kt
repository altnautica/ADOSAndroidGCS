package com.altnautica.gcs.radio.rx.bind

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class BindKeysTest {

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    @Test
    fun `the pair key matches the agent's derivation`() {
        // Printed by the agent's own derive_pair_key for drone.key bytes 0..63.
        val droneKey = ByteArray(64) { it.toByte() }
        assertEquals("1ec1936c30d3494449634dc2d0163c09b271099f2cf9d20e8c5b495d8d8ad4f7", hex(BindKeys.pairKey(droneKey)))
    }

    @Test
    fun `the key fingerprint matches the agent's`() {
        // Printed by the agent's own read_public_fingerprint for key byte i = (7i + 3) & 0xff.
        val gsKey = ByteArray(64) { ((it * 7 + 3) and 0xff).toByte() }
        assertEquals("cb6f61afdb7fe5d8", BindKeys.fingerprint(gsKey))
    }

    @Test
    fun `blake2b matches the reference at several lengths`() {
        // Python hashlib.blake2b.
        assertEquals(
            "786a02f742015903c6c6fd852552d272912f4740e15847618a86e217f71f5419d25e1031afee585313896444934eb04b903a685b1448b755d56f701afe9be2ce",
            hex(Blake2b.digest(ByteArray(0), 64)),
        )
        assertEquals(
            "ba80a53f981c4d0d6a2797b69f12f6e94c212f14685ac4b74b12bb6fdbffa2d17d87c5392aab792dc252d5de4533cc9518d38aa8dbf1925ab92386edd4009923",
            hex(Blake2b.digest("abc".toByteArray(), 64)),
        )
        assertEquals("d325a9e186fa61cc", hex(Blake2b.digest(ByteArray(300) { it.toByte() }, 8)))
        assertEquals("c3582f71ebb2be66fa5dd750f80baae97554f3b015663c8be377cfcb2488c1d1", hex(Blake2b.digest(ByteArray(128) { it.toByte() }, 32)))
    }

    @Test
    fun `the bind link id is wfb-ng's hash of drone_bind`() {
        // Python: int.from_bytes(hashlib.sha1(b"drone_bind").digest()[:3], "big")
        assertEquals(0xa0b44d, BindProfile.LINK_ID)
        assertEquals(0xa0b44d7fL, BindProfile.channelId(BindProfile.RX_PORT))
        assertEquals(0xa0b44dffL, BindProfile.channelId(BindProfile.TX_PORT))
    }

    @Test
    fun `the vendored bind key is upstream's shared key`() {
        val key = File("src/main/assets/${BindProfile.BIND_KEY_ASSET}").readBytes()
        assertEquals(64, key.size)
        assertEquals(
            "d6c8a83d6f135273562d99efdfaadf14ee89f9e3589a01ca99863fe7bbc7a098",
            hex(MessageDigest.getInstance("SHA-256").digest(key)),
        )
    }
}
