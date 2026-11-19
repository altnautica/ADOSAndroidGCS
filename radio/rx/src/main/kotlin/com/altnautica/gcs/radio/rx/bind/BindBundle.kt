package com.altnautica.gcs.radio.rx.bind

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.GZIPOutputStream

/**
 * What a bind sends the drone: a gzip-compressed tar holding `drone.key` and
 * `checksum.txt` (one `sha1sum` line per member), base64 without line breaks,
 * as wfb-ng's bind client packs it. The drone's bind server checks the
 * checksums, then installs the members it finds.
 */
object BindBundle {
    const val DRONE_KEY = "drone.key"
    const val CHECKSUMS = "checksum.txt"
    private const val BLOCK = 512

    /** The base64 the `BIND` line carries for [droneKey], with members stamped [mtimeSec]. */
    fun encode(droneKey: ByteArray, mtimeSec: Long = System.currentTimeMillis() / 1000): String =
        Base64.getEncoder().encodeToString(gzip(tar(members(droneKey), mtimeSec)))

    /** The members in archive order: `drone.key`, then `checksum.txt` covering it. */
    fun members(droneKey: ByteArray): List<Pair<String, ByteArray>> {
        val sha1 = MessageDigest.getInstance("SHA-1").digest(droneKey).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return listOf(DRONE_KEY to droneKey, CHECKSUMS to "$sha1  $DRONE_KEY\n".toByteArray(Charsets.US_ASCII))
    }

    /** A POSIX ustar archive of regular files under `./`. */
    fun tar(members: List<Pair<String, ByteArray>>, mtimeSec: Long): ByteArray {
        val out = ByteArrayOutputStream()
        for ((name, data) in members) {
            out.write(header("./$name", data.size.toLong(), mtimeSec))
            out.write(data)
            val pad = (BLOCK - data.size % BLOCK) % BLOCK
            out.write(ByteArray(pad))
        }
        out.write(ByteArray(2 * BLOCK))
        return out.toByteArray()
    }

    private fun header(name: String, size: Long, mtimeSec: Long): ByteArray {
        val h = ByteArray(BLOCK)
        fun put(offset: Int, value: String) = value.toByteArray(Charsets.US_ASCII).copyInto(h, offset)
        fun octal(offset: Int, width: Int, value: Long) = put(offset, value.toString(8).padStart(width - 1, '0'))
        require(name.length < 100) { "member name too long" }
        put(0, name)
        octal(100, 8, 0b110_100_100) // 0644
        octal(108, 8, 0)
        octal(116, 8, 0)
        octal(124, 12, size)
        octal(136, 12, mtimeSec)
        put(148, "        ")
        h[156] = '0'.code.toByte()
        put(257, "ustar\u0000")
        put(263, "00")
        put(265, "root")
        put(297, "root")
        val sum = h.sumOf { it.toInt() and 0xff }
        put(148, sum.toString(8).padStart(6, '0') + "\u0000 ")
        return h
    }

    private fun gzip(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(data) }
        return out.toByteArray()
    }
}
