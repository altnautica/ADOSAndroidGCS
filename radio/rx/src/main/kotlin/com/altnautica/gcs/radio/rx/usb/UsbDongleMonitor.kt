package com.altnautica.gcs.radio.rx.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import com.altnautica.gcs.radio.usb.RadioDongles
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** The USB radio as the phone sees it right now. */
sealed interface DongleStatus {
    data object None : DongleStatus

    /** A radio sold under the same name but built on a chipset the receiver cannot drive. */
    data class Unsupported(val productId: Int) : DongleStatus
    data class NeedsPermission(val device: UsbDevice, val chip: String) : DongleStatus
    data class Ready(val device: UsbDevice, val chip: String) : DongleStatus
}

/** Finds the attached USB radio and handles the per-device USB permission. */
@Singleton
class UsbDongleMonitor @Inject constructor(@ApplicationContext private val context: Context) {

    private val usb: UsbManager? = context.getSystemService(UsbManager::class.java)

    fun current(): DongleStatus {
        val manager = usb ?: return DongleStatus.None
        val devices = manager.deviceList.values
        val supported = devices.firstOrNull { RadioDongles.isSupported(it.vendorId, it.productId) }
        if (supported != null) {
            val chip = chipName(supported.productId)
            return if (manager.hasPermission(supported)) DongleStatus.Ready(supported, chip) else DongleStatus.NeedsPermission(supported, chip)
        }
        val lookalike = devices.firstOrNull { RadioDongles.isUnsupportedLookalike(it.vendorId, it.productId) }
        return if (lookalike != null) DongleStatus.Unsupported(lookalike.productId) else DongleStatus.None
    }

    /**
     * The radio status, re-read on detach, on a permission answer and every
     * [RESCAN_MS] (attach is only broadcast to activities).
     */
    val status: Flow<DongleStatus> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                trySend(current())
            }
        }
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_PERMISSION)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        val poll = launch {
            while (true) {
                trySend(current())
                delay(RESCAN_MS)
            }
        }
        awaitClose {
            poll.cancel()
            context.unregisterReceiver(receiver)
        }
    }.distinctUntilChanged { a, b -> a.key() == b.key() }

    /** Shows the system USB permission prompt for [device]. */
    fun requestPermission(device: UsbDevice) {
        val manager = usb ?: return
        val intent = Intent(ACTION_PERMISSION).setPackage(context.packageName)
        // Mutable: the system adds the device and the grant to this intent.
        val pending = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager.requestPermission(device, pending)
    }

    fun open(device: UsbDevice): UsbDeviceConnection? = usb?.openDevice(device)

    private fun DongleStatus.key(): String = when (this) {
        DongleStatus.None -> "none"
        is DongleStatus.Unsupported -> "unsupported:$productId"
        is DongleStatus.NeedsPermission -> "permission:${device.deviceName}"
        is DongleStatus.Ready -> "ready:${device.deviceName}"
    }

    companion object {
        private const val RESCAN_MS = 2_000L
        private const val ACTION_PERMISSION = "com.altnautica.gcs.radio.rx.USB_PERMISSION"

        fun chipName(productId: Int): String = if (productId == 0xA81A) "RTL8812EU" else "RTL8812AU"
    }
}
