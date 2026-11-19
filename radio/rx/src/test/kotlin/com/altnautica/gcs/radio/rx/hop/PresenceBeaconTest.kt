package com.altnautica.gcs.radio.rx.hop

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Golden frames from the agent's own encoder (`build_presence_beacon` with
 * role ground and RSSI 0, as the box emits), under the pair key 00 01 .. 1f.
 */
class PresenceBeaconTest {

    private val key = ByteArray(32) { it.toByte() }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    @Test
    fun `a 16-byte device id fills the id field`() {
        assertEquals(
            "41443035505245530170686f6e652d30313233343536373839029d000000000000000000" +
                "eda1f888b032154c6c876d013755f177592fa6e90da7d0eaf3c25137d640a97a",
            hex(PresenceBeacon.build("phone-0123456789", 157, 0L, key)),
        )
    }

    @Test
    fun `a short device id is zero-padded and the epoch is big-endian`() {
        assertEquals(
            "41443035505245530167732d31000000000000000000000000029500000001a1ee565e00" +
                "21faa1e1a27734da2773e3792e7182a58fef0b9663901ce7381dcb139a6b7efd",
            hex(PresenceBeacon.build("gs-1", 149, 1_795_000_000_000L, key)),
        )
    }

    @Test
    fun `a long device id is cut to 16 bytes`() {
        assertEquals(
            "414430355052455301612d6465766963652d69642d6c6f6e67022400000000000000002a" +
                "5fbcfba3eeb27c58976966ecbc323b8f3ec0971119947d947d9203c477c0345d",
            hex(PresenceBeacon.build("a-device-id-longer-than-16", 36, 42L, key)),
        )
    }
}
