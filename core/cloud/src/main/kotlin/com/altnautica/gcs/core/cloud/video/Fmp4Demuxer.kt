package com.altnautica.gcs.core.cloud.video

import java.io.ByteArrayOutputStream

enum class VideoCodec(val mime: String) { Avc("video/avc"), Hevc("video/hevc") }

/** What the init segment says about the one video track. */
class Fmp4Track(
    val codec: VideoCodec,
    val timescale: Long,
    /** Parameter sets (SPS/PPS, or VPS/SPS/PPS) in Annex-B form. */
    val parameterSets: ByteArray,
    val nalLengthSize: Int,
)

/** One coded picture in Annex-B form; key frames carry the parameter sets in front. */
class Fmp4Sample(val data: ByteArray, val keyFrame: Boolean, val presentationUs: Long)

class Fmp4Exception(message: String) : Exception(message)

/**
 * Fragmented MP4 to coded pictures: reads the init segment (`ftyp` + `moov`)
 * for the codec, timescale and parameter sets, then each `moof` + `mdat`
 * fragment into samples. Bytes may arrive in any chunking; only complete
 * top-level boxes are parsed. Every read is bounds-checked and box sizes are
 * capped at [MAX_BOX_BYTES]: corrupt or hostile input throws [Fmp4Exception],
 * never an unchecked runtime exception.
 */
class Fmp4Demuxer {

    var track: Fmp4Track? = null
        private set

    private val pending = ByteArrayOutputStream()
    private var moof: ByteArray? = null

    /** Feeds bytes; returns the samples of every fragment they completed. Throws [Fmp4Exception] on unusable input. */
    fun push(bytes: ByteArray): List<Fmp4Sample> {
        // The unparsed remainder is always one incomplete box, and box sizes are capped at
        // MAX_BOX_BYTES, so `pending` never grows past that cap.
        pending.write(bytes)
        val buf = pending.toByteArray()
        var pos = 0
        val out = ArrayList<Fmp4Sample>()
        try {
            while (buf.size - pos >= 8) {
                val size = boxSize(buf, pos, buf.size) ?: break
                if (size > buf.size - pos) break
                val type = fourcc(buf, pos + 4)
                val box = buf.copyOfRange(pos, pos + size.toInt())
                when (type) {
                    "moov" -> track = parseInit(box)
                    "moof" -> moof = box
                    "mdat" -> {
                        val m = moof
                        val t = track
                        if (m != null && t != null) out += samples(t, m, box)
                        moof = null
                    }
                }
                pos += size.toInt()
            }
        } catch (e: IndexOutOfBoundsException) {
            throw Fmp4Exception("Truncated box: ${e.message}")
        } catch (e: IllegalArgumentException) {
            throw Fmp4Exception("Corrupt box: ${e.message}")
        } catch (e: NegativeArraySizeException) {
            throw Fmp4Exception("Corrupt box length")
        }
        pending.reset()
        if (pos < buf.size) pending.write(buf, pos, buf.size - pos)
        return out
    }

    companion object {
        private val START_CODE = byteArrayOf(0, 0, 0, 1)

        /** Largest box accepted (a key frame at relay bitrates is far smaller). */
        const val MAX_BOX_BYTES = 16L * 1024 * 1024

        /** Most samples one `trun` may declare. */
        const val MAX_SAMPLES_PER_RUN = 4096

        private fun need(b: ByteArray, p: Int, n: Int) {
            if (p < 0 || n < 0 || p > b.size - n) throw Fmp4Exception("Read of $n bytes at $p past a ${b.size}-byte box")
        }

        internal fun u32(b: ByteArray, p: Int): Long {
            need(b, p, 4)
            return ((b[p].toLong() and 0xFF) shl 24) or ((b[p + 1].toLong() and 0xFF) shl 16) or
                ((b[p + 2].toLong() and 0xFF) shl 8) or (b[p + 3].toLong() and 0xFF)
        }

        internal fun u16(b: ByteArray, p: Int): Int {
            need(b, p, 2)
            return ((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF)
        }

        private fun u8(b: ByteArray, p: Int): Int {
            need(b, p, 1)
            return b[p].toInt() and 0xFF
        }

        internal fun u64(b: ByteArray, p: Int): Long = (u32(b, p) shl 32) or u32(b, p + 4)

        internal fun fourcc(b: ByteArray, p: Int): String {
            need(b, p, 4)
            return String(b, p, 4, Charsets.US_ASCII)
        }

        /** Size of the box at [p], or null when its header is incomplete. */
        private fun boxSize(b: ByteArray, p: Int, end: Int): Long? {
            val s = u32(b, p)
            return when (s) {
                1L -> if (end - p >= 16) u64(b, p + 8) else null
                0L -> (end - p).toLong()
                else -> s
            }.also {
                if (it != null && (it < 8 || it > MAX_BOX_BYTES)) throw Fmp4Exception("Corrupt box size $it")
            }
        }

        private fun headerSize(b: ByteArray, p: Int) = if (u32(b, p) == 1L) 16 else 8

        /** Child boxes of the container spanning [start, end). */
        private fun children(b: ByteArray, start: Int, end: Int): List<Pair<String, IntRange>> {
            val list = ArrayList<Pair<String, IntRange>>()
            var p = start
            while (end - p >= 8) {
                val size = boxSize(b, p, end) ?: break
                if (size > end - p) break
                list += fourcc(b, p + 4) to (p until p + size.toInt())
                p += size.toInt()
            }
            return list
        }

        private fun child(b: ByteArray, parent: IntRange, type: String, skip: Int = 0): IntRange? =
            children(b, parent.first + headerSize(b, parent.first) + skip, parent.last + 1)
                .firstOrNull { it.first == type }?.second

        internal fun parseInit(moov: ByteArray): Fmp4Track {
            for ((type, trak) in children(moov, headerSize(moov, 0), moov.size)) {
                if (type != "trak") continue
                val mdia = child(moov, trak, "mdia") ?: continue
                val hdlr = child(moov, mdia, "hdlr") ?: continue
                if (fourcc(moov, hdlr.first + 16) != "vide") continue
                val mdhd = child(moov, mdia, "mdhd") ?: continue
                val v = u8(moov, mdhd.first + 8)
                val timescale = if (v == 1) u32(moov, mdhd.first + 28) else u32(moov, mdhd.first + 20)
                val stsd = child(moov, mdia, "minf")?.let { child(moov, it, "stbl") }?.let { child(moov, it, "stsd") } ?: continue
                val entries = children(moov, stsd.first + 16, stsd.last + 1)
                for ((format, entry) in entries) {
                    when (format) {
                        "avc1", "avc3" -> {
                            val avcC = child(moov, entry, "avcC", skip = 78) ?: throw Fmp4Exception("H.264 track without avcC")
                            return avcTrack(moov, avcC.first + 8, timescale)
                        }
                        "hvc1", "hev1" -> {
                            val hvcC = child(moov, entry, "hvcC", skip = 78) ?: throw Fmp4Exception("H.265 track without hvcC")
                            return hevcTrack(moov, hvcC.first + 8, timescale)
                        }
                        else -> throw Fmp4Exception("Unsupported video codec $format")
                    }
                }
            }
            throw Fmp4Exception("The stream's first segment carries no video track")
        }

        private fun avcTrack(b: ByteArray, p0: Int, timescale: Long): Fmp4Track {
            val lengthSize = (u8(b, p0 + 4) and 0x03) + 1
            val ps = ByteArrayOutputStream()
            var p = p0 + 5
            val numSps = u8(b, p) and 0x1F
            p += 1
            repeat(numSps) { p = copyNal(b, p, ps) }
            val numPps = u8(b, p)
            p += 1
            repeat(numPps) { p = copyNal(b, p, ps) }
            return Fmp4Track(VideoCodec.Avc, timescale, ps.toByteArray(), lengthSize)
        }

        private fun hevcTrack(b: ByteArray, p0: Int, timescale: Long): Fmp4Track {
            val lengthSize = (u8(b, p0 + 21) and 0x03) + 1
            val ps = ByteArrayOutputStream()
            val arrays = u8(b, p0 + 22)
            var p = p0 + 23
            repeat(arrays) {
                val count = u16(b, p + 1)
                p += 3
                repeat(count) { p = copyNal(b, p, ps) }
            }
            return Fmp4Track(VideoCodec.Hevc, timescale, ps.toByteArray(), lengthSize)
        }

        /** Copies one u16-length-prefixed NAL as Annex-B; returns the next position. */
        private fun copyNal(b: ByteArray, p: Int, out: ByteArrayOutputStream): Int {
            val len = u16(b, p)
            need(b, p + 2, len)
            out.write(START_CODE)
            out.write(b, p + 2, len)
            return p + 2 + len
        }

        internal fun samples(track: Fmp4Track, moof: ByteArray, mdat: ByteArray): List<Fmp4Sample> {
            val out = ArrayList<Fmp4Sample>()
            for ((type, traf) in children(moof, headerSize(moof, 0), moof.size)) {
                if (type != "traf") continue
                val tfhd = child(moof, traf, "tfhd") ?: continue
                val tfFlags = (u32(moof, tfhd.first + 8) and 0xFFFFFF).toInt()
                var q = tfhd.first + 16
                if (tfFlags and 0x01 != 0) q += 8
                if (tfFlags and 0x02 != 0) q += 4
                var defDuration = 0L
                var defSize = 0L
                var defFlags = 0L
                if (tfFlags and 0x08 != 0) { defDuration = u32(moof, q); q += 4 }
                if (tfFlags and 0x10 != 0) { defSize = u32(moof, q); q += 4 }
                if (tfFlags and 0x20 != 0) { defFlags = u32(moof, q) }

                val tfdt = child(moof, traf, "tfdt")
                var dts = when {
                    tfdt == null -> 0L
                    u8(moof, tfdt.first + 8) == 1 -> u64(moof, tfdt.first + 12)
                    else -> u32(moof, tfdt.first + 12)
                }
                val mdatHeader = headerSize(mdat, 0)
                for ((ctype, trun) in children(moof, traf.first + headerSize(moof, traf.first), traf.last + 1)) {
                    if (ctype != "trun") continue
                    val flags = (u32(moof, trun.first + 8) and 0xFFFFFF).toInt()
                    val count = u32(moof, trun.first + 12)
                    if (count > MAX_SAMPLES_PER_RUN) throw Fmp4Exception("trun declares $count samples")
                    var r = trun.first + 16
                    // Data offsets count from the start of the moof; the mdat follows it directly.
                    var dataPos = if (flags and 0x01 != 0) {
                        (u32(moof, r).toInt() - moof.size).also { r += 4 }
                    } else {
                        mdatHeader
                    }
                    var firstFlags: Long? = null
                    if (flags and 0x04 != 0) { firstFlags = u32(moof, r); r += 4 }
                    for (i in 0 until count.toInt()) {
                        var duration = defDuration
                        var size = defSize
                        var sFlags = if (i == 0 && firstFlags != null) firstFlags else defFlags
                        var cto = 0L
                        if (flags and 0x100 != 0) { duration = u32(moof, r); r += 4 }
                        if (flags and 0x200 != 0) { size = u32(moof, r); r += 4 }
                        if (flags and 0x400 != 0) { val f = u32(moof, r); r += 4; if (!(i == 0 && firstFlags != null)) sFlags = f }
                        if (flags and 0x800 != 0) { cto = u32(moof, r).toInt().toLong(); r += 4 }
                        if (dataPos < 0 || dataPos + size > mdat.size) throw Fmp4Exception("Sample outside its mdat")
                        val annexB = toAnnexB(mdat, dataPos, size.toInt(), track.nalLengthSize)
                        val hasFlags = flags and 0x400 != 0 || (i == 0 && firstFlags != null) || tfFlags and 0x20 != 0
                        val key = if (hasFlags) sFlags and 0x00010000L == 0L else containsIdr(track.codec, annexB)
                        val data = if (key) track.parameterSets + annexB else annexB
                        val ptsUs = if (track.timescale > 0) (dts + cto) * 1_000_000L / track.timescale else 0L
                        out += Fmp4Sample(data, key, ptsUs)
                        dataPos += size.toInt()
                        dts += duration
                    }
                }
            }
            return out
        }

        /** Length-prefixed NAL units to start-code-prefixed ones. */
        internal fun toAnnexB(b: ByteArray, start: Int, length: Int, lengthSize: Int): ByteArray {
            val out = ByteArrayOutputStream(length + 16)
            var p = start
            val end = start + length
            while (p + lengthSize <= end) {
                var n = 0
                for (k in 0 until lengthSize) n = (n shl 8) or (b[p + k].toInt() and 0xFF)
                p += lengthSize
                if (n <= 0 || p + n > end) break
                out.write(START_CODE)
                out.write(b, p, n)
                p += n
            }
            return out.toByteArray()
        }

        private fun containsIdr(codec: VideoCodec, annexB: ByteArray): Boolean {
            var i = 0
            while (i + 4 < annexB.size) {
                if (annexB[i] == 0.toByte() && annexB[i + 1] == 0.toByte() && annexB[i + 2] == 0.toByte() && annexB[i + 3] == 1.toByte()) {
                    val h = annexB[i + 4].toInt() and 0xFF
                    val idr = when (codec) {
                        VideoCodec.Avc -> h and 0x1F == 5
                        VideoCodec.Hevc -> (h shr 1) and 0x3F in 16..21
                    }
                    if (idr) return true
                    i += 4
                } else {
                    i++
                }
            }
            return false
        }
    }
}
