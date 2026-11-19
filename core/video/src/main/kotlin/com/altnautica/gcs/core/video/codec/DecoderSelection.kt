package com.altnautica.gcs.core.video.codec

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface

/**
 * Which platform decoder a live stream gets, and how it is configured.
 *
 * `MediaCodecList.findDecoderForFormat` returns the first decoder in platform
 * order. That is usually the hardware one on a phone, but nothing guarantees it:
 * a hardware decoder that refuses the probe format is skipped for the software
 * one without a word, and on some devices only a later decoder of the same kind
 * offers low-latency mode. So every decoder for the type is ranked here and
 * opened in order, and the result says which one runs.
 */
data class DecoderCandidate(
    val name: String,
    /** The platform's own flags (API 29): accelerated and not software only. */
    val hardware: Boolean,
    /** Offers `FEATURE_LowLatency` (Android 11+). */
    val lowLatency: Boolean,
    /** An alias name of another decoder in the list. */
    val alias: Boolean,
)

/** The decoder that opened, for the operator and the logs. */
data class DecoderInfo(
    val name: String,
    val hardware: Boolean,
    /** Platform low-latency mode or a vendor low-latency option was accepted. */
    val lowLatency: Boolean,
)

object DecoderRanking {
    /**
     * Hardware before software; among each, low-latency capable first; aliases
     * dropped (the canonical decoder is listed too). Order is otherwise the
     * platform's, which is the vendor's own preference.
     */
    fun rank(candidates: List<DecoderCandidate>): List<DecoderCandidate> =
        candidates.filterNot { it.alias }
            .withIndex()
            .sortedWith(compareBy({ !it.value.hardware }, { !it.value.lowLatency }, { it.index }))
            .map { it.value }

    /**
     * Vendor low-latency options for decoders that predate the platform flag or
     * only honour their own: Qualcomm, Exynos, Kirin, Amlogic. Names as the
     * vendors' extension headers spell them.
     */
    fun vendorLowLatencyKeys(decoder: String): Map<String, Int> {
        val n = decoder.lowercase()
        return when {
            n.startsWith("c2.qti") || n.startsWith("omx.qcom") ->
                mapOf("vendor.qti-ext-dec-picture-order.enable" to 1, "vendor.qti-ext-dec-low-latency.enable" to 1)
            n.startsWith("c2.exynos") || n.startsWith("omx.exynos") ->
                mapOf("vendor.rtc-ext-dec-low-latency.enable" to 1)
            n.startsWith("c2.hisi") || n.startsWith("omx.hisi") ->
                mapOf(
                    "vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-req" to 1,
                    "vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-rdy" to -1,
                )
            n.startsWith("c2.amlogic") || n.startsWith("omx.amlogic") ->
                mapOf("vendor.low-latency.enable" to 1)
            else -> emptyMap()
        }
    }

    /**
     * The configurations to try for one decoder, most to least ambitious: a
     * decoder that rejects an option fails `configure`, so the next attempt
     * drops it. Each entry is (options, whether low latency is confirmed).
     *
     * A decoder usually ignores a key it does not know rather than failing,
     * so an accepted key proves nothing: low latency counts only when the
     * decoder declares `FEATURE_LowLatency` or lists the vendor key among its
     * parameters ([vendor].listed, Android 12+). Unlisted vendor keys are
     * still sent, best effort, on older releases.
     */
    fun attempts(candidate: DecoderCandidate, vendor: VendorKeys): List<Pair<Map<String, Int>, Boolean>> {
        val platform = if (candidate.lowLatency) mapOf(MediaFormat.KEY_LOW_LATENCY to 1) else emptyMap()
        val out = mutableListOf<Pair<Map<String, Int>, Boolean>>()
        if (vendor.keys.isNotEmpty()) out += (platform + vendor.keys) to (candidate.lowLatency || vendor.listed)
        if (platform.isNotEmpty()) out += platform to true
        out += emptyMap<String, Int>() to false
        return out
    }

    /**
     * Walks [ranked] decoders and each one's [attempts] until [tryOpen] returns
     * a decoder; [tryOpen] returns null for a configuration the decoder refused
     * and throws [DecoderUnavailable] for a decoder that cannot be created at
     * all (its remaining attempts are skipped).
     */
    fun <T> openFirst(
        ranked: List<DecoderCandidate>,
        vendorKeys: (DecoderCandidate) -> VendorKeys,
        tryOpen: (DecoderCandidate, Map<String, Int>, Boolean) -> T?,
    ): T? {
        for (candidate in ranked) {
            try {
                for ((options, low) in attempts(candidate, vendorKeys(candidate))) {
                    tryOpen(candidate, options, low)?.let { return it }
                }
            } catch (_: DecoderUnavailable) {
                continue
            }
        }
        return null
    }
}

/** The decoder could not be created; try the next one. */
class DecoderUnavailable(cause: Throwable) : Exception(cause)

/** Vendor low-latency options to send, and whether the decoder listed them itself. */
data class VendorKeys(val keys: Map<String, Int>, val listed: Boolean) {
    companion object {
        val NONE = VendorKeys(emptyMap(), listed = false)
    }
}

/** Ranks the device's decoders for [mime] and opens the best one that configures. */
object PlatformDecoders {
    private const val TAG = "AdosVideoDecoder"

    fun candidates(mime: String, width: Int, height: Int): List<DecoderCandidate> =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, ignoreCase = true) } }
            .filter { info ->
                runCatching { info.getCapabilitiesForType(mime).videoCapabilities?.isSizeSupported(width, height) != false }
                    .getOrDefault(false)
            }
            .map { info ->
                val caps = info.getCapabilitiesForType(mime)
                DecoderCandidate(
                    name = info.name,
                    hardware = info.isHardwareAccelerated && !info.isSoftwareOnly,
                    lowLatency = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                        caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency),
                    alias = info.isAlias,
                )
            }

    /**
     * Opens the first ranked decoder that configures into [surface], trying each
     * one's attempts in turn. Throws only when no decoder for [mime] opens.
     */
    fun open(mime: String, width: Int, height: Int, surface: Surface): Pair<MediaCodec, DecoderInfo> {
        var last: Exception? = null
        val opened = DecoderRanking.openFirst(
            ranked = DecoderRanking.rank(candidates(mime, width, height)),
            vendorKeys = { vendorKeysOffered(it.name) },
        ) { candidate, options, low ->
            val codec = try {
                MediaCodec.createByCodecName(candidate.name)
            } catch (e: Exception) {
                last = e
                Log.w(TAG, "decoder ${candidate.name} could not be created: ${e.message}")
                throw DecoderUnavailable(e)
            }
            try {
                val format = MediaFormat.createVideoFormat(mime, width, height)
                options.forEach { (k, v) -> format.setInteger(k, v) }
                codec.configure(format, surface, null, 0)
                codec.start()
                Log.i(TAG, "decoder ${candidate.name} hw=${candidate.hardware} lowLatency=$low options=${options.keys}")
                codec to DecoderInfo(candidate.name, candidate.hardware, low)
            } catch (e: Exception) {
                last = e
                Log.w(TAG, "decoder ${candidate.name} refused ${options.keys}: ${e.message}")
                runCatching { codec.release() }
                null
            }
        }
        return opened ?: throw IllegalStateException("no $mime decoder opened", last)
    }

    /**
     * The vendor low-latency keys this decoder declares. Android 12 lists a
     * decoder's vendor parameters; before that the keys are tried and a
     * decoder that rejects them is configured again without.
     */
    private fun vendorKeysOffered(name: String): VendorKeys {
        val keys = DecoderRanking.vendorLowLatencyKeys(name)
        if (keys.isEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return VendorKeys(keys, listed = false)
        val supported = runCatching {
            val probe = MediaCodec.createByCodecName(name)
            try {
                probe.supportedVendorParameters.map { it.lowercase() }.toSet()
            } finally {
                probe.release()
            }
        }.getOrDefault(emptySet())
        val offered = keys.filterKeys { it.lowercase() in supported }
        return VendorKeys(offered, listed = offered.isNotEmpty())
    }
}
