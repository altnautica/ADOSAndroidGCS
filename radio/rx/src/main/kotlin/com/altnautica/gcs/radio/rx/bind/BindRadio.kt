package com.altnautica.gcs.radio.rx.bind

import android.content.Context
import android.hardware.usb.UsbDeviceConnection
import android.os.ParcelFileDescriptor
import com.altnautica.gcs.radio.rx.AlternateRadio
import com.altnautica.gcs.radio.rx.usb.DongleStatus
import com.altnautica.gcs.radio.rx.usb.UsbDongleMonitor
import com.altnautica.gcs.radio.rx.usb.UsbRadioConnection
import com.altnautica.gcs.radio.usb.IUsbRadio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A bind that could not run or did not pair, with the sentence the operator reads. */
class BindFailure(message: String) : Exception(message)

/**
 * The radio a bind runs on, opened on the bind channel while the phone-radio
 * session is stopped: the USB dongle, or a debug build's bench radio carrying
 * exactly the bind planes.
 */
class BindRadio private constructor(
    val radio: IUsbRadio,
    val frames: ParcelFileDescriptor,
    private val binding: UsbRadioConnection?,
    private val usb: UsbDeviceConnection?,
) : AutoCloseable {

    override fun close() {
        runCatching { radio.close() }
        runCatching { frames.close() }
        binding?.disconnect()
        usb?.close()
    }

    companion object {
        suspend fun open(context: Context, dongles: UsbDongleMonitor, alternate: AlternateRadio?): BindRadio =
            withContext(Dispatchers.IO) {
                if (alternate != null && alternate.available) openAlternate(alternate) else openDongle(context, dongles)
            }

        private fun openAlternate(alternate: AlternateRadio): BindRadio {
            val radio = alternate.openChannels(
                rxChannels = listOf(BindProfile.channelId(BindProfile.RX_PORT)),
                txChannels = listOf(BindProfile.channelId(BindProfile.TX_PORT)),
            )
            try {
                radio.open(null, 0, 0)
                radio.setChannel(BindProfile.CHANNEL, BindProfile.BANDWIDTH_MHZ)
                return BindRadio(radio, radio.frames(), null, null)
            } catch (e: Exception) {
                runCatching { radio.close() }
                throw BindFailure("The bench radio did not open: ${e.message}")
            }
        }

        private suspend fun openDongle(context: Context, dongles: UsbDongleMonitor): BindRadio {
            val device = when (val d = dongles.current()) {
                is DongleStatus.Ready -> d.device
                is DongleStatus.NeedsPermission -> throw BindFailure("Allow USB access to the radio first")
                else -> throw BindFailure("Plug in the radio to bind a drone")
            }
            val usb = dongles.open(device) ?: throw BindFailure("The radio could not be opened; replug it and try again")
            val binding = UsbRadioConnection(context) {}
            try {
                val radio = binding.connect()
                ParcelFileDescriptor.fromFd(usb.fileDescriptor).use { pfd -> radio.open(pfd, device.vendorId, device.productId) }
                radio.setChannel(BindProfile.CHANNEL, BindProfile.BANDWIDTH_MHZ)
                return BindRadio(radio, radio.frames(), binding, usb)
            } catch (e: Exception) {
                binding.disconnect()
                usb.close()
                throw BindFailure("The radio did not start: ${e.message}")
            }
        }
    }
}
