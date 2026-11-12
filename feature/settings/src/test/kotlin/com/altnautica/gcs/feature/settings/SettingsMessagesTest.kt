package com.altnautica.gcs.feature.settings

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altnautica.gcs.core.data.cockpit.CockpitSettingsRepository
import com.altnautica.gcs.core.demo.DemoScenarioStore
import com.altnautica.gcs.core.demo.DemoSettings
import com.altnautica.gcs.core.link.direct.DirectLinkSettings
import com.altnautica.gcs.core.skills.controls.ControlsRepository
import com.altnautica.gcs.core.skills.spec.CockpitSpecRepository
import com.altnautica.gcs.core.skills.spec.DirectorySpecSource
import com.altnautica.gcs.core.skills.tips.TipsRepository
import io.mockk.coVerify
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Replay tour and Reset tips say they worked; the Controls row carries the spec's name. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SettingsMessagesTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val prefs = MemoryPreferences()
    private val controls = mockk<ControlsRepository>(relaxed = true)
    private val spec = CockpitSpecRepository(DirectorySpecSource(File("../cockpit/src/main/assets/cockpit-spec")))

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = SettingsViewModel(
        AppSettingsRepository(prefs),
        CockpitSettingsRepository(prefs),
        DirectLinkSettings(prefs),
        DemoSettings(prefs),
        controls,
        TipsRepository(prefs),
        spec,
        DemoScenarioStore(),
    )

    @Test
    fun `replaying the controller tour confirms it`() = runTest(dispatcher) {
        val vm = viewModel()
        val message = async { vm.messages.first() }
        vm.replayControllerTour()
        assertEquals(SettingsMessage.TourReplayed, message.await())
        coVerify { controls.setTourSeen(false) }
    }

    @Test
    fun `resetting tips confirms it`() = runTest(dispatcher) {
        val vm = viewModel()
        val message = async { vm.messages.first() }
        vm.resetTips()
        assertEquals(SettingsMessage.TipsReset, message.await())
    }

    @Test
    fun `every confirmation has text`() {
        val res = ApplicationProvider.getApplicationContext<android.content.Context>().resources
        SettingsMessage.entries.forEach { assert(res.getString(settingsMessageText(it)).isNotBlank()) }
    }

    @Test
    fun `the Controls category and row use the spec name`() {
        val res = ApplicationProvider.getApplicationContext<android.content.Context>().resources
        assertEquals(spec.setupStrings.format("controls.title"), res.getString(SettingsCategory.Controller.titleRes))
        assertEquals("Controls", viewModel().specText("controls.title"))
    }
}
