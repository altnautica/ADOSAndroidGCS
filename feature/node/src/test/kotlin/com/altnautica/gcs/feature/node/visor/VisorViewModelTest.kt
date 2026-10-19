package com.altnautica.gcs.feature.node.visor

import com.altnautica.gcs.core.agent.AgentEndpoints
import com.altnautica.gcs.core.agent.capture.CaptureApi
import com.altnautica.gcs.core.agent.capture.CaptureMarkResponse
import com.altnautica.gcs.core.agent.capture.CaptureSessions
import com.altnautica.gcs.core.agent.capture.CaptureState
import com.altnautica.gcs.core.agent.capture.CaptureStartRequest
import com.altnautica.gcs.core.agent.http.AgentErrorParser
import com.altnautica.gcs.core.agent.http.AgentJson
import com.altnautica.gcs.core.agent.http.AgentResult
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.feature.node.common.NodeApis
import com.altnautica.gcs.feature.node.common.PageProblem
import com.altnautica.gcs.feature.node.groundstation.Load
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import com.altnautica.gcs.core.agent.http.AgentEndpoint
import com.altnautica.gcs.core.agent.http.AgentError
import com.altnautica.gcs.core.agent.sensors.ClockStatus
import com.altnautica.gcs.core.agent.sensors.SensorsApi
import com.altnautica.gcs.core.agent.sensors.SensorsStatus
import com.altnautica.gcs.core.agent.sensors.StorageStatus
import com.altnautica.gcs.core.model.VideoSource
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VisorViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val capture = mockk<CaptureApi>()
    private val apis = mockk<NodeApis> { coEvery { capture(DeviceId("v1")) } returns capture }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm() = VisorViewModel("v1", apis, mockk<AgentEndpoints>(relaxed = true))

    /** The contract example from the capture state route. */
    private val recording = AgentJson.json.decodeFromString(
        CaptureState.serializer(),
        """
        {"state":"recording","session_id":"visor-1-20261019T101500Z-3fa2","started_at_ns":120000000000,"duration_s":312.4,
         "site":"Warehouse 3","floor":0,"room":"Dock","segment":3,
         "frames":{"total":12480,"dropped_pct":0.2,"per_camera":[{"id":"cam_fl","fps":20.0,"dropped":3}]},
         "pose":{"source":"vio","health":"ok","tracked_features":143,"drift_hint_m":0.04},
         "coverage":{"voxels":18420,"room_pct":72.0,"loop_closed":false,"distance_from_start_m":38.2},
         "health":[{"id":"clock","level":"ok","value":0.42},{"id":"blur","level":"ok","value":182.0},{"id":"texture","level":"warn","value":54},
                   {"id":"flicker","level":"ok","value":0.8},{"id":"corridor","level":"bad","value":0.12},{"id":"storage","level":"ok","value":61.0},
                   {"id":"something_new","level":"ok"}],
         "exposure":{"locked":true,"exposure_us":4000,"gain":2.0,"mode":"walking"},
         "storage":{"free_bytes":812345678901,"est_remaining_s":14400}}
        """.trimIndent(),
    )

    @Test
    fun captureStateMapsToTheTabModel() = runTest(dispatcher) {
        coEvery { capture.state() } returns AgentResult.Ok(recording)
        val vm = vm()
        vm.loadCapture()

        val ui = (vm.state.value.capture as Load.Ready).value
        assertEquals(CaptureMode.Recording, ui.mode)
        assertEquals("visor-1-20261019T101500Z-3fa2", ui.sessionId)
        assertEquals(3, ui.segment)
        assertEquals(12480L, ui.framesTotal)
        assertEquals("vio", ui.poseSource)
        assertEquals(72.0, ui.coveragePct!!, 0.0)
        assertEquals(14400L, ui.estRemainingS)
        // Fixed chip order, unknown ids dropped, levels mapped.
        assertEquals(listOf("blur", "texture", "flicker", "corridor", "storage", "clock"), ui.chips.map { it.id })
        assertEquals(ChipLevel.Warn, ui.chips.first { it.id == "texture" }.level)
        assertEquals(ChipLevel.Bad, ui.chips.first { it.id == "corridor" }.level)
        assertTrue(ui.exposure!!.locked)
    }

    @Test
    fun unknownHealthLevelIsNeverShownAsOk() {
        assertEquals(ChipLevel.Warn, CaptureUi.level("degraded"))
    }

    @Test
    fun markSendsRoomAndFloorAndKeepsTheSegment() = runTest(dispatcher) {
        coEvery { capture.mark("Kitchen", 1) } returns AgentResult.Ok(CaptureMarkResponse(ok = true, segment = 4))
        coEvery { capture.state() } returns AgentResult.Ok(recording)
        val vm = vm()

        vm.mark("  Kitchen ", 1)

        coVerify(exactly = 1) { capture.mark("Kitchen", 1) }
        assertEquals(4, vm.state.value.lastSegment)
        assertEquals(ActionState.Done(CaptureAction.Mark), vm.state.value.action)
        coVerify(exactly = 1) { capture.state() }
    }

    @Test
    fun markWithoutARoomNameSendsNothing() = runTest(dispatcher) {
        val vm = vm()
        vm.mark("   ", 0)
        coVerify(exactly = 0) { capture.mark(any(), any()) }
        assertEquals(ActionState.Idle, vm.state.value.action)
    }

    @Test
    fun storageFullOnStartIsReportedAsStorageFull() = runTest(dispatcher) {
        val refused = AgentErrorParser.parse(409, """{"error":"E_STORAGE_FULL"}""")
        coEvery { capture.start(any()) } returns AgentResult.Err(refused)
        val vm = vm()

        vm.start(site = " Warehouse 3 ", floor = 0, room = "")

        assertEquals(ActionState.Failed(CaptureAction.Start, CaptureProblem.StorageFull), vm.state.value.action)
        coVerify { capture.start(CaptureStartRequest(site = "Warehouse 3", floor = 0, room = null)) }
        coVerify(exactly = 0) { capture.state() }
    }

    @Test
    fun storageFullWithAMessageStillMapsByCode() {
        val refused = AgentErrorParser.parse(409, """{"error":"E_STORAGE_FULL","message":"capture volume below floor"}""")
        assertEquals(CaptureProblem.StorageFull, CaptureProblem.from(refused))
    }

    @Test
    fun otherRefusalsMapToTheirProblem() {
        assertEquals(CaptureProblem.AlreadyRecording, CaptureProblem.from(AgentErrorParser.parse(409, """{"error":"E_ALREADY_RECORDING"}""")))
        assertEquals(CaptureProblem.NotUploaded, CaptureProblem.from(AgentErrorParser.parse(409, """{"error":"E_NOT_UPLOADED"}""")))
        assertEquals(CaptureProblem.Page(PageProblem.Unsupported), CaptureProblem.from(AgentErrorParser.parse(404, "")))
    }

    @Test
    fun noAgentLinkFailsTheActionWithoutACall() = runTest(dispatcher) {
        val noLink = mockk<NodeApis> { coEvery { capture(DeviceId("v1")) } returns null }
        val vm = VisorViewModel("v1", noLink, mockk<AgentEndpoints>(relaxed = true))
        vm.stop()
        assertEquals(ActionState.Failed(CaptureAction.Stop, CaptureProblem.Page(PageProblem.NoAgentLink)), vm.state.value.action)
    }

    @Test
    fun sessionsMapUploadProgress() = runTest(dispatcher) {
        val body = AgentJson.json.decodeFromString(
            CaptureSessions.serializer(),
            """{"sessions":[{"session_id":"s1","started_at_utc":"2026-10-19T10:15:00Z","duration_s":312.4,"size_bytes":1000,"frames":12480,
               "rooms":["Dock","Aisle 1"],"floors":[0],"timebase":"hub_freerun","anchor_source":null,"uploaded":false,
               "upload":{"state":"uploading","bytes_sent":250,"bytes_total":1000,"node":"workstation-1","error":null}}]}""",
        )
        coEvery { capture.sessions() } returns AgentResult.Ok(body)
        val vm = vm()
        vm.loadSessions()

        val s = (vm.state.value.sessions as Load.Ready).value.single()
        assertEquals(UploadState.Uploading, s.upload)
        assertEquals(0.25f, s.progress!!, 0.0001f)
        assertEquals(listOf("Dock", "Aisle 1"), s.rooms)
        assertNull(s.uploadError)
    }

    private val sensorsApi = mockk<SensorsApi>()

    private fun overviewVm(): VisorViewModel {
        coEvery { apis.sensors(DeviceId("v1")) } returns sensorsApi
        return vm()
    }

    @Test
    fun absentServiceIsNotFittedAndAFailedReadIsNot() = runTest(dispatcher) {
        coEvery { sensorsApi.clock() } returns AgentResult.Ok(ClockStatus(present = true, timebase = "hub_freerun", locked = true, sigmaNs = 420_000))
        coEvery { sensorsApi.storage() } returns AgentResult.Ok(StorageStatus(present = false))
        coEvery { sensorsApi.sensors() } returns AgentResult.Err(AgentError.Network(java.io.IOException("down")))
        val vm = overviewVm()
        vm.loadOverview()

        val o = (vm.state.value.overview as Load.Ready).value
        assertEquals(Section.Ready(ClockUi(true, "hub_freerun", 420_000, emptyList())), o.clock)
        assertEquals(Section.NotFitted, o.storage)
        assertTrue(o.sensors is Section.Failed)
    }

    @Test
    fun aFailedReadKeepsTheLastValueMarkedStale() = runTest(dispatcher) {
        coEvery { sensorsApi.clock() } returnsMany listOf(
            AgentResult.Ok(ClockStatus(present = true, locked = true, sigmaNs = 1)),
            AgentResult.Err(AgentError.Server(500, null)),
        )
        coEvery { sensorsApi.storage() } returns AgentResult.Ok(StorageStatus(present = false))
        coEvery { sensorsApi.sensors() } returns AgentResult.Ok(SensorsStatus(present = false))
        val vm = overviewVm()
        vm.loadOverview()
        vm.loadOverview()

        val clock = (vm.state.value.overview as Load.Ready).value.clock as Section.Ready
        assertTrue(clock.stale)
        assertTrue(clock.value.locked)
    }

    @Test
    fun previewUsesTheCaptureWhepRouteOfTheFirstCamera() = runTest(dispatcher) {
        val endpoints = mockk<AgentEndpoints> {
            coEvery { endpointFor(DeviceId("v1")) } returns AgentEndpoint.forHost("192.168.1.50", "k", DeviceId("v1"))
        }
        coEvery { capture.state() } returns AgentResult.Ok(recording)
        coEvery { apis.sensors(DeviceId("v1")) } returns null
        val vm = VisorViewModel("v1", apis, endpoints)
        vm.loadCapture()
        vm.resolvePreview()

        val preview = vm.state.value.preview as VideoSource.AgentWhep
        assertEquals("http://192.168.1.50:8080/api/plugins/com.altnautica.world-engine/x/atlas/capture", preview.baseUrl)
        assertEquals("cam_fl", preview.streamId)
        assertEquals(listOf("cam_fl"), vm.state.value.cameras)
    }
}
