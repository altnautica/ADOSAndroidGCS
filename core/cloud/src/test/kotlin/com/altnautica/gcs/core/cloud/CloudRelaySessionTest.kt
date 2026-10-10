package com.altnautica.gcs.core.cloud

import android.view.Surface
import com.altnautica.gcs.core.cloud.video.CloudRelaySession
import com.altnautica.gcs.core.cloud.video.Fmp4Demuxer
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
