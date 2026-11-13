package com.altnautica.gcs.core.video

import com.altnautica.gcs.core.video.whep.DecoderOutput
import com.altnautica.gcs.core.video.whep.DecoderOutputPolicy
import com.altnautica.gcs.core.video.whep.InboundVideoStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoRatesTest {

    private fun stats(received: Long, decoded: Long, dropped: Long = 0, interFrameS: Double = decoded / 30.0) =
        InboundVideoStats(
            framesReceived = received,
            framesDecoded = decoded,
            framesDropped = dropped,
            decoderImplementation = "c2.example.avc.decoder",
            totalInterFrameDelayS = interFrameS,
            jitterBufferDelayS = decoded * 0.05,
            jitterBufferEmittedCount = decoded,
        )

    @Test
    fun `the stats report's inbound video counters are read by name`() {
        val parsed = InboundVideoStats.from(
            mapOf(
                "kind" to "video",
                "framesReceived" to 120L,
                "framesDecoded" to 118L,
                "framesDropped" to 2L,
                "decoderImplementation" to "c2.goldfish.h264.decoder",
                "totalInterFrameDelay" to 3.9,
                "jitterBufferDelay" to 2.5,
                "jitterBufferEmittedCount" to 118.toBigInteger(),
            ),
        )
        assertEquals(
            InboundVideoStats(120, 118, 2, "c2.goldfish.h264.decoder", 3.9, 2.5, 118),
            parsed,
        )
        assertEquals("a stack that reports nothing gives nulls", InboundVideoStats(), InboundVideoStats.from(emptyMap()))
    }

    @Test
    fun `the meter turns two samples into per-second rates`() {
        val meter = VideoStatsMeter()
        assertNull("one sample is no rate", meter.update(1_000, stats(30, 30), delivered = 30, shown = 30))
        val rates = meter.update(3_000, stats(90, 80, dropped = 4), delivered = 50, shown = 40)!!
        assertEquals(30.0, rates.receivedFps!!, 1e-9)
        assertEquals(25.0, rates.decodedFps!!, 1e-9)
        assertEquals(2.0, rates.droppedFps!!, 1e-9)
        assertEquals(10.0, rates.deliveredFps, 1e-9)
        assertEquals(5.0, rates.shownFps, 1e-9)
        assertEquals(33.3, rates.interFrameMs!!, 0.1)
        assertEquals(50, rates.latencyMs)
        assertEquals("c2.example.avc.decoder", rates.decoder)

        meter.reset()
        assertNull(meter.update(4_000, stats(120, 110), delivered = 80, shown = 70))
    }

    @Test
    fun `rates without the stack's counters still give delivered and shown fps`() {
        val meter = VideoStatsMeter()
        meter.update(0, null, delivered = 0, shown = 0)
        val rates = meter.update(1_000, null, delivered = 27, shown = 12)!!
        assertNull(rates.receivedFps)
        assertNull(rates.handoffLoss)
        assertEquals(27.0, rates.deliveredFps, 1e-9)
        assertEquals(12.0, rates.shownFps, 1e-9)
    }

    @Test
    fun `handoff loss is the share of kept frames that never reached the sink`() {
        fun rates(received: Double, dropped: Double, delivered: Double) =
            VideoRates(received, received, dropped, delivered, delivered, null, null, null)
        assertEquals(0.5, rates(received = 27.0, dropped = 3.0, delivered = 12.0).handoffLoss!!, 1e-9)
        assertEquals(0.0, rates(received = 27.0, dropped = 0.0, delivered = 30.0).handoffLoss!!, 1e-9)
        assertNull("too few frames to judge", rates(received = 4.0, dropped = 0.0, delivered = 0.0).handoffLoss)
    }

    @Test
    fun `the loss monitor fires after 5 lossy seconds in a row and starts over on a clean one`() {
        val lossy = VideoRates(27.0, 27.0, 0.0, 12.0, 12.0, null, null, null)
        val clean = lossy.copy(deliveredFps = 26.0)
        val monitor = HandoffLossMonitor()
        repeat(4) { assertFalse(monitor.onRates(lossy)) }
        assertFalse("a clean second resets the count", monitor.onRates(clean))
        repeat(4) { assertFalse(monitor.onRates(lossy)) }
        assertTrue(monitor.onRates(lossy))
        assertFalse("no rates is not a loss", monitor.onRates(null))
    }

    @Test
    fun `emulators decode to byte buffers and phones keep textures`() {
        assertTrue(DecoderOutputPolicy.isEmulator("ranchu", emptyList()))
        assertTrue(DecoderOutputPolicy.isEmulator("goldfish", emptyList()))
        assertTrue(DecoderOutputPolicy.isEmulator("unknown", listOf("c2.android.avc.decoder", "c2.goldfish.h264.decoder")))
        assertFalse(DecoderOutputPolicy.isEmulator("qcom", listOf("c2.qti.avc.decoder", "c2.android.avc.decoder")))

        assertEquals(DecoderOutput.ByteBuffer, DecoderOutputPolicy.initial(emulator = true, override = null))
        assertEquals(DecoderOutput.Texture, DecoderOutputPolicy.initial(emulator = false, override = null))
        assertEquals(DecoderOutput.ByteBuffer, DecoderOutputPolicy.initial(emulator = false, override = "1"))
        assertEquals(DecoderOutput.Texture, DecoderOutputPolicy.initial(emulator = true, override = "0"))
        assertEquals(DecoderOutput.ByteBuffer, DecoderOutputPolicy.initial(emulator = true, override = "yes"))
    }
}
