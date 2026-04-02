package com.altnautica.gcs.data.serial

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import com.altnautica.gcs.data.mavlink.MavLinkParser
import com.altnautica.gcs.data.telemetry.ConnectionState
import com.altnautica.gcs.data.telemetry.ConnectionStatus
import com.altnautica.gcs.data.telemetry.TelemetryStore
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.Ch34xSerialDriver
import com.hoho.android.usbserial.driver.Cp21xxSerialDriver
import com.hoho.android.usbserial.driver.FtdiSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import dagger.hilt.android.qualifiers.ApplicationContext
import io.dronefleet.mavlink.MavlinkConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Known flight controller USB VID:PID pairs.
 */
data class FcUsbId(val vendorId: Int, val productId: Int, val description: String)

@Singleton
class UsbSerialManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val parser: MavLinkParser,
    private val telemetryStore: TelemetryStore,
) {

    companion object {
        private const val TAG = "UsbSerialManager"
        private const val DEFAULT_BAUD_RATE = 57600
        private const val READ_BUFFER_SIZE = 4096
        private const val READ_TIMEOUT_MS = 100

        val FC_VID_PIDS = listOf(
            FcUsbId(0x0483, 0x5740, "STM32 VCP (ArduPilot)"),
            FcUsbId(0x26AC, 0x0011, "Pixhawk"),
            FcUsbId(0x2DAE, 0x1011, "SpeedyBee"),
            FcUsbId(0x2DAE, 0x1012, "SpeedyBee F405"),
            FcUsbId(0x1209, 0x5740, "Generic STM32 DFU"),
            FcUsbId(0x10C4, 0xEA60, "Silicon Labs CP210x"),
            FcUsbId(0x0403, 0x6001, "FTDI"),
            FcUsbId(0x1A86, 0x7523, "CH340"),
        )

        val BAUD_RATES = listOf(9600, 19200, 38400, 57600, 115200, 230400, 460800, 921600)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var readJob: Job? = null
    private var serialPort: UsbSerialPort? = null

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _connectedDevice = MutableStateFlow<String?>(null)
    val connectedDevice: StateFlow<String?> = _connectedDevice.asStateFlow()

    private val _detectedDevices = MutableStateFlow<List<DetectedDevice>>(emptyList())
    val detectedDevices: StateFlow<List<DetectedDevice>> = _detectedDevices.asStateFlow()

    private val _baudRate = MutableStateFlow(DEFAULT_BAUD_RATE)
    val baudRate: StateFlow<Int> = _baudRate.asStateFlow()

    fun setBaudRate(rate: Int) {
        _baudRate.value = rate
    }

    /**
     * Scan for USB devices that match known FC VID:PIDs.
     */
    fun scanDevices(): List<DetectedDevice> {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            ?: return emptyList()

        val probeTable = ProbeTable().apply {
            // Register known FC VIDs
            addProduct(0x0483, 0x5740, CdcAcmSerialDriver::class.java)
            addProduct(0x26AC, 0x0011, CdcAcmSerialDriver::class.java)
            addProduct(0x2DAE, 0x1011, CdcAcmSerialDriver::class.java)
            addProduct(0x2DAE, 0x1012, CdcAcmSerialDriver::class.java)
            addProduct(0x1209, 0x5740, CdcAcmSerialDriver::class.java)
            addProduct(0x10C4, 0xEA60, Cp21xxSerialDriver::class.java)
            addProduct(0x0403, 0x6001, FtdiSerialDriver::class.java)
            addProduct(0x1A86, 0x7523, Ch34xSerialDriver::class.java)
        }

        val prober = UsbSerialProber(probeTable)
        val drivers = prober.findAllDrivers(usbManager)

        val devices = drivers.map { driver ->
            val device = driver.device
            val fcId = FC_VID_PIDS.find {
                it.vendorId == device.vendorId && it.productId == device.productId
            }
            DetectedDevice(
                usbDevice = device,
                name = fcId?.description ?: device.productName ?: "Unknown USB Device",
                vendorId = device.vendorId,
                productId = device.productId,
                isFc = fcId != null,
            )
        }

        _detectedDevices.value = devices
        Log.d(TAG, "Scanned ${devices.size} USB serial device(s)")
        return devices
    }

    /**
     * Check if a known FC is currently plugged in without doing a full scan.
     */
    fun hasConnectedFc(): Boolean {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            ?: return false
        return usbManager.deviceList.values.any { device ->
            FC_VID_PIDS.any { fc ->
                fc.vendorId == device.vendorId && fc.productId == device.productId
            }
        }
    }

    /**
     * Connect to a USB serial device and start the MAVLink read loop.
     */
    fun connect(device: DetectedDevice, baudRate: Int = _baudRate.value) {
        disconnect()

        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: run {
            Log.e(TAG, "UsbManager not available")
            return
        }

        val probeTable = ProbeTable().apply {
            addProduct(0x0483, 0x5740, CdcAcmSerialDriver::class.java)
            addProduct(0x26AC, 0x0011, CdcAcmSerialDriver::class.java)
            addProduct(0x2DAE, 0x1011, CdcAcmSerialDriver::class.java)
            addProduct(0x2DAE, 0x1012, CdcAcmSerialDriver::class.java)
            addProduct(0x1209, 0x5740, CdcAcmSerialDriver::class.java)
            addProduct(0x10C4, 0xEA60, Cp21xxSerialDriver::class.java)
            addProduct(0x0403, 0x6001, FtdiSerialDriver::class.java)
            addProduct(0x1A86, 0x7523, Ch34xSerialDriver::class.java)
        }

        val prober = UsbSerialProber(probeTable)
        val drivers = prober.findAllDrivers(usbManager)
        val driver = drivers.find { it.device.deviceId == device.usbDevice.deviceId }

        if (driver == null) {
            Log.e(TAG, "No driver found for device ${device.name}")
            return
        }

        val connection = usbManager.openDevice(driver.device)
        if (connection == null) {
            Log.e(TAG, "USB permission not granted for ${device.name}")
            telemetryStore.updateConnection(
                ConnectionState(ConnectionStatus.LOST, "USB permission denied")
            )
            return
        }

        try {
            val port = driver.ports.firstOrNull() ?: run {
                Log.e(TAG, "No serial ports on device")
                return
            }
            port.open(connection)
            port.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)

            serialPort = port
            _isConnected.value = true
            _connectedDevice.value = device.name
            _baudRate.value = baudRate

            telemetryStore.updateConnection(
                ConnectionState(ConnectionStatus.CONNECTED, "USB: ${device.name} @ $baudRate")
            )

            Log.i(TAG, "Connected to ${device.name} at $baudRate baud")

            // Start read loop
            readJob = scope.launch {
                readLoop(port)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open serial port: ${e.message}", e)
            telemetryStore.updateConnection(
                ConnectionState(ConnectionStatus.LOST, "Serial error: ${e.message}")
            )
            disconnect()
        }
    }

    fun disconnect() {
        readJob?.cancel()
        readJob = null

        try {
            serialPort?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing serial port: ${e.message}")
        }
        serialPort = null

        if (_isConnected.value) {
            _isConnected.value = false
            _connectedDevice.value = null
            telemetryStore.updateConnection(
                ConnectionState(ConnectionStatus.DISCONNECTED, "USB disconnected")
            )
            Log.i(TAG, "Disconnected from USB serial")
        }
    }

    /**
     * Send raw bytes out through the USB serial port. Used by MavLinkCommandSender
     * when in USB serial mode.
     */
    fun sendBytes(data: ByteArray) {
        val port = serialPort ?: return
        try {
            port.write(data, READ_TIMEOUT_MS)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write to serial: ${e.message}")
        }
    }

    private suspend fun readLoop(port: UsbSerialPort) {
        val buffer = ByteArray(READ_BUFFER_SIZE)
        val sendStream = ByteArrayOutputStream()

        try {
            while (scope.isActive) {
                val bytesRead = try {
                    port.read(buffer, READ_TIMEOUT_MS)
                } catch (e: Exception) {
                    Log.w(TAG, "Serial read error: ${e.message}")
                    break
                }

                if (bytesRead > 0) {
                    val frame = buffer.copyOf(bytesRead)
                    processSerialFrame(frame, sendStream)
                }
            }
        } catch (e: CancellationException) {
            // Normal cancellation
        } catch (e: Exception) {
            Log.e(TAG, "Read loop error: ${e.message}", e)
        }

        // If we exit the read loop unexpectedly, mark as disconnected
        if (_isConnected.value) {
            _isConnected.value = false
            _connectedDevice.value = null
            telemetryStore.updateConnection(
                ConnectionState(ConnectionStatus.LOST, "USB serial disconnected")
            )
        }
    }

    private fun processSerialFrame(bytes: ByteArray, sendStream: ByteArrayOutputStream) {
        try {
            val inputStream = ByteArrayInputStream(bytes)
            val connection = MavlinkConnection.create(inputStream, sendStream)

            var message = connection.next()
            while (message != null) {
                parser.handleMessage(message)
                message = try {
                    connection.next()
                } catch (_: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            // Partial frames are normal with serial; will be completed on next read
        }
    }
}

data class DetectedDevice(
    val usbDevice: UsbDevice,
    val name: String,
    val vendorId: Int,
    val productId: Int,
    val isFc: Boolean,
)
