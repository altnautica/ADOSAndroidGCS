package com.altnautica.gcs.feature.logs.dataflash

import com.altnautica.gcs.core.database.LogFileKind
import com.altnautica.gcs.core.database.TlogFileDao
import com.altnautica.gcs.core.database.TlogFileEntity
import com.altnautica.gcs.core.link.log.LogDownloadProgress
import com.altnautica.gcs.core.link.log.LogEntry
import com.altnautica.gcs.core.link.log.LogListResult
import com.altnautica.gcs.core.link.log.LogService
import com.altnautica.gcs.core.model.DeviceId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
class DataflashViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val rows = MutableStateFlow<List<TlogFileEntity>>(emptyList())
    private val dao = object : TlogFileDao {
        override suspend fun insert(file: TlogFileEntity): Long {
            rows.value = rows.value + file; return 1
        }
        override fun observeAll(): Flow<List<TlogFileEntity>> = rows
        override fun observeForSession(sessionId: Long) = rows.map { emptyList<TlogFileEntity>() }
        override suspend fun listForSession(sessionId: Long) = emptyList<TlogFileEntity>()
        override suspend fun getById(id: Long) = null
        override suspend fun updateSize(id: Long, sizeBytes: Long) = Unit
        override suspend fun delete(id: Long) = Unit
    }
    private val progress = MutableSharedFlow<LogDownloadProgress>()
    private var cancelled = false
    private val service = object : LogService {
        var result: LogListResult = LogListResult.Success(listOf(LogEntry(1, 100, 0), LogEntry(2, 200, 1_700_000_000)))
        override suspend fun list() = result
        override fun download(entry: LogEntry, destination: File) = progress.onCompletion { cancelled = it != null }
        override suspend fun eraseAll() = true
    }
    private val serviceFlow = MutableStateFlow<LogService?>(null)
    private val dir = Files.createTempDirectory("df").toFile()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() {
        Dispatchers.resetMain()
        dir.deleteRecursively()
    }

    private fun vm() = DataflashViewModel(DeviceId("fc"), serviceFlow, dao, dir) { 42L }

    @Test
    fun listsDownloadsAndIndexes() = runTest(dispatcher) {
        val vm = vm()
        assertEquals(DataflashUiState.NoVehicle, vm.state.value)
        serviceFlow.value = service
        val content = vm.state.value as DataflashUiState.Content
        assertEquals(listOf(2, 1), content.rows.map { it.entry.id })

        vm.download(content.rows.last().entry)
        progress.emit(LogDownloadProgress.Running(50, 100))
        assertEquals(EntryDownload.Running(50, 100), (vm.state.value as DataflashUiState.Content).rows.last().download)
        val file = File(dir, "fc-log1.bin").apply { writeBytes(ByteArray(100)) }
        progress.emit(LogDownloadProgress.Done(file))

        val saved = rows.value.single()
        assertEquals(LogFileKind.Dataflash, saved.kind)
        assertEquals(1, saved.fcLogId)
        assertEquals("fc", saved.deviceId)
        assertEquals(42L, saved.createdAtMs)
        assertEquals(EntryDownload.Saved(file.absolutePath), (vm.state.value as DataflashUiState.Content).rows.last().download)
    }

    @Test
    fun cancelStopsTransferAndListFailureIsShown() = runTest(dispatcher) {
        val vm = vm()
        serviceFlow.value = service
        val entry = (vm.state.value as DataflashUiState.Content).rows.first().entry
        vm.download(entry)
        progress.emit(LogDownloadProgress.Running(10, 200))
        vm.cancel(entry)
        assertTrue(cancelled)
        assertEquals(EntryDownload.None, (vm.state.value as DataflashUiState.Content).rows.first().download)

        service.result = LogListResult.Failed("timeout")
        vm.refresh()
        assertEquals(DataflashUiState.ListFailed("timeout"), vm.state.value)
    }
}
