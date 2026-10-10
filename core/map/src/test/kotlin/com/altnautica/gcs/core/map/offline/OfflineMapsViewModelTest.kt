package com.altnautica.gcs.core.map.offline

import android.net.Uri
import com.altnautica.gcs.core.map.MapProvider
import com.altnautica.gcs.core.map.PmtilesPack
import com.altnautica.gcs.core.map.TileBounds
import com.altnautica.gcs.core.map.TileMath
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class OfflineMapsViewModelTest {

    private class FakeRepository : OfflineMapRepository {
        override val packs = MutableStateFlow<List<OfflinePack>>(emptyList())
        override val pmtiles = MutableStateFlow<List<PmtilesPack>>(emptyList())
        var refreshResult: Result<Unit> = Result.success(Unit)
        val downloads = mutableListOf<List<Any>>()
        var downloadResult: Result<Unit> = Result.success(Unit)
        var importResult: Result<PmtilesPack> = Result.failure(PmtilesImportException(PmtilesImportError.NotPmtiles))
        val paused = mutableListOf<Pair<Long, Boolean>>()

        override suspend fun refresh() = refreshResult
        override suspend fun download(name: String, provider: MapProvider, bounds: TileBounds, minZoom: Int, maxZoom: Int): Result<Unit> {
            downloads += listOf(name, provider, bounds, minZoom, maxZoom)
            return downloadResult
        }
        override fun setPaused(id: Long, paused: Boolean) {
            this.paused += id to paused
        }
        override suspend fun delete(id: Long) = Result.success(Unit)
        override suspend fun importPmtiles(uri: Uri) = importResult
        override suspend fun deletePmtiles(id: String) = Result.success(Unit)
    }

    private val area = TileBounds(minLat = 12.0, maxLat = 12.1, minLon = 77.0, maxLon = 77.1)

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun kotlinx.coroutines.test.TestScope.collect(vm: OfflineMapsViewModel): StateFlow<OfflineMapsUiState> {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        return vm.state
    }

    @Test
    fun `refresh failure surfaces a load error`() = runTest {
        val repo = FakeRepository().apply { refreshResult = Result.failure(IOException("db locked")) }
        val state = collect(OfflineMapsViewModel(repo))

        assertFalse(state.value.loading)
        assertEquals("db locked", state.value.loadError)
    }

    @Test
    fun `draft estimate follows bounds, provider and zoom range`() = runTest {
        val vm = OfflineMapsViewModel(FakeRepository())
        val state = collect(vm)

        vm.startDraft()
        assertNull(state.value.draft!!.estimate)
        vm.setDraftBounds(area)
        vm.setDraftProvider(MapProvider.EsriWorldImagery)
        vm.setDraftZoom(10, 12)

        val draft = state.value.draft!!
        assertEquals(TileMath.estimate(area, 10, 12, MapProvider.EsriWorldImagery), draft.estimate)
        assertEquals(7L, draft.estimate!!.tileCount)
        assertEquals(7L * 40 * 1024, draft.estimate!!.estimatedBytes)
    }

    @Test
    fun `zoom range is clamped to the provider maximum`() = runTest {
        val vm = OfflineMapsViewModel(FakeRepository())
        val state = collect(vm)
        vm.startDraft()

        vm.setDraftZoom(15, 22)
        vm.setDraftProvider(MapProvider.OpenTopoMap)

        assertEquals(15, state.value.draft!!.minZoom)
        assertEquals(17, state.value.draft!!.maxZoom)
    }

    @Test
    fun `huge areas cannot be downloaded`() = runTest {
        val vm = OfflineMapsViewModel(FakeRepository())
        val state = collect(vm)
        vm.startDraft()
        vm.setDraftName("Region")
        vm.setDraftBounds(TileBounds(minLat = 0.0, maxLat = 10.0, minLon = 0.0, maxLon = 10.0))
        vm.setDraftZoom(10, 16)

        assertTrue(state.value.draft!!.tooLarge)
        assertFalse(state.value.draft!!.canDownload)
    }

    @Test
    fun `download hands the draft to the repository and closes it`() = runTest {
        val repo = FakeRepository()
        val vm = OfflineMapsViewModel(repo)
        val state = collect(vm)
        vm.startDraft()
        vm.setDraftBounds(area)
        assertFalse(state.value.draft!!.canDownload)
        vm.setDraftName("  Field  ")
        vm.setDraftZoom(12, 14)

        vm.download()

        assertNull(state.value.draft)
        assertEquals(listOf(listOf("Field", MapProvider.Osm, area, 12, 14)), repo.downloads)
    }

    @Test
    fun `failed download is reported`() = runTest {
        val repo = FakeRepository().apply { downloadResult = Result.failure(IOException("disk full")) }
        val vm = OfflineMapsViewModel(repo)
        val state = collect(vm)
        vm.startDraft()
        vm.setDraftBounds(area)
        vm.setDraftName("Field")

        vm.download()

        assertEquals(OfflineMapsMessage.DownloadFailed("disk full"), state.value.message)
        vm.messageShown()
        assertNull(state.value.message)
    }

    @Test
    fun `vector pmtiles import is refused with its reason`() = runTest {
        val repo = FakeRepository().apply { importResult = Result.failure(PmtilesImportException(PmtilesImportError.VectorTiles)) }
        val vm = OfflineMapsViewModel(repo)
        val state = collect(vm)

        vm.importPmtiles(mockk())

        assertFalse(state.value.importing)
        assertEquals(OfflineMapsMessage.ImportFailed(PmtilesImportError.VectorTiles), state.value.message)
    }

    @Test
    fun `pause forwards to the repository`() = runTest {
        val repo = FakeRepository()
        val vm = OfflineMapsViewModel(repo)

        vm.setPaused(7, true)

        assertEquals(listOf(7L to true), repo.paused)
    }

    @Test
    fun `metadata round trips and pack names are sanitised`() {
        val bytes = MapLibreOfflineMapRepository.encodeMetadata("North field", MapProvider.OpenTopoMap)
        val decoded = MapLibreOfflineMapRepository.decodeMetadata(bytes)

        assertEquals("North field", decoded!!.name)
        assertEquals("terrain", decoded.providerId)
        assertNull(MapLibreOfflineMapRepository.decodeMetadata("not json".toByteArray()))
        assertEquals("a_b c.pmtiles", MapLibreOfflineMapRepository.packFileName("a/b c.pmtiles"))
    }
}
