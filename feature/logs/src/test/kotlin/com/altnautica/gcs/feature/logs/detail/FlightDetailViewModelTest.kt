package com.altnautica.gcs.feature.logs.detail

import com.altnautica.gcs.core.database.FlightSessionDao
import com.altnautica.gcs.core.database.FlightSessionEntity
import com.altnautica.gcs.core.database.TlogFileDao
import com.altnautica.gcs.core.mavlink.MavlinkFrameWriter
import com.altnautica.gcs.core.mavlink.messages.Heartbeat
import com.altnautica.gcs.core.mavlink.messages.VfrHud
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer

@OptIn(ExperimentalCoroutinesApi::class)
class FlightDetailViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val sessions = mockk<FlightSessionDao>()
    private val files = mockk<TlogFileDao>()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun tlog(): ByteArray {
        val w = MavlinkFrameWriter(sysId = 1, compId = 1)
        val out = ByteArrayOutputStream()
        listOf(Heartbeat(type = 2, autopilot = 3), VfrHud(groundspeed = 3f)).forEachIndexed { i, m ->
            out.write(ByteBuffer.allocate(8).putLong(1_760_000_000_000_000L + i * 1_000_000L).array())
            out.write(w.encode(m))
        }
        return out.toByteArray()
    }

    private fun vm(open: (String) -> java.io.InputStream) = FlightDetailViewModel(5, sessions, files, dispatcher, open)

    @Test
    fun missingSessionIsNotFound() = runTest(dispatcher) {
        coEvery { sessions.getById(5) } returns null
        assertEquals(FlightDetailUiState.NotFound, vm { error("unused") }.state.value)
    }

    @Test
    fun sessionWithoutLogHasNoSeries() = runTest(dispatcher) {
        coEvery { sessions.getById(5) } returns FlightSessionEntity(id = 5, startTime = 1)
        coEvery { files.listForSession(5) } returns emptyList()
        val state = vm { error("unused") }.state.value as FlightDetailUiState.Content
        assertEquals(SeriesState.NoLog, state.series)
    }

    @Test
    fun readsSeriesFromSessionLogAndReportsFailures() = runTest(dispatcher) {
        coEvery { sessions.getById(5) } returns FlightSessionEntity(id = 5, startTime = 1, tlogPath = "/x.tlog")
        coEvery { files.listForSession(5) } returns emptyList()
        val ok = vm { tlog().inputStream() }.state.value as FlightDetailUiState.Content
        val ready = ok.series as SeriesState.Ready
        assertEquals("/x.tlog", ready.tlogPath)
        assertEquals(1, ready.series.groundspeedMs.size)

        val bad = vm { throw IOException("gone") }.state.value as FlightDetailUiState.Content
        assertTrue((bad.series as SeriesState.Failed).message.contains("gone"))
    }
}
