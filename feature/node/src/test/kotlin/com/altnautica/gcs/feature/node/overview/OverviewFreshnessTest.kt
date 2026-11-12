package com.altnautica.gcs.feature.node.overview

import com.altnautica.gcs.core.agent.groundstation.StationStatus
import com.altnautica.gcs.core.agent.http.AgentError
import com.altnautica.gcs.core.agent.http.AgentResult
import com.altnautica.gcs.core.link.node.NodeVitalsRepository
import com.altnautica.gcs.feature.node.R
import com.altnautica.gcs.feature.node.common.PageProblem
import com.altnautica.gcs.feature.node.common.pollStale
import com.altnautica.gcs.feature.node.common.roleLabelRes
import com.altnautica.gcs.feature.node.common.uplinkLabelRes
import com.altnautica.gcs.feature.node.common.videoStateLabelRes
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverviewFreshnessTest {
    private val status = io.mockk.mockk<StationStatus>()

    @Test
    fun aFailedReadKeepsTheLastStationValuesMarkedStale() {
        val ready = StationLoad.next(StationLoad.Loading, AgentResult.Ok(status))
        assertEquals(StationLoad.Ready(status, stale = false), ready)
        val failed = StationLoad.next(ready, AgentResult.Err(AgentError.Network(IOException("socket 10.0.0.1"))))
        assertEquals(StationLoad.Ready(status, stale = true), failed)
        assertEquals(StationLoad.Ready(status, stale = false), StationLoad.next(failed, AgentResult.Ok(status)))
    }

    @Test
    fun aFirstFailureShowsAProblemWithoutExceptionText() {
        val failed = StationLoad.next(StationLoad.Loading, AgentResult.Err(AgentError.Network(IOException("socket 10.0.0.1"))))
        assertEquals(StationLoad.Failed(PageProblem.Unreachable), failed)
        assertEquals(PageProblem.Unreachable, PageProblem.from(AgentError.Server(500, "stack trace")))
    }

    @Test
    fun anOldPollIsStale() {
        assertFalse(pollStale(lastOkMs = 10_000, nowMs = 12_000))
        assertTrue(pollStale(lastOkMs = 10_000, nowMs = 10_000 + NodeVitalsRepository.MAX_POLL_AGE_MS))
        assertTrue(pollStale(lastOkMs = null, nowMs = 0))
    }

    @Test
    fun wireValuesHaveWords() {
        assertEquals(R.string.node_video_not_initialized, videoStateLabelRes("not_initialized"))
        assertEquals(R.string.node_uplink_cellular, uplinkLabelRes("cellular"))
        assertEquals(R.string.gs_role_relay, roleLabelRes("relay"))
        assertNull(uplinkLabelRes("enx001122"))
    }
}
