package com.altnautica.gcs.feature.node

import com.altnautica.gcs.core.agent.pairing.PairingFailure
import com.altnautica.gcs.core.agent.pairing.PairingOutcome
import com.altnautica.gcs.core.agent.pairing.PairingService
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.feature.fleet.data.NodeRepository
import com.altnautica.gcs.feature.fleet.data.NodeVitalsRepository
import com.altnautica.gcs.feature.node.common.NodeApis
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
import org.junit.Before
import org.junit.Test

/** An unreadable credential store is reported with a retry, never as an agent failure or a silent success. */
@OptIn(ExperimentalCoroutinesApi::class)
class NodeDetailForgetTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val pairing = mockk<PairingService>()
    private val id = DeviceId("d1")

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm() = NodeDetailViewModel(
        "d1", NodeRepository(emptySet()), mockk<NodeVitalsRepository>(relaxed = true), pairing, mockk<NodeApis>(relaxed = true),
    )

    @Test
    fun forgetRefusedByTheCredentialStoreOffersARetryThatRepeatsTheForget() = runTest(dispatcher) {
        coEvery { pairing.forget(id) } returnsMany listOf(
            PairingOutcome.Failure(PairingFailure.VaultUnavailable),
            PairingOutcome.Success(Unit),
        )
        val vm = vm()

        vm.forgetLocally()
        assertEquals(ForgetState.CredentialStoreUnavailable, vm.forget.value)

        vm.retryForget()
        assertEquals(ForgetState.Done, vm.forget.value)
        coVerify(exactly = 2) { pairing.forget(id) }
    }

    @Test
    fun unpairRefusedByTheCredentialStoreRetriesTheUnpair() = runTest(dispatcher) {
        coEvery { pairing.unpair(id) } returnsMany listOf(
            PairingOutcome.Failure(PairingFailure.VaultUnavailable),
            PairingOutcome.Success(Unit),
        )
        val vm = vm()

        vm.unpair()
        assertEquals(ForgetState.CredentialStoreUnavailable, vm.forget.value)
        vm.retryForget()
        assertEquals(ForgetState.Done, vm.forget.value)
        coVerify(exactly = 2) { pairing.unpair(id) }
        coVerify(exactly = 0) { pairing.forget(any()) }
    }
}
