package com.altnautica.gcs.core.video.codec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DecoderRankingTest {

    private fun c(name: String, hw: Boolean, low: Boolean = false, alias: Boolean = false) =
        DecoderCandidate(name, hardware = hw, lowLatency = low, alias = alias)

    @Test
    fun `hardware ranks ahead of a software decoder listed first`() {
        val ranked = DecoderRanking.rank(
            listOf(c("c2.android.avc.decoder", hw = false), c("c2.qti.avc.decoder", hw = true)),
        )
        assertEquals(listOf("c2.qti.avc.decoder", "c2.android.avc.decoder"), ranked.map { it.name })
    }

    @Test
    fun `a later hardware decoder with low latency ranks ahead of the first one without`() {
        val ranked = DecoderRanking.rank(
            listOf(
                c("c2.vendor.avc.decoder", hw = true),
                c("c2.vendor.avc.decoder.low_latency", hw = true, low = true),
                c("c2.android.avc.decoder", hw = false),
            ),
        )
        assertEquals("c2.vendor.avc.decoder.low_latency", ranked.first().name)
        assertEquals("c2.android.avc.decoder", ranked.last().name)
    }

    @Test
    fun `aliases are dropped and platform order is kept among equals`() {
        val ranked = DecoderRanking.rank(
            listOf(
                c("OMX.qcom.video.decoder.avc", hw = true, alias = true),
                c("c2.qti.avc.decoder", hw = true),
                c("c2.qti.avc.decoder.secure", hw = true),
            ),
        )
        assertEquals(listOf("c2.qti.avc.decoder", "c2.qti.avc.decoder.secure"), ranked.map { it.name })
    }

    @Test
    fun `an emulator shaped list keeps its software decoder`() {
        val ranked = DecoderRanking.rank(listOf(c("c2.android.avc.decoder", hw = false)))
        assertEquals(listOf("c2.android.avc.decoder"), ranked.map { it.name })
    }

    @Test
    fun `vendor low latency keys only for the vendors that define them`() {
        assertEquals(
            setOf("vendor.qti-ext-dec-picture-order.enable", "vendor.qti-ext-dec-low-latency.enable"),
            DecoderRanking.vendorLowLatencyKeys("c2.qti.avc.decoder").keys,
        )
        assertEquals(setOf("vendor.rtc-ext-dec-low-latency.enable"), DecoderRanking.vendorLowLatencyKeys("c2.exynos.h264.decoder").keys)
        assertEquals(emptyMap<String, Int>(), DecoderRanking.vendorLowLatencyKeys("c2.android.avc.decoder"))
        assertEquals(emptyMap<String, Int>(), DecoderRanking.vendorLowLatencyKeys("c2.mtk.avc.decoder"))
    }

    @Test
    fun `attempts drop the riskiest options first and always end plain`() {
        val vendor = VendorKeys(mapOf("vendor.qti-ext-dec-low-latency.enable" to 1), listed = true)
        val withBoth = DecoderRanking.attempts(c("c2.qti.avc.decoder", hw = true, low = true), vendor)
        assertEquals(
            listOf(
                setOf("low-latency", "vendor.qti-ext-dec-low-latency.enable") to true,
                setOf("low-latency") to true,
                emptySet<String>() to false,
            ),
            withBoth.map { (o, low) -> o.keys to low },
        )
        val plain = DecoderRanking.attempts(c("c2.android.avc.decoder", hw = false), VendorKeys.NONE)
        assertEquals(listOf(emptySet<String>() to false), plain.map { (o, low) -> o.keys to low })
    }

    @Test
    fun `vendor keys the decoder never listed are sent but not counted as low latency`() {
        // A decoder ignores a key it does not know, so configure succeeding proves nothing.
        val unlisted = VendorKeys(mapOf("vendor.rtc-ext-dec-low-latency.enable" to 1), listed = false)
        val attempts = DecoderRanking.attempts(c("c2.exynos.h264.decoder", hw = true), unlisted)
        assertEquals(
            listOf(setOf("vendor.rtc-ext-dec-low-latency.enable") to false, emptySet<String>() to false),
            attempts.map { (o, low) -> o.keys to low },
        )
        val listed = DecoderRanking.attempts(c("c2.exynos.h264.decoder", hw = true), unlisted.copy(listed = true))
        assertEquals(true, listed.first().second)
    }

    @Test
    fun `a hardware decoder that refuses every configuration falls to the next hardware one, then software`() {
        val ranked = DecoderRanking.rank(
            listOf(c("c2.android.avc.decoder", hw = false), c("hw.a", hw = true, low = true), c("hw.b", hw = true)),
        )
        val tried = mutableListOf<String>()
        val opened = DecoderRanking.openFirst(ranked, { VendorKeys.NONE }) { cand, options, _ ->
            tried += "${cand.name}${options.keys}"
            if (cand.name == "hw.b") cand.name else null
        }
        assertEquals("hw.b", opened)
        assertEquals(listOf("hw.a[low-latency]", "hw.a[]", "hw.b[]"), tried)
    }

    @Test
    fun `a decoder that cannot be created is skipped without its other attempts`() {
        val ranked = listOf(c("hw.a", hw = true, low = true), c("c2.android.avc.decoder", hw = false))
        val tried = mutableListOf<String>()
        val opened = DecoderRanking.openFirst(ranked, { VendorKeys.NONE }) { cand, _, _ ->
            tried += cand.name
            if (cand.name == "hw.a") throw DecoderUnavailable(RuntimeException("gone")) else cand.name
        }
        assertEquals("c2.android.avc.decoder", opened)
        assertEquals(listOf("hw.a", "c2.android.avc.decoder"), tried)
    }

    @Test
    fun `nothing opens when every decoder refuses`() {
        assertNull(DecoderRanking.openFirst(listOf(c("hw.a", hw = true)), { VendorKeys.NONE }) { _, _, _ -> null as String? })
    }
}
