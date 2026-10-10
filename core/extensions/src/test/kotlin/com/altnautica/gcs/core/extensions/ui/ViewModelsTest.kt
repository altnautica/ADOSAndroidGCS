package com.altnautica.gcs.core.extensions.ui

import com.altnautica.gcs.core.extensions.FakeExtensionApi
import com.altnautica.gcs.core.extensions.FakeNodeSource
import com.altnautica.gcs.core.extensions.NODE
import com.altnautica.gcs.core.extensions.data.ExtensionFailure
import com.altnautica.gcs.core.extensions.data.ExtensionReach
import com.altnautica.gcs.core.extensions.data.ExtensionsState
import com.altnautica.gcs.core.extensions.fixture
import com.altnautica.gcs.core.extensions.manifest.ExtensionDetailParser
import com.altnautica.gcs.core.extensions.manifest.ExtensionSlots
import com.altnautica.gcs.core.extensions.parameters.ExtensionParametersViewModel
import com.altnautica.gcs.core.extensions.repository
import com.altnautica.gcs.core.extensions.summary
import com.altnautica.gcs.core.extensions.bridge.ConfirmBroker
import com.altnautica.gcs.core.model.Reach
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ViewModelsTest {

    private val dispatcher = StandardTestDispatcher()
    private val followId = "com.altnautica.follow-me"
    private val follow = ExtensionDetailParser.parse(fixture("follow-me.detail.json"))!!
    private val api = FakeExtensionApi(linkedMapOf(followId to fixture("follow-me.detail.json")))

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.repo(reaches: List<Reach> = listOf(Reach.Lan)) =
        repository(api, FakeNodeSource(listOf(summary(reaches))), backgroundScope)

    @Test
    fun parametersReadBackThenCommitAndRevert() = runTest(dispatcher) {
        api.config[followId] = buildJsonObject { put("follow_distance_m", 12) }
        val vm = ExtensionParametersViewModel(repo())
        advanceUntilIdle()
        vm.start(NODE, followId, follow.contributions.parameters)
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals(JsonPrimitive(12), s.values["follow_distance_m"])
        assertTrue("follow_distance_m" in s.confirmed)
        assertFalse("follow_height_m" in s.confirmed) // default, not read
        assertEquals(listOf("cam1", "cam0"), cameraOptions(s.cameras!!, "detect").map { it.id })

        val distance = follow.contributions.parameters.single { it.key == "follow_distance_m" }
        vm.commit(distance, JsonPrimitive(8.3))
        advanceUntilIdle()
        assertEquals(JsonPrimitive(8.5), vm.state.value.values["follow_distance_m"]) // quantized to step
        assertEquals("follow_distance_m", api.writes.last().second)

        val errors = mutableListOf<String>()
        val job = backgroundScope.launch { vm.writeErrors.collect { errors += it.key } }
        api.putFailure = ExtensionFailure.Refused("bad")
        vm.commit(distance, JsonPrimitive(20))
        advanceUntilIdle()
        assertEquals(JsonPrimitive(8.5), vm.state.value.values["follow_distance_m"])
        runCurrent() // advanceUntilIdle does not wait on backgroundScope collectors
        assertEquals(listOf("follow_distance_m"), errors)
        job.cancel()
    }

    @Test
    fun readOnlyBindingsNeverWrite() = runTest(dispatcher) {
        val vm = ExtensionParametersViewModel(repo())
        vm.start(NODE, followId, follow.contributions.parameters)
        advanceUntilIdle()
        val detector = follow.contributions.parameters.single { it.key == "detector" }
        vm.commit(detector, JsonPrimitive("other"))
        advanceUntilIdle()
        assertTrue(api.writes.isEmpty())
    }

    @Test
    fun nodeExtensionsLoadsAndFiltersCatalog() = runTest(dispatcher) {
        val vm = NodeExtensionsViewModel(repo())
        val collector = backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()
        vm.start(NODE)
        advanceUntilIdle()
        val state = vm.state.value
        val loaded = state.extensions as ExtensionsState.Loaded
        assertEquals(listOf(followId), loaded.extensions.map { it.pluginId })
        assertEquals(emptyList<Any>(), state.catalog)
        vm.setEnabled(followId, false)
        advanceUntilIdle()
        assertTrue(vm.state.value.busy.isEmpty())
        collector.cancel()
    }

    @Test
    fun nodeExtensionsCloudOnlyShowsNoAgentPath() = runTest(dispatcher) {
        val vm = NodeExtensionsViewModel(repo(listOf(Reach.CloudQueue)))
        val collector = backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()
        vm.start(NODE)
        advanceUntilIdle()
        assertEquals(
            ExtensionsState.NoAgentPath(ExtensionReach.REASON_LAN_OR_GROUND_REQUIRED),
            vm.state.value.extensions,
        )
        collector.cancel()
    }

    @Test
    fun slotViewModelResolvesGrantedMounts() = runTest(dispatcher) {
        val r = repo()
        val vm = ExtensionSlotViewModel(r, ConfirmBroker())
        advanceUntilIdle()
        vm.ensureLoaded(NODE)
        advanceUntilIdle()
        val tabs = vm.mounts(NODE, ExtensionSlots.NODE_DETAIL_TAB, null).first()
        assertEquals(listOf("follow-me-tab"), tabs.map { it.contribution.panelId })
        assertTrue(vm.mounts(NODE, ExtensionSlots.VIDEO_OVERLAY, null).first().isEmpty())
    }
}
