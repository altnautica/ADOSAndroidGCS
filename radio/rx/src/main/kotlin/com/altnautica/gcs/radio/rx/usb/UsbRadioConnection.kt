package com.altnautica.gcs.radio.rx.usb

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.altnautica.gcs.radio.usb.IUsbRadio
import com.altnautica.gcs.radio.usb.UsbRadioService
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * A binding to the USB radio service in its own process. [onLost] fires
 * when that process goes away while bound (a driver crash or the system
 * reclaiming it).
 */
class UsbRadioConnection(private val context: Context, private val onLost: () -> Unit) {

    private var connection: ServiceConnection? = null

    suspend fun connect(): IUsbRadio = suspendCancellableCoroutine { cont ->
        val intent = UsbRadioService.intent(context)
        ContextCompat.startForegroundService(context, intent)
        val conn = object : ServiceConnection {
            private var delivered = false

            override fun onServiceConnected(name: ComponentName?, service: IBinder) {
                if (!delivered) {
                    delivered = true
                    cont.resume(IUsbRadio.Stub.asInterface(service))
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                onLost()
            }

            override fun onBindingDied(name: ComponentName?) {
                onLost()
            }
        }
        connection = conn
        if (!context.bindService(intent, conn, Context.BIND_AUTO_CREATE)) {
            connection = null
            cont.resumeWith(Result.failure(IllegalStateException("Radio service unavailable")))
            return@suspendCancellableCoroutine
        }
        cont.invokeOnCancellation { disconnect() }
    }

    fun disconnect() {
        val conn = connection ?: return
        connection = null
        runCatching { context.unbindService(conn) }
    }
}
