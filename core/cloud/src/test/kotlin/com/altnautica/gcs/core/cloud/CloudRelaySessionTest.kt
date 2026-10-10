package com.altnautica.gcs.core.cloud

import android.view.Surface
import com.altnautica.gcs.core.cloud.video.CloudRelaySession
import com.altnautica.gcs.core.cloud.video.Fmp4Demuxer
import com.altnautica.gcs.core.cloud.video.Fmp4Exception
import com.altnautica.gcs.core.cloud.video.Fmp4Sample
import com.altnautica.gcs.core.cloud.video.RelayConnection
import com.altnautica.gcs.core.cloud.video.RelayDialer
import com.altnautica.gcs.core.cloud.video.RelayEvent
import com.altnautica.gcs.core.cloud.video.RelaySampleDecoder
import com.altnautica.gcs.core.cloud.video.RelayToken
import com.altnautica.gcs.core.cloud.video.RelayTokenSource
import com.altnautica.gcs.core.cloud.video.VideoCodec
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.VideoSource
import com.altnautica.gcs.core.model.VideoState
import com.altnautica.gcs.core.video.VideoClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CloudRelaySessionTest {

    private class NoDecoder : RelaySampleDecoder {
        override fun setSurface(surface: Surface?) = Unit
        override fun configure(codec: VideoCodec) = Unit
        override fun submit(sample: Fmp4Sample) = true
        override fun release() = Unit
    }

    private fun TestScope.session(tokens: RelayTokenSource, dialer: RelayDialer) = CloudRelaySession(
        source = VideoSource.CloudRelay(DeviceId("d1")),
        scope = backgroundScope,
        tokens = tokens,
        dialer = dialer,
        relayUrl = { "wss://relay.invalid" },
        decoderFactory = { NoDecoder() },
        clock = VideoClock { testScheduler.currentTime },
    )

    @Test
    fun mintsBeforeEveryDialAndRetriesCloseBeforeOpenWithAFreshToken() = runTest {
        val log = mutableListOf<String>()
        var n = 0
        val tokens = RelayTokenSource { id ->
            n++
            log += "mint:$id"
            RelayToken.Ok("t$n")
        }
        val dialer = RelayDialer { url, events ->
            log += "dial:$url"
            events(RelayEvent.Closed("refused"))
            RelayConnection { log += "close" }
        }
        val s = session(tokens, dialer)
        s.start()
        runCurrent()
        assertEquals(listOf("mint:d1", "dial:wss://relay.invalid/ws/stream/d1?token=t1", "close"), log)
        assertEquals(VideoState.Failed(CloudRelaySession.TOKEN_REFUSED), s.state.value)

        advanceTimeBy(CloudRelaySession.RETRY_MS - 1)
        runCurrent()
        assertEquals(3, log.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("mint:d1", "dial:wss://relay.invalid/ws/stream/d1?token=t2", "close"), log.drop(3))
        s.stop()
    }

    @Test
    fun notConfiguredIsTerminal() = runTest {
        var dials = 0
        var mints = 0
        val s = session({ mints++; RelayToken.NotConfigured }, { _, _ -> dials++; RelayConnection { } })
        s.start()
        runCurrent()
        advanceTimeBy(10 * CloudRelaySession.RETRY_MS)
        runCurrent()
        assertEquals(1, mints)
        assertEquals(0, dials)
        assertTrue(s.state.value is VideoState.Failed)
    }

    private fun box(type: String, vararg payload: ByteArray): ByteArray {
        val body = payload.fold(ByteArray(0)) { a, b -> a + b }
        val size = 8 + body.size
        return byteArrayOf((size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte()) +
            type.toByteArray(Charsets.US_ASCII) + body
    }

    /** A `moov` whose `hdlr` box ends before its handler type: every read past it is out of bounds. */
    private val truncatedInit = box("moov", box("trak", box("mdia", box("hdlr", byteArrayOf(0, 0, 0, 0)))))

    @Test
    fun corruptBoxesThrowFmp4ExceptionNotIndexErrors() {
        val e1 = runCatching { Fmp4Demuxer().push(truncatedInit) }.exceptionOrNull()
        assertTrue("truncated init: $e1", e1 is Fmp4Exception)

        // A box declaring more than the cap is refused before it is buffered.
        val huge = byteArrayOf(0x7F, 0, 0, 0) + "mdat".toByteArray(Charsets.US_ASCII)
        val e2 = runCatching { Fmp4Demuxer().push(huge) }.exceptionOrNull()
        assertTrue("oversized box: $e2", e2 is Fmp4Exception)
    }

    @Test
    fun anUnreadableStreamFailsTheSessionInsteadOfCrashing() = runTest {
        val s = session({ RelayToken.Ok("t") }, { _, events ->
            events(RelayEvent.Opened)
            events(RelayEvent.Bytes(truncatedInit))
            RelayConnection { }
        })
        s.start()
        runCurrent()
        assertTrue(s.state.value.toString(), s.state.value is VideoState.Failed)
        s.stop()
    }

    @Test
    fun aDecoderFailureFailsTheSessionInsteadOfCrashing() = runTest {
        val s = CloudRelaySession(
            source = VideoSource.CloudRelay(DeviceId("d1")),
            scope = backgroundScope,
            tokens = { RelayToken.Ok("t") },
            dialer = { _, events ->
                events(RelayEvent.Bytes(validInit()))
                RelayConnection { }
            },
            relayUrl = { "wss://relay.invalid" },
            decoderFactory = {
                object : RelaySampleDecoder {
                    override fun setSurface(surface: Surface?) = Unit
                    override fun configure(codec: VideoCodec) = throw IllegalStateException("no decoder for $codec")
                    override fun submit(sample: Fmp4Sample) = true
                    override fun release() = Unit
                }
            },
            clock = VideoClock { testScheduler.currentTime },
        )
        s.start()
        runCurrent()
        assertEquals(VideoState.Failed("no decoder for Avc"), s.state.value)
        s.stop()
    }

    /** Smallest init segment the demuxer accepts: one H.264 video track, one SPS and one PPS. */
    private fun validInit(): ByteArray {
        val hdlr = box("hdlr", ByteArray(8) + "vide".toByteArray(Charsets.US_ASCII) + ByteArray(12))
        val mdhd = box("mdhd", ByteArray(12) + byteArrayOf(0, 0, 0x03, 0xE8.toByte()) + ByteArray(8))
        val avcC = box("avcC", byteArrayOf(1, 0x42, 0, 0x1E, 0xFF.toByte(), 0xE1.toByte(), 0, 2, 0x67, 0x42, 1, 0, 2, 0x68, 0xCE.toByte()))
        val avc1 = box("avc1", ByteArray(78), avcC)
        val stsd = box("stsd", ByteArray(8), avc1)
        val minf = box("minf", box("stbl", stsd))
        return box("moov", box("trak", box("mdia", hdlr, mdhd, minf)))
    }

    @Test
    fun streamUrlEncodesTheToken() {
        assertEquals("wss://v.invalid/ws/stream/d1?token=a%2Bb%3D", CloudRelaySession.streamUrl("wss://v.invalid/", "d1", "a+b="))
    }

    @Test
    fun lengthPrefixedNalsBecomeAnnexB() {
        val avcc = byteArrayOf(0, 0, 0, 2, 0x65, 0x01, 0, 0, 0, 1, 0x41)
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 1, 0x65, 0x01, 0, 0, 0, 1, 0x41),
            Fmp4Demuxer.toAnnexB(avcc, 0, avcc.size, 4),
        )
    }
}
