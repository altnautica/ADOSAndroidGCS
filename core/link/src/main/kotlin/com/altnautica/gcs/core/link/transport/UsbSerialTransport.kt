package com.altnautica.gcs.core.link.transport

import android.hardware.usb.UsbManager
import com.altnautica.gcs.core.model.TransportState
import com.altnautica.gcs.core.model.WriteResult
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * USB CDC/FTDI/CP210x/CH34x flight controller. Reads run on a dedicated
 * thread; an unplug surfaces as an I/O error and closes the transport.
 */
class UsbSerialTransport(
    context: CoroutineContext,
    private val usbManager: UsbManager,
    private val driver: UsbSerialDriver,
    private val baudRate: Int,
) : ChannelTransport(context) {

    private var port: UsbSerialPort? = null
    private var reader: Thread? = null
    private val writeLock = Mutex()

    fun open() {
        mutableState.value = TransportState.Connecting
        val device = driver.device
        if (!usbManager.hasPermission(device)) {
            finish(TransportState.Failed("USB permission not granted"))
            return
        }
        val connection = usbManager.openDevice(device)
        if (connection == null) {
            finish(TransportState.Failed("Cannot open USB device"))
            return
        }
        val p = driver.ports.firstOrNull()
        if (p == null) {
            connection.close()
            finish(TransportState.Failed("USB device has no serial port"))
            return
        }
        try {
            p.open(connection)
            p.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            runCatching { p.dtr = true }
            runCatching { p.rts = true }
        } catch (e: IOException) {
            runCatching { p.close() }
            finish(TransportState.Failed(e.message ?: "USB open failed"))
            return
        }
        port = p
        mutableState.value = TransportState.Open
        reader = Thread({ readLoop(p) }, "usb-serial-read").apply {
            isDaemon = true
            start()
        }
    }

    private fun readLoop(p: UsbSerialPort) {
        val buf = ByteArray(p.readEndpoint?.maxPacketSize?.coerceAtLeast(64)?.times(16) ?: 4096)
        while (!Thread.currentThread().isInterrupted) {
            val n = try {
                p.read(buf, READ_TIMEOUT_MS)
            } catch (e: IOException) {
                finish(TransportState.Closed(e.message ?: "USB device detached"))
                return
            }
            if (n > 0) deliver(buf.copyOf(n))
        }
    }

    override suspend fun write(bytes: ByteArray): WriteResult {
        val p = port ?: return WriteResult.Failed("closed")
        return withContext(Dispatchers.IO) {
            writeLock.withLock {
                try {
                    p.write(bytes, WRITE_TIMEOUT_MS)
                    WriteResult.Sent
                } catch (e: IOException) {
                    finish(TransportState.Closed(e.message ?: "USB device detached"))
                    WriteResult.Failed(e.message ?: "USB write failed")
                }
            }
        }
    }

    override fun release() {
        reader?.interrupt()
        reader = null
        runCatching { port?.close() }
        port = null
    }

    companion object {
        const val READ_TIMEOUT_MS = 200
        const val WRITE_TIMEOUT_MS = 500
        const val DEFAULT_BAUD = 57_600
        val BAUD_RATES = listOf(57_600, 115_200, 921_600)
    }
}
