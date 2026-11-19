package com.altnautica.gcs.radio.rx.session

import android.content.Context
import android.hardware.usb.UsbDevice
import com.altnautica.gcs.radio.rx.invite.InviteStore
import com.altnautica.gcs.radio.rx.invite.sampleInvite
import com.altnautica.gcs.radio.rx.usb.DongleStatus
import com.altnautica.gcs.radio.rx.usb.UsbDongleMonitor
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Optional

/** A start whose USB open fails reports why: the radio went, or access was refused. */
class PhoneRadioSessionTest {

    private val device = mockk<UsbDevice> {
        every { deviceName } returns "/dev/bus/usb/001/002"
    }

    private fun session(afterOpen: DongleStatus): PhoneRadioSession {
        val store = mockk<InviteStore> { coEvery { load() } returns sampleInvite() }
        val dongles = mockk<UsbDongleMonitor> {
            // Ready when the session reads it, then whatever the phone sees once the open failed.
            every { current() } returnsMany listOf(DongleStatus.Ready(device, "RTL8812EU"), afterOpen)
            every { open(device) } returns null
        }
        return PhoneRadioSession(mockk<Context>(relaxed = true), store, dongles, Optional.empty(), mockk(relaxed = true))
    }

    private fun failureOf(session: PhoneRadioSession): PhoneRadioFailure = runBlocking {
        session.start()
        val failed = withTimeout(5_000) { session.state.first { it is PhoneRadioState.Failed } }
        (failed as PhoneRadioState.Failed).reason
    }

    @Test
    fun `a radio unplugged before the open reports it was unplugged`() {
        assertEquals(PhoneRadioFailure.DongleRemoved, failureOf(session(DongleStatus.None)))
    }

    @Test
    fun `a radio still attached when the open fails reports refused access`() {
        assertEquals(
            PhoneRadioFailure.NoPermission,
            failureOf(session(DongleStatus.NeedsPermission(device, "RTL8812EU"))),
        )
    }
}
