package com.altnautica.gcs.ui.settings

import androidx.lifecycle.ViewModel
import com.altnautica.gcs.data.serial.DetectedDevice
import com.altnautica.gcs.data.serial.UsbSerialManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * ViewModel boundary in front of the USB-serial hardware.
 *
 * The panel used to take [UsbSerialManager] as a parameter, which put USB
 * device enumeration and open/close calls inside the composition: a
 * recomposition could open a serial port, and the panel could not be previewed
 * or tested. The composable now sees only flows and callbacks.
 */
@HiltViewModel
class UsbSerialViewModel @Inject constructor(
    private val manager: UsbSerialManager,
) : ViewModel() {

    val isConnected: StateFlow<Boolean> = manager.isConnected
    val connectedDevice: StateFlow<String?> = manager.connectedDevice
    val detectedDevices: StateFlow<List<DetectedDevice>> = manager.detectedDevices
    val baudRate: StateFlow<Int> = manager.baudRate

    val baudRates: List<Int> = UsbSerialManager.BAUD_RATES

    fun scan() {
        manager.scanDevices()
    }

    fun setBaudRate(rate: Int) {
        manager.setBaudRate(rate)
    }

    fun connect(device: DetectedDevice) {
        manager.connect(device)
    }

    fun disconnect() {
        manager.disconnect()
    }
}
