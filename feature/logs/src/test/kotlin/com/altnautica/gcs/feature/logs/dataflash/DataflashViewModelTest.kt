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
import kotlinx.coroutines.flow.transformWhile
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
            val id = rows.value.size + 1L
            rows.value = rows.value + file.copy(id = id); return id
        }
        override fun observeAll(): Flow<List<TlogFileEntity>> = rows
        override fun observeForSession(sessionId: Long) = rows.map { emptyList<TlogFileEntity>() }
        override suspend fun listForSession(sessionId: Long) = emptyList<TlogFileEntity>()
        override suspend fun getById(id: Long) = null
        override suspend fun updateSize(id: Long, sizeBytes: Long) = Unit
        override suspend fun delete(id: Long) {
            rows.value = rows.value.filterNot { it.id == id }
        }
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

    @Test
    fun downloadsRunOneAtATime() = runTest(dispatcher) {
        val started = mutableListOf<Int>()
        val streams = mapOf(1 to MutableSharedFlow<LogDownloadProgress>(), 2 to MutableSharedFlow<LogDownloadProgress>())
        val queued = object : LogService {
            override suspend fun list() = LogListResult.Success(listOf(LogEntry(1, 100, 0), LogEntry(2, 200, 0)))
            override fun download(entry: LogEntry, destination: File): Flow<LogDownloadProgress> {
                started += entry.id
                // Like the real service, the stream ends after Done or Failed.
                return streams.getValue(entry.id).transformWhile { p ->
                    emit(p)
                    p !is LogDownloadProgress.Done && p !is LogDownloadProgress.Failed
                }
            }
            override suspend fun eraseAll() = true
        }
        serviceFlow.value = queued
        val vm = vm()
        val (second, first) = (vm.state.value as DataflashUiState.Content).rows.map { it.entry }
        vm.download(first)
        vm.download(second)
        assertEquals(listOf(1), started)
        assertEquals(EntryDownload.Queued, (vm.state.value as DataflashUiState.Content).rows.first().download)

        val file = File(dir, "fc-log1.bin").apply { writeBytes(ByteArray(100)) }
        streams.getValue(1).emit(LogDownloadProgress.Done(file))
        assertEquals(listOf(1, 2), started)
    }

    @Test
    fun armedVehicleIsRefusedAndStopsTransfers() = runTest(dispatcher) {
        val armed = MutableStateFlow(false)
        serviceFlow.value = service
        val vm = DataflashViewModel(DeviceId("fc"), serviceFlow, dao, dir, armed) { 42L }
        val entry = (vm.state.value as DataflashUiState.Content).rows.first().entry
        vm.download(entry)
        progress.emit(LogDownloadProgress.Running(10, 200))

        armed.value = true
        assertEquals(DataflashUiState.Armed, vm.state.value)
        assertTrue(cancelled)
        vm.download(entry)
        vm.refresh()
        assertEquals(DataflashUiState.Armed, vm.state.value)

        armed.value = false
        assertEquals(EntryDownload.None, (vm.state.value as DataflashUiState.Content).rows.first().download)
    }

    @Test
    fun ulogDownloadsAreSavedAsUlg() = runTest(dispatcher) {
        serviceFlow.value = service
        val vm = vm()
        val entry = (vm.state.value as DataflashUiState.Content).rows.last().entry
        vm.download(entry)
        val file = File(dir, "fc-log1.bin").apply { writeBytes(byteArrayOf(0x55, 0x4C, 0x6F, 0x67, 0x01, 0x12, 0x35, 0x01)) }
        progress.emit(LogDownloadProgress.Done(file))

        assertTrue(rows.value.single().path.endsWith("fc-log1.ulg"))
        assertTrue(File(dir, "fc-log1.ulg").exists())
    }

    @Test
    fun aSavedFileThatIsGoneDropsItsIndexRow() = runTest(dispatcher) {
        serviceFlow.value = service
        val vm = vm()
        val entry = (vm.state.value as DataflashUiState.Content).rows.last().entry
        vm.download(entry)
        val file = File(dir, "fc-log1.bin").apply { writeBytes(ByteArray(100)) }
        progress.emit(LogDownloadProgress.Done(file))
        assertEquals(1, rows.value.size)

        file.delete()
        vm.fileMissing(file.absolutePath)

        assertTrue(rows.value.isEmpty())
        assertEquals(EntryDownload.None, (vm.state.value as DataflashUiState.Content).rows.last().download)
    }
}
