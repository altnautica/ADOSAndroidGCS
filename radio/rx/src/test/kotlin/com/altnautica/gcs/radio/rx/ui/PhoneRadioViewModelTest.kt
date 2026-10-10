package com.altnautica.gcs.radio.rx.ui

import com.altnautica.gcs.core.agent.node.LanNodeSource
import com.altnautica.gcs.core.data.vault.CredentialVault
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.radio.rx.invite.EnrollmentState
import com.altnautica.gcs.radio.rx.invite.FlakyCipher
import com.altnautica.gcs.radio.rx.invite.InviteEnrollment
import com.altnautica.gcs.radio.rx.invite.InviteStore
import com.altnautica.gcs.radio.rx.invite.MemoryPreferences
import com.altnautica.gcs.radio.rx.invite.sampleInvite
import com.altnautica.gcs.radio.rx.session.PhoneRadioSession
import com.altnautica.gcs.radio.rx.session.PhoneRadioState
import com.altnautica.gcs.radio.rx.usb.DongleStatus
import com.altnautica.gcs.radio.rx.usb.UsbDongleMonitor
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
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

@OptIn(ExperimentalCoroutinesApi::class)
class PhoneRadioViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val vaultScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = MemoryPreferences()
    private val cipher = FlakyCipher()
    private val enrollment = mockk<InviteEnrollment>()
    private val session = mockk<PhoneRadioSession>(relaxed = true) {
        every { state } returns MutableStateFlow(PhoneRadioState.Stopped)
        every { stats } returns MutableStateFlow(null)
        every { links } returns MutableStateFlow(emptyList())
    }

    private fun store() = InviteStore(CredentialVault(prefs, cipher, vaultScope))

    private fun vm(store: InviteStore) = PhoneRadioViewModel(
        dongles = mockk<UsbDongleMonitor> { every { status } returns flowOf(DongleStatus.None) },
        store = store,
        enrollment = enrollment,
        session = session,
        lan = mockk<LanNodeSource> { every { nodes } returns flowOf(emptyList()) },
    )

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        vaultScope.cancel()
    }

    @Test
    fun unreadableKeysShowAnErrorUntilARetryReadsThem() = runTest(dispatcher) {
        val invite = sampleInvite()
        InviteStore(CredentialVault(prefs, FlakyCipher(), vaultScope)).save(invite)
        cipher.failing = true
        val vm = vm(store())
        backgroundScope.launch { vm.state.collect {} }

        assertFalse(vm.state.value.loading)
        assertEquals(KeysError.Unreadable, vm.state.value.keysError)
        assertNull(vm.state.value.invite)

        vm.retryKeys()
        assertEquals(KeysError.Unreadable, vm.state.value.keysError)

        cipher.failing = false
        vm.retryKeys()
        assertNull(vm.state.value.keysError)
        assertEquals(invite, vm.state.value.invite)
    }

    @Test
    fun aRefusedForgetKeepsTheKeysAndForgetsOnRetry() = runTest(dispatcher) {
        val invite = sampleInvite()
        val store = store()
        store.save(invite)
        val vm = vm(store)
        backgroundScope.launch { vm.state.collect {} }
        assertEquals(invite, vm.state.value.invite)

        cipher.failing = true
        vm.forget()
        assertEquals(KeysError.NotForgotten, vm.state.value.keysError)
        assertEquals(invite, vm.state.value.invite)

        cipher.failing = false
        vm.retryKeys()
        assertNull(vm.state.value.keysError)
        assertNull(vm.state.value.invite)
    }

    @Test
    fun keysThatDidNotSaveAreOfferedAndSavedAgain() = runTest(dispatcher) {
        val invite = sampleInvite()
        every { enrollment.run(any(), any(), any()) } returns flowOf(EnrollmentState.NotSaved(invite))
        coEvery { enrollment.save(invite) } returns EnrollmentState.Approved(invite)
        val vm = vm(store())
        backgroundScope.launch { vm.state.collect {} }

        vm.enroll(GroundOption(DeviceId("gs-1"), "Field GS", live = true))
        assertEquals(EnrollmentState.NotSaved(invite), vm.state.value.enrollment)

        vm.saveAgain()
        assertEquals(EnrollmentState.Approved(invite), vm.state.value.enrollment)
    }
}
