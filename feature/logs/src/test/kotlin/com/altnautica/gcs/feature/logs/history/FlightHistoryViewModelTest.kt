package com.altnautica.gcs.feature.logs.history

import app.cash.turbine.test
import com.altnautica.gcs.core.database.FlightSessionDao
import com.altnautica.gcs.core.database.FlightSessionEntity
import com.altnautica.gcs.core.database.LogFileKind
import com.altnautica.gcs.core.database.TlogFileDao
import com.altnautica.gcs.core.database.TlogFileEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class FlightHistoryViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val sessionRows = MutableStateFlow<List<FlightSessionEntity>>(emptyList())
    private val fileRows = MutableStateFlow<List<TlogFileEntity>>(emptyList())

    private val sessions = object : FlightSessionDao {
        override suspend fun insert(session: FlightSessionEntity): Long {
            sessionRows.value = sessionRows.value + session; return session.id
        }
        override fun getAll(): Flow<List<FlightSessionEntity>> = sessionRows.map { l -> l.sortedByDescending { it.startTime } }
        override suspend fun getById(id: Long) = sessionRows.value.firstOrNull { it.id == id }
        override suspend fun delete(session: FlightSessionEntity) {
            sessionRows.value = sessionRows.value.filterNot { it.id == session.id }
        }
        override suspend fun update(session: FlightSessionEntity) = Unit
    }

    private val files = object : TlogFileDao {
        override suspend fun insert(file: TlogFileEntity): Long {
            fileRows.value = fileRows.value + file; return file.id
        }
        override fun observeAll(): Flow<List<TlogFileEntity>> = fileRows
        override fun observeForSession(sessionId: Long) = fileRows.map { l -> l.filter { it.sessionId == sessionId } }
        override suspend fun listForSession(sessionId: Long) = fileRows.value.filter { it.sessionId == sessionId }
        override suspend fun getById(id: Long) = fileRows.value.firstOrNull { it.id == id }
        override suspend fun updateSize(id: Long, sizeBytes: Long) = Unit
        override suspend fun delete(id: Long) {
            fileRows.value = fileRows.value.filterNot { it.id == id }
        }
    }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun emptyThenContentWithTlogFlag() = runTest(dispatcher) {
        val vm = FlightHistoryViewModel(sessions, files, dispatcher)
        vm.state.test {
            assertEquals(FlightHistoryUiState.Empty, awaitItem())
            sessions.insert(FlightSessionEntity(id = 1, startTime = 10))
            awaitItem()
            sessions.insert(FlightSessionEntity(id = 2, startTime = 20))
            awaitItem()
            files.insert(TlogFileEntity(id = 7, sessionId = 1, path = "x", kind = LogFileKind.Tlog, sizeBytes = 1, createdAtMs = 10))
            val content = awaitItem() as FlightHistoryUiState.Content
            assertEquals(listOf(2L to false, 1L to true), content.flights.map { it.session.id to it.hasTlog })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun deleteFlightRemovesFilesFromDiskAndIndex() = runTest(dispatcher) {
        val tlog = File.createTempFile("flight", ".tlog")
        sessions.insert(FlightSessionEntity(id = 1, startTime = 10, tlogPath = tlog.path))
        files.insert(TlogFileEntity(id = 3, sessionId = 1, path = tlog.path, kind = LogFileKind.Tlog, sizeBytes = 1, createdAtMs = 10))
        files.insert(TlogFileEntity(id = 4, path = "df", kind = LogFileKind.Dataflash, sizeBytes = 1, createdAtMs = 11))
        val vm = FlightHistoryViewModel(sessions, files, dispatcher)
        vm.state.test {
            val content = awaitItem() as FlightHistoryUiState.Content
            assertEquals(listOf(4L), content.dataflashLogs.map { it.id })
            vm.deleteFlight(content.flights.single().session)
            val after = expectMostRecentItem() as FlightHistoryUiState.Content
            assertEquals(emptyList<FlightRow>(), after.flights)
            cancelAndIgnoreRemainingEvents()
        }
        assertFalse(tlog.exists())
        assertEquals(listOf(4L), fileRows.value.map { it.id })
    }
}
