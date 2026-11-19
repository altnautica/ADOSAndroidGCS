package com.altnautica.gcs.radio.rx.bind

import java.security.MessageDigest

/**
 * The radio profile of an over-the-air bind, the ground's half of wfb-ng's
 * `gs_bind` profile as the agent provisions it: the home channel at 20 MHz,
 * MCS1 with STBC, LDPC and the short guard interval off, tunnel FEC 1/2, the
 * shared bind key, link domain `drone_bind`, the ground transmitting on
 * stream 0xff and receiving on 0x7f, addresses 10.5.99.1 (ground) and
 * 10.5.99.2 (drone), the drone's bind server on TCP 5555.
 */
object BindProfile {
    const val CHANNEL = 149
    const val BANDWIDTH_MHZ = 20
    const val MCS = 1
    const val FEC_K = 1
    const val FEC_N = 2
    const val TX_PORT = 0xff
    const val RX_PORT = 0x7f
    const val LINK_DOMAIN = "drone_bind"

    /** wfb-ng's link id for [LINK_DOMAIN]: the first 3 bytes of its SHA-1, big-endian. */
    val LINK_ID: Int = linkIdOf(LINK_DOMAIN)

    const val GROUND_IP = 0x0a056301 // 10.5.99.1
    const val DRONE_IP = 0x0a056302 // 10.5.99.2
    const val BIND_TCP_PORT = 5555

    /** Asset path of upstream wfb-ng's shared bind key (64 bytes), the same file on every rig. */
    const val BIND_KEY_ASSET = "wfb/bind.key"

    fun linkIdOf(domain: String): Int {
        val d = MessageDigest.getInstance("SHA-1").digest(domain.toByteArray(Charsets.UTF_8))
        return ((d[0].toInt() and 0xff) shl 16) or ((d[1].toInt() and 0xff) shl 8) or (d[2].toInt() and 0xff)
    }

    fun channelId(port: Int): Long = (LINK_ID.toLong() shl 8) + port
}
