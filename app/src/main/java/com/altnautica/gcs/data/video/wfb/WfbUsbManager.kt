package com.altnautica.gcs.data.video.wfb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages USB host access to RTL8812EU/RTL8812AU WiFi adapters for WFB-ng Mode B.
 *
 * Handles device discovery, permission requests, connection lifecycle, and hot-plug events.
 */
@Singleton
class WfbUsbManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    companion object {
        private const val TAG = "WfbUsbManager"
        private const val ACTION_USB_PERMISSION = "com.altnautica.gcs.USB_PERMISSION"

        // RTL8812EU / RTL8812AU identifiers
        private const val VENDOR_REALTEK = 0x0BDA
        private const val PRODUCT_RTL8812EU = 0xB812
        private const val PRODUCT_RTL8812AU = 0x8812
        // Some adapters use alternate PIDs
        private const val PRODUCT_RTL8812AU_ALT = 0x881A
    }

    private val usbManager: UsbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    private var activeConnection: UsbDeviceConnection? = null
    private var activeDevice: UsbDevice? = null

    private val _connectedDevice = MutableStateFlow<UsbDevice?>(null)
    val connectedDevice: StateFlow<UsbDevice?> = _connectedDevice.asStateFlow()

    private val _hasPermission = MutableStateFlow(false)
    val hasPermission: StateFlow<Boolean> = _hasPermission.asStateFlow()

    private var permissionCallback: ((Boolean) -> Unit)? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    Log.d(TAG, "USB permission ${if (granted) "granted" else "denied"} for ${device?.deviceName}")
                    _hasPermission.value = granted
                    permissionCallback?.invoke(granted)
                    permissionCallback = null
                }

                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    if (device != null && isWfbAdapter(device)) {
                        Log.i(TAG, "WFB adapter attached: ${device.deviceName}")
                        _connectedDevice.value = device
                    }
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    if (device != null && device.deviceId == activeDevice?.deviceId) {
                        Log.i(TAG, "WFB adapter detached: ${device.deviceName}")
                        closeConnection()
                    }
                }
            }
        }
    }

    /**
     * Register the USB broadcast receiver. Call from Activity.onCreate() or Application.
     */
    fun register() {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(usbReceiver, filter)
        }
        Log.d(TAG, "USB receiver registered")
    }

    /**
     * Unregister the USB broadcast receiver.
     */
    fun unregister() {
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (e: IllegalArgumentException) {
            // Already unregistered
        }
    }

    /**
     * Find the first connected RTL8812 adapter.
     * @return The UsbDevice, or null if none found.
     */
    fun findAdapter(): UsbDevice? {
        return usbManager.deviceList.values.firstOrNull { isWfbAdapter(it) }
    }

    /**
     * Request USB permission for the given device.
     * @param device The USB device to request permission for.
     * @param callback Called with true if permission granted, false if denied.
     */
    fun requestPermission(device: UsbDevice, callback: (Boolean) -> Unit) {
        if (usbManager.hasPermission(device)) {
            _hasPermission.value = true
            callback(true)
            return
        }

        permissionCallback = callback
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, 0, Intent(ACTION_USB_PERMISSION), flags
        )
        usbManager.requestPermission(device, pendingIntent)
        Log.d(TAG, "USB permission requested for ${device.deviceName}")
    }

    /**
     * Open a connection to the adapter and return its file descriptor.
     * @param device The USB device to connect to.
     * @return The file descriptor, or -1 on failure.
     */
    fun openDevice(device: UsbDevice): Int {
        if (!usbManager.hasPermission(device)) {
            Log.e(TAG, "No permission for ${device.deviceName}")
            return -1
        }

        val connection = usbManager.openDevice(device)
        if (connection == null) {
            Log.e(TAG, "Failed to open ${device.deviceName}")
            return -1
        }

        activeConnection = connection
        activeDevice = device
        _connectedDevice.value = device
        _hasPermission.value = true

        val fd = connection.fileDescriptor
        Log.i(TAG, "Opened ${device.deviceName}, fd=$fd")
        return fd
    }

    /**
     * Close the active USB connection.
     */
    fun closeConnection() {
        activeConnection?.close()
        activeConnection = null
        activeDevice = null
        _connectedDevice.value = null
        _hasPermission.value = false
        Log.d(TAG, "USB connection closed")
    }

    /**
     * Check if a USB device is a supported WFB-ng WiFi adapter.
     */
    fun isWfbAdapter(device: UsbDevice): Boolean {
        return device.vendorId == VENDOR_REALTEK && (
            device.productId == PRODUCT_RTL8812EU ||
            device.productId == PRODUCT_RTL8812AU ||
            device.productId == PRODUCT_RTL8812AU_ALT
        )
    }
}
