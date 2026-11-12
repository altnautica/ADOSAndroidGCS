package com.altnautica.gcs.feature.node.logs

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeLogsPagingTest {
    private fun entry(seq: Long?) = LogEntry(seq, null, "INFO", "x", "m$seq")

    @Test
    fun aFullPageOffersMoreAndAShortPageDoesNot() {
        assertTrue(NodeLogsUiState(loading = false, hasMore = true).canLoadMore)
        assertFalse(NodeLogsUiState(loading = false, hasMore = false).canLoadMore)
        assertFalse(NodeLogsUiState(loading = true, hasMore = true).canLoadMore)
    }

    @Test
    fun appendDropsRowsAlreadyShown() {
        val shown = listOf(entry(9), entry(8))
        val merged = NodeLogsViewModel.append(shown, listOf(entry(8), entry(7), entry(null)))
        assertEquals(listOf<Long?>(9, 8, 7, null), merged.map { it.seq })
    }

    @Test
    fun timesAreShownInTheLocalZone() {
        val zone = ZoneId.of("Asia/Kolkata")
        val now = Instant.parse("2026-11-12T10:00:00Z")
        assertEquals("15:00:01.250", NodeLogsViewModel.logTime("2026-11-12T09:30:01.25Z", zone, now))
        val older = NodeLogsViewModel.logTime("2026-11-10T09:30:01Z", zone, now)!!
        assertTrue(older, older.contains("15:00:01") && older.contains("10"))
        assertEquals("garbage", NodeLogsViewModel.logTime("garbage", zone, now))
        assertEquals(null, NodeLogsViewModel.logTime(null, zone, now))
    }
}
