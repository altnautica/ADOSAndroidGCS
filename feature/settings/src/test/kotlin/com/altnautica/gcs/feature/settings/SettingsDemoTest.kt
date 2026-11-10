package com.altnautica.gcs.feature.settings

import com.altnautica.gcs.core.data.cockpit.CockpitSettingsRepository
import com.altnautica.gcs.core.demo.DemoScenario
import com.altnautica.gcs.core.demo.DemoScenarioStore
import com.altnautica.gcs.core.demo.DemoSettings
import com.altnautica.gcs.core.link.direct.DirectLinkSettings
import com.altnautica.gcs.core.skills.tips.TipsRepository
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** Switching demo mode off leaves no pinned flight state behind for the next demo session. */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsDemoTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val prefs = MemoryPreferences()
    private val demo = DemoSettings(prefs)
    private val scenarios = DemoScenarioStore()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = SettingsViewModel(
        AppSettingsRepository(prefs),
        CockpitSettingsRepository(prefs),
        DirectLinkSettings(prefs),
        demo,
        mockk(relaxed = true),
        TipsRepository(prefs),
        mockk(relaxed = true),
        scenarios,
    )

    @Test
    fun `turning demo mode off unpins the demo flight state`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.setDemoEnabled(true)
        vm.pinDemoScenario(DemoScenario.PilotStalled)

        vm.setDemoEnabled(true)
        assertEquals(DemoScenario.PilotStalled, scenarios.pinned.value)

        vm.setDemoEnabled(false)
        assertNull(scenarios.pinned.value)
        assertFalse(demo.enabled.first())
    }
}
