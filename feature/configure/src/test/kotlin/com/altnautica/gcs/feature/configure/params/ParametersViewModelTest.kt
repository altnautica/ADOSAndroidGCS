package com.altnautica.gcs.feature.configure.params

import com.altnautica.gcs.core.link.param.ParamLoadResult
import com.altnautica.gcs.core.link.param.ParamSetResult
import com.altnautica.gcs.core.link.param.ParamValue
import com.altnautica.gcs.core.model.CommandResult
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.VehicleCommand
import com.altnautica.gcs.feature.configure.FakeParamService
import com.altnautica.gcs.feature.configure.FakeVehicle
import com.altnautica.gcs.feature.configure.common.ConfigureTarget
import com.altnautica.gcs.feature.configure.common.ConfigureTargetSource
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
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

@OptIn(ExperimentalCoroutinesApi::class)
class ParametersViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val target = MutableStateFlow<ConfigureTarget?>(null)
    private val metadata = ParamMetadataSet(
        "ardupilot-copter", null,
        mapOf(
            "RATE" to ParamMeta("RATE", "Rate", "roll rate", min = 1.0, max = 10.0, group = "ATC", defaultValue = 5.0),
            "ADV" to ParamMeta("ADV", "Adv", "", group = "ADV", advanced = true),
            "REBOOT" to ParamMeta("REBOOT", "Reboot", "", group = "SYS", rebootRequired = true),
        ),
    )
    private var requestedKey: String? = null

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private var clock = 0L

    private fun vm() = ParametersViewModel(DeviceId("d"), ConfigureTargetSource { target }, { clock }, flowOf(Unit)) { key ->
        requestedKey = key
        metadata
    }

    private fun content(vm: ParametersViewModel) = vm.state.value as ParametersUiState.Content

    @Test
    fun noVehicleThenLoadingThenContentWithMetadata() = runTest(dispatcher) {
        val vm = vm()
        assertEquals(ParametersUiState.NoVehicle, vm.state.value)

        val params = FakeParamService(mapOf("RATE" to 5.0), complete = false).apply { loading.value = true }
        target.value = FakeVehicle(vehicleType = 13).target(params)
        assertEquals(1, params.refreshes)
        assertTrue(vm.state.value is ParametersUiState.Loading)
        assertEquals("ardupilot-copter", requestedKey)

        params.snapshot.value = FakeParamService.snapshotOf(mapOf("RATE" to 7.0, "ADV" to 1.0, "REBOOT" to 0.0))
        params.loading.value = false
        val c = content(vm)
        assertEquals(listOf("RATE", "REBOOT"), c.rows.map { it.name }) // advanced hidden by default
        assertTrue(c.rows.first().modified)

        vm.setFilter { it.copy(showAdvanced = true, modifiedOnly = true) }
        assertEquals(listOf("RATE"), content(vm).rows.map { it.name })
        vm.setFilter { ParamFilter(query = "roll") }
        assertEquals(listOf("RATE"), content(vm).rows.map { it.name })
    }

    @Test
    fun loadFailureIsReported() = runTest(dispatcher) {
        val vm = vm()
        val params = FakeParamService(emptyMap(), complete = false).apply { refreshResult = ParamLoadResult.Failed("no link") }
        target.value = FakeVehicle().target(params)
        assertEquals(ParametersUiState.LoadFailed("no link"), vm.state.value)
    }

    @Test
    fun failedLoadStaysFailedWhenTheFcBroadcastsAFewValues() = runTest(dispatcher) {
        val vm = vm()
        // ArduPilot sends PARAM_VALUE on its own when a value changes (baro ground pressure); that is no parameter list.
        val params = FakeParamService(mapOf("BARO1_GND_PRESS" to 101326.0, "BARO2_GND_PRESS" to 101323.0), complete = false)
            .apply { refreshResult = ParamLoadResult.Failed("noFcLink") }
        target.value = FakeVehicle().target(params)
        assertEquals(ParametersUiState.LoadFailed("noFcLink"), vm.state.value)
    }

    @Test
    fun loadFailureReasonsHaveOperatorText() {
        assertEquals(com.altnautica.gcs.feature.configure.R.string.cfg_block_no_vehicle, paramLoadReasonRes("noFcLink"))
        assertEquals(com.altnautica.gcs.feature.configure.R.string.cfg_result_timeout, paramLoadReasonRes("timeout"))
        assertNull(paramLoadReasonRes("writeFailed"))
    }

    @Test
    fun invalidInputNeverWrites() = runTest(dispatcher) {
        val vm = vm()
        val params = FakeParamService(mapOf("RATE" to 5.0))
        target.value = FakeVehicle().target(params)
        vm.requestWrite("RATE", "11")
        assertEquals(ParamMessage.Invalid("RATE", ParamInvalidReason.AboveMax), vm.message.value)
        assertTrue(params.writes.isEmpty())
    }

    @Test
    fun disarmedWritesDirectlyArmedNeedsConfirmation() = runTest(dispatcher) {
        val vm = vm()
        val params = FakeParamService(mapOf("RATE" to 5.0, "REBOOT" to 0.0))
        val vehicle = FakeVehicle()
        target.value = vehicle.target(params)

        vm.requestWrite("RATE", "6")
        assertEquals(listOf("RATE" to 6.0), params.writes)
        assertEquals(ParamMessage.Saved("RATE", 6.0, false), vm.message.value)

        vehicle.setArmed(true)
        vm.requestWrite("REBOOT", "1")
        assertEquals(PendingWrite("REBOOT", 1.0), vm.pendingWrite.value)
        assertEquals(1, params.writes.size)
        vm.confirmPendingWrite()
        assertNull(vm.pendingWrite.value)
        assertEquals(ParamMessage.Saved("REBOOT", 1.0, true), vm.message.value)

        params.nextSet = { n, v -> ParamSetResult.Mismatch(v, ParamValue(n, 4.0, 9, 0)) }
        vehicle.setArmed(false)
        vm.requestWrite("RATE", "9")
        assertEquals(ParamMessage.Clamped("RATE", 9.0, 4.0), vm.message.value)
        params.nextSet = { _, _ -> ParamSetResult.Timeout }
        vm.requestWrite("RATE", "2")
        assertEquals(ParamMessage.WriteFailed("RATE", ParamWriteFailure.Timeout), vm.message.value)
    }

    @Test
    fun importComparesAndAppliesOnlyChangedValues() = runTest(dispatcher) {
        val vm = vm()
        val params = FakeParamService(mapOf("RATE" to 5.0, "REBOOT" to 0.0))
        target.value = FakeVehicle().target(params)

        val exported = vm.exportText(ParamFileFormat.Qgc)!!
        assertTrue(exported.contains("\tRATE\t5\t9"))

        vm.importText("RATE,8\nREBOOT,0\nGONE,1\n")
        val review = vm.importReview.value!!
        assertEquals(listOf("RATE"), review.comparison.changed.map { it.name })
        assertEquals(listOf("GONE"), review.comparison.unknownNames)
        vm.applyImport()
        assertEquals(listOf("RATE" to 8.0), params.writes)
        assertEquals(1, vm.importReview.value!!.applied)
        assertEquals(false, vm.importReview.value!!.running)

        vm.importText("# nothing\n")
        assertTrue(vm.message.value is ParamMessage.FileUnreadable)
    }

    @Test
    fun armedImportIsRefusedUntilDisarmed() = runTest(dispatcher) {
        val vm = vm()
        val params = FakeParamService(mapOf("RATE" to 5.0, "REBOOT" to 0.0))
        val vehicle = FakeVehicle()
        target.value = vehicle.target(params)
        vehicle.setArmed(true)

        vm.importText("RATE,8\nREBOOT,1\n")
        vm.applyImport()
        assertTrue(params.writes.isEmpty())
        assertEquals(0, vm.importReview.value!!.applied)
        assertEquals(false, vm.importReview.value!!.running)

        vehicle.setArmed(false)
        vm.applyImport()
        assertEquals(listOf("RATE" to 8.0, "REBOOT" to 1.0), params.writes)
        assertEquals(2, vm.importReview.value!!.applied)
    }

    @Test
    fun aVehicleThatArmsMidImportStopsTheBatch() = runTest(dispatcher) {
        val vm = vm()
        val params = FakeParamService(mapOf("RATE" to 5.0, "REBOOT" to 0.0))
        val vehicle = FakeVehicle()
        target.value = vehicle.target(params)
        params.nextSet = { n, v -> vehicle.setArmed(true); ParamSetResult.Confirmed(ParamValue(n, v, 9, 0)) }

        vm.importText("RATE,8\nREBOOT,1\n")
        vm.applyImport()
        assertEquals(listOf("RATE" to 8.0), params.writes)
        assertEquals(false, vm.importReview.value!!.running)
    }

    @Test
    fun rowsAreSortedByGroupThenName() = runTest(dispatcher) {
        val vm = vm()
        target.value = FakeVehicle().target(FakeParamService(mapOf("Z_ONE" to 1.0, "REBOOT" to 0.0, "B" to 1.0, "RATE" to 5.0, "ATC_B" to 1.0)))
        val rows = content(vm).rows
        assertEquals(listOf("ATC_B", "RATE", "B", "REBOOT", "Z_ONE"), rows.map { it.name })

        val groups = rows.grouped()
        assertEquals(listOf("ATC", "B", "SYS", "Z"), groups.map { it.first })
        val headers = headerIndices(groups)
        assertEquals(listOf(0, 3, 5, 7), headers)
        assertEquals(3, groupJumpTarget(headers, firstVisible = 1, forward = true))
        assertEquals(0, groupJumpTarget(headers, firstVisible = 3, forward = false))
        assertNull(groupJumpTarget(headers, firstVisible = 7, forward = true))
    }

    @Test
    fun importAppliesOnlySelectedRowsAndListsUnknownNames() = runTest(dispatcher) {
        val vm = vm()
        val params = FakeParamService(mapOf("RATE" to 5.0, "REBOOT" to 0.0))
        target.value = FakeVehicle().target(params)

        vm.importText("RATE,8\nREBOOT,1\nGONE,1\n")
        assertEquals(listOf("GONE"), vm.importReview.value!!.comparison.unknownNames)
        assertEquals(listOf("RATE", "REBOOT"), vm.importReview.value!!.selected.map { it.name })
        vm.toggleImportRow("RATE")
        assertEquals(listOf("REBOOT"), vm.importReview.value!!.selected.map { it.name })
        vm.toggleImportRow("RATE")
        vm.toggleImportRow("REBOOT")
        vm.applyImport()
        assertEquals(listOf("RATE" to 8.0), params.writes)
        assertEquals(1, vm.importReview.value!!.applied)
    }

    @Test
    fun rebootPendingIsSetByARebootParamAndClearedByAnAcceptedReboot() = runTest(dispatcher) {
        val vm = vm()
        val params = FakeParamService(mapOf("RATE" to 5.0, "REBOOT" to 0.0))
        val vehicle = FakeVehicle()
        coEvery { vehicle.control.run(any()) } returns CommandResult.Accepted
        target.value = vehicle.target(params)

        vm.requestWrite("RATE", "6")
        assertFalse(vm.rebootPending.value)
        vm.requestWrite("REBOOT", "1")
        assertTrue(vm.rebootPending.value)

        vehicle.setArmed(true)
        vm.reboot()
        coVerify(exactly = 0) { vehicle.control.run(VehicleCommand.RebootAutopilot) }
        assertTrue(vm.rebootPending.value)

        vehicle.setArmed(false)
        vm.reboot()
        coVerify(exactly = 1) { vehicle.control.run(VehicleCommand.RebootAutopilot) }
        assertFalse(vm.rebootPending.value)
        assertEquals(ParamMessage.Reboot(CommandResult.Accepted), vm.message.value)
    }

    @Test
    fun rebootIsRefusedOnAStaleLink() = runTest(dispatcher) {
        clock = 10_000L // the vehicle's last heartbeat is at t=0, older than the freshness limit
        val vm = vm()
        val vehicle = FakeVehicle()
        target.value = vehicle.target(FakeParamService(mapOf("REBOOT" to 0.0)))
        vm.requestWrite("REBOOT", "1")
        assertFalse(content(vm).linkFresh)
        vm.reboot()
        coVerify(exactly = 0) { vehicle.control.run(any()) }
        assertTrue(vm.rebootPending.value)
    }
}
