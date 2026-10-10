package com.altnautica.gcs.core.link.direct

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import com.altnautica.gcs.core.model.DeviceId
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialProber
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A USB serial device that may be a flight controller. */
data class UsbFlightController(
    val nodeId: DeviceId,
    /** `UsbDevice.deviceName`, stable while attached. */
    val deviceName: String,
    val vendorId: Int,
    val productId: Int,
    val productName: String?,
    val hasPermission: Boolean,
) {
    val label: String
        get() = productName?.takeIf { it.isNotBlank() } ?: "USB %04x:%04x".format(vendorId, productId)
}

/**
 * Tracks USB serial devices attached to the phone. A newly attached device
 * triggers the system permission prompt once; [requestPermission] asks again.
 */
@Singleton
class UsbFlightControllers @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val usbManager: UsbManager? = context.getSystemService(UsbManager::class.java)
    private val prober = UsbSerialProber.getDefaultProber()

    private val _devices = MutableStateFlow<List<UsbFlightController>>(emptyList())
    val devices: StateFlow<List<UsbFlightController>> = _devices.asStateFlow()

    private val prompted = mutableSetOf<String>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val device = intent.usbDevice()
            if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED && device != null) {
                refresh()
                if (usbManager?.hasPermission(device) == false && prompted.add(device.deviceName)) {
                    requestPermission(device.deviceName)
                }
            } else {
                if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED && device != null) prompted.remove(device.deviceName)
                refresh()
            }
        }
    }

    init {
        if (usbManager != null) {
            val filter = IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                addAction(ACTION_USB_PERMISSION)
            }
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            refresh()
        }
    }

    /** Shows the system USB permission prompt for [deviceName]. */
    fun requestPermission(deviceName: String) {
        val manager = usbManager ?: return
        val device = manager.deviceList[deviceName] ?: return
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        manager.requestPermission(device, PendingIntent.getBroadcast(context, 0, intent, flags))
    }

    /** Serial driver for an attached device, for opening a transport. */
    fun driverFor(nodeId: DeviceId): UsbSerialDriver? {
        val manager = usbManager ?: return null
        val name = _devices.value.firstOrNull { it.nodeId == nodeId }?.deviceName ?: return null
        val device = manager.deviceList[name] ?: return null
        return prober.probeDevice(device)
    }

    fun manager(): UsbManager? = usbManager

    fun refresh() {
        val manager = usbManager ?: return
        _devices.value = prober.findAllDrivers(manager).map { driver ->
            val d = driver.device
            UsbFlightController(
                nodeId = DeviceId("usb:${"%04x".format(d.vendorId)}:${"%04x".format(d.productId)}:${d.deviceName}"),
                deviceName = d.deviceName,
                vendorId = d.vendorId,
                productId = d.productId,
                productName = d.productName,
                hasPermission = manager.hasPermission(d),
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.usbDevice(): UsbDevice? =
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

    private companion object {
        const val ACTION_USB_PERMISSION = "com.altnautica.gcs.core.link.USB_PERMISSION"
    }
}
