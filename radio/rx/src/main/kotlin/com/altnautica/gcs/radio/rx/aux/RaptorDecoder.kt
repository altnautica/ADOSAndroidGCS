package com.altnautica.gcs.radio.rx.aux

/**
 * A single-block RaptorQ (RFC 6330) decoder for relayed responses: one
 * source block, one sub-block, byte alignment, [symbolSize]-byte symbols
 * carrying a [transferLength]-byte object. Encoding-symbol ids `0 until k`
 * are the source symbols themselves; higher ids are repair symbols, any of
 * which can stand in for a lost source symbol.
 *
 * [push] returns the object once the symbols received rebuild it: at once
 * when every source symbol arrived, otherwise when the constraint system of
 * section 5.4.2 becomes solvable.
 */
internal class RaptorDecoder(private val transferLength: Int, val symbolSize: Int) {
    val k: Int = (transferLength + symbolSize - 1) / symbolSize
    private val row = requireNotNull(RaptorTables.rowFor(k)) { "block of $k symbols exceeds the decoder table" }
    private val kPrime = row.kPrime
    private val s = row.s
    private val h = row.h
    private val w = row.w
    private val l = kPrime + s + h
    private val p = l - w
    private val p1 = smallestPrimeAtLeast(p)
    private val received = LinkedHashMap<Int, ByteArray>()
    private var done = false

    /** Feeds encoding symbol [esi]; the object once it can be rebuilt, else null. */
    fun push(esi: Int, symbol: ByteArray): ByteArray? {
        if (done || symbol.size != symbolSize || esi in received) return null
        received[esi] = symbol
        if ((0 until k).all { it in received }) return finish((0 until k).map { received.getValue(it) })
        if (received.size < k) return null
        val intermediate = solve() ?: return null
        val source = (0 until k).map { isi -> received[isi] ?: encode(intermediate, isi) }
        return finish(source)
    }

    private fun finish(source: List<ByteArray>): ByteArray {
        done = true
        val out = ByteArray(transferLength)
        var at = 0
        for (sym in source) {
            val n = minOf(symbolSize, transferLength - at)
            if (n <= 0) break
            sym.copyInto(out, at, 0, n)
            at += n
        }
        return out
    }

    /** The intermediate symbols C[0 until L], or null while the system is short of rank. */
    private fun solve(): Array<ByteArray>? {
        val isis = ArrayList<Int>()
        val data = ArrayList<ByteArray>()
        for ((esi, sym) in received) {
            isis += if (esi < k) esi else esi + kPrime - k
            data += sym
        }
        for (pad in k until kPrime) {
            isis += pad
            data += ByteArray(symbolSize)
        }
        val m = s + h + isis.size
        val a = Array(m) { ByteArray(l) }
        val d = Array(m) { i -> if (i < s + h) ByteArray(symbolSize) else data[i - s - h].copyOf() }
        ldpcRows(a)
        hdpcRows(a)
        isis.forEachIndexed { r, isi -> encIndices(isi) { col -> a[s + h + r][col] = 1 } }
        return eliminate(a, d)
    }

    private fun ldpcRows(a: Array<ByteArray>) {
        val bCount = w - s
        for (i in 0 until bCount) {
            val step = 1 + i / s
            var b = i % s
            a[b][i] = 1
            b = (b + step) % s
            a[b][i] = 1
            b = (b + step) % s
            a[b][i] = 1
        }
        for (i in 0 until s) {
            a[i][i + bCount] = 1
            a[i][(i % p) + w] = 1
            a[i][((i + 1) % p) + w] = 1
        }
    }

    private fun hdpcRows(a: Array<ByteArray>) {
        val cols = kPrime + s
        val g = Array(h) { IntArray(cols) }
        for (i in 0 until h) g[i][cols - 1] = Gf256.exp(i)
        for (j in cols - 2 downTo 0) {
            for (i in 0 until h) g[i][j] = Gf256.mul(2, g[i][j + 1])
            val r6 = RaptorTables.rand((j + 1).toLong(), 6, h)
            val r7 = RaptorTables.rand((j + 1).toLong(), 7, h - 1)
            g[r6][j] = g[r6][j] xor 1
            val i2 = (r6 + r7 + 1) % h
            g[i2][j] = g[i2][j] xor 1
        }
        for (i in 0 until h) {
            for (j in 0 until cols) a[s + i][j] = g[i][j].toByte()
            a[s + i][cols + i] = 1
        }
    }

    /** Gauss-Jordan over GF(256); the solved symbols in column order, or null when rank-deficient. */
    private fun eliminate(a: Array<ByteArray>, d: Array<ByteArray>): Array<ByteArray>? {
        val m = a.size
        for (col in 0 until l) {
            val pivot = (col until m).firstOrNull { a[it][col].toInt() != 0 } ?: return null
            swap(a, col, pivot)
            swap(d, col, pivot)
            val inv = Gf256.inv(a[col][col].toInt() and 0xFF)
            if (inv != 1) {
                Gf256.scale(a[col], inv)
                Gf256.scale(d[col], inv)
            }
            for (r in 0 until m) {
                val f = a[r][col].toInt() and 0xFF
                if (r == col || f == 0) continue
                Gf256.addScaled(a[r], a[col], f)
                Gf256.addScaled(d[r], d[col], f)
            }
        }
        return Array(l) { d[it] }
    }

    /** Enc[] of section 5.3.5.3: the source symbol with internal symbol id [isi]. */
    private fun encode(c: Array<ByteArray>, isi: Int): ByteArray {
        val out = ByteArray(symbolSize)
        encIndices(isi) { col -> Gf256.addScaled(out, c[col], 1) }
        return out
    }

    /** The intermediate-symbol columns Tuple[K', isi] touches (sections 5.3.5.3 and 5.3.5.4). */
    private inline fun encIndices(isi: Int, f: (Int) -> Unit) {
        var aMul = 53591L + row.j * 997L
        if (aMul % 2 == 0L) aMul += 1
        val bAdd = 10267L * (row.j + 1)
        val y = (bAdd + isi.toLong() * aMul) % 4294967296L
        val v = RaptorTables.rand(y, 0, 1048576)
        val deg = degree(v)
        val a = 1 + RaptorTables.rand(y, 1, w - 1)
        var b = RaptorTables.rand(y, 2, w)
        val d1 = if (deg < 4) 2 + RaptorTables.rand(isi.toLong(), 3, 2) else 2
        val a1 = 1 + RaptorTables.rand(isi.toLong(), 4, p1 - 1)
        var b1 = RaptorTables.rand(isi.toLong(), 5, p1)
        f(b)
        repeat(deg - 1) {
            b = (b + a) % w
            f(b)
        }
        while (b1 >= p) b1 = (b1 + a1) % p1
        f(w + b1)
        repeat(d1 - 1) {
            b1 = (b1 + a1) % p1
            while (b1 >= p) b1 = (b1 + a1) % p1
            f(w + b1)
        }
    }

    private fun degree(v: Int): Int {
        for (d in 1 until RaptorTables.DEGREE.size) {
            if (v < RaptorTables.DEGREE[d]) return minOf(d, w - 2)
        }
        error("degree table exhausted")
    }

    private fun <T> swap(arr: Array<T>, i: Int, j: Int) {
        if (i == j) return
        val t = arr[i]
        arr[i] = arr[j]
        arr[j] = t
    }

    private companion object {
        fun smallestPrimeAtLeast(n: Int): Int {
            var c = maxOf(n, 2)
            while ((2..c / 2).any { c % it == 0 }) c++
            return c
        }
    }
}

/** GF(256) under x^8 + x^4 + x^3 + x^2 + 1 (RFC 6330 section 5.7). */
internal object Gf256 {
    private val EXP = IntArray(510)
    private val LOG = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            EXP[i] = x
            LOG[x] = i
            x = x shl 1
            if (x and 0x100 != 0) x = x xor 0x11D
        }
        for (i in 255 until 510) EXP[i] = EXP[i - 255]
    }

    fun exp(i: Int): Int = EXP[i % 255]

    fun mul(a: Int, b: Int): Int = if (a == 0 || b == 0) 0 else EXP[LOG[a] + LOG[b]]

    fun inv(a: Int): Int = EXP[255 - LOG[a]]

    /** row *= f */
    fun scale(row: ByteArray, f: Int) {
        for (i in row.indices) row[i] = mul(row[i].toInt() and 0xFF, f).toByte()
    }

    /** dst += f * src */
    fun addScaled(dst: ByteArray, src: ByteArray, f: Int) {
        if (f == 1) {
            for (i in dst.indices) dst[i] = (dst[i].toInt() xor src[i].toInt()).toByte()
            return
        }
        val lf = LOG[f]
        for (i in dst.indices) {
            val v = src[i].toInt() and 0xFF
            if (v != 0) dst[i] = (dst[i].toInt() xor EXP[LOG[v] + lf]).toByte()
        }
    }
}
