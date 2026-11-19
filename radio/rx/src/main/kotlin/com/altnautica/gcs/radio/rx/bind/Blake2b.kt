package com.altnautica.gcs.radio.rx.bind

/**
 * Unkeyed BLAKE2b (RFC 7693) with any digest length from 1 to 64 bytes. The
 * digest length is a parameter of the hash, not a truncation: an 8-byte
 * BLAKE2b differs from the first 8 bytes of a 64-byte one.
 */
internal object Blake2b {
    private val IV = longArrayOf(
        0x6a09e667f3bcc908, -0x4498517a7b3558c5, 0x3c6ef372fe94f82b, -0x5ab00ac5a0e2c90f,
        0x510e527fade682d1, -0x64fa9773d4c193e1, 0x1f83d9abfb41bd6b, 0x5be0cd19137e2179,
    )

    private val SIGMA = arrayOf(
        intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15),
        intArrayOf(14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3),
        intArrayOf(11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4),
        intArrayOf(7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8),
        intArrayOf(9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13),
        intArrayOf(2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9),
        intArrayOf(12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11),
        intArrayOf(13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10),
        intArrayOf(6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5),
        intArrayOf(10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0),
    )

    fun digest(input: ByteArray, outLen: Int): ByteArray {
        require(outLen in 1..64) { "digest length $outLen out of range" }
        val h = IV.copyOf()
        h[0] = h[0] xor (0x01010000L or outLen.toLong())
        var counter = 0L
        var offset = 0
        val block = ByteArray(128)
        // Every full block but the last is compressed as it comes; the last (possibly empty) is final.
        while (input.size - offset > 128) {
            input.copyInto(block, 0, offset, offset + 128)
            counter += 128
            compress(h, block, counter, last = false)
            offset += 128
        }
        block.fill(0)
        input.copyInto(block, 0, offset, input.size)
        counter += (input.size - offset)
        compress(h, block, counter, last = true)
        val out = ByteArray(outLen)
        for (i in 0 until outLen) out[i] = (h[i / 8] ushr (8 * (i % 8))).toByte()
        return out
    }

    private fun compress(h: LongArray, block: ByteArray, counter: Long, last: Boolean) {
        val m = LongArray(16) { i ->
            var w = 0L
            for (b in 7 downTo 0) w = (w shl 8) or (block[i * 8 + b].toLong() and 0xff)
            w
        }
        val v = LongArray(16)
        h.copyInto(v)
        IV.copyInto(v, 8)
        v[12] = v[12] xor counter
        if (last) v[14] = v[14].inv()
        for (r in 0 until 12) {
            val s = SIGMA[r % 10]
            g(v, 0, 4, 8, 12, m[s[0]], m[s[1]])
            g(v, 1, 5, 9, 13, m[s[2]], m[s[3]])
            g(v, 2, 6, 10, 14, m[s[4]], m[s[5]])
            g(v, 3, 7, 11, 15, m[s[6]], m[s[7]])
            g(v, 0, 5, 10, 15, m[s[8]], m[s[9]])
            g(v, 1, 6, 11, 12, m[s[10]], m[s[11]])
            g(v, 2, 7, 8, 13, m[s[12]], m[s[13]])
            g(v, 3, 4, 9, 14, m[s[14]], m[s[15]])
        }
        for (i in 0 until 8) h[i] = h[i] xor v[i] xor v[i + 8]
    }

    private fun g(v: LongArray, a: Int, b: Int, c: Int, d: Int, x: Long, y: Long) {
        v[a] = v[a] + v[b] + x
        v[d] = (v[d] xor v[a]).rotateRight(32)
        v[c] = v[c] + v[d]
        v[b] = (v[b] xor v[c]).rotateRight(24)
        v[a] = v[a] + v[b] + y
        v[d] = (v[d] xor v[a]).rotateRight(16)
        v[c] = v[c] + v[d]
        v[b] = (v[b] xor v[c]).rotateRight(63)
    }
}
