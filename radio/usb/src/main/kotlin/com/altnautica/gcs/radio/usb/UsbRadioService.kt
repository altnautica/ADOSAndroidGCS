// SPDX-License-Identifier: GPL-2.0-or-later
package com.altnautica.gcs.radio.usb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Hosts the USB radio driver in the `:radio` process.
 *
 * The client starts it with [Context.startForegroundService] (so it may keep
 * running while the screen is off), then binds to [IUsbRadio]. The binder is
 * the whole interface: open the granted descriptor, pick a channel, read the
 * frame pipe, close. Closing stops the service.
 */
class UsbRadioService : Service() {

    private val lock = Any()
    private var handle = 0L
    private var channel = 0
    private var bandwidthMhz = DEFAULT_BANDWIDTH_MHZ
    private var started = false

    override fun onCreate() {
        super.onCreate()
        NativeUsbRadio.load()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promote()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onUnbind(intent: Intent?): Boolean {
        shutdown()
        return false
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private val binder = object : IUsbRadio.Stub() {

        override fun open(usbFd: ParcelFileDescriptor, vendorId: Int, productId: Int) {
            require(RadioDongles.isSupported(vendorId, productId)) {
                "Unsupported radio %04x:%04x".format(vendorId, productId)
            }
            synchronized(lock) {
                closeLocked()
                // The native driver owns the descriptor from here.
                handle = NativeUsbRadio.nativeOpen(usbFd.detachFd(), cacheDir.absolutePath)
            }
            Log.i(TAG, "radio %04x:%04x opened".format(vendorId, productId))
        }

        override fun setChannel(channel: Int, bandwidthMhz: Int) {
            require(channel in 1..196) { "Channel $channel out of range" }
            synchronized(lock) {
                this@UsbRadioService.channel = channel
                this@UsbRadioService.bandwidthMhz = bandwidthMhz
                if (started) {
                    check(NativeUsbRadio.nativeSetChannel(handle, channel, bandwidthMhz)) { "Retune to channel $channel failed" }
                }
            }
        }

        override fun frames(): ParcelFileDescriptor = synchronized(lock) {
            check(handle != 0L) { "Radio not open" }
            check(channel != 0) { "Channel not set" }
            check(!started) { "Frames already streaming" }
            val (read, write) = ParcelFileDescriptor.createPipe()
            try {
                check(NativeUsbRadio.nativeStart(handle, channel, bandwidthMhz, write.detachFd())) { "Radio did not start" }
            } catch (e: RuntimeException) {
                read.close()
                throw e
            }
            started = true
            read
        }

        override fun close() {
            shutdown()
        }

        override fun stats(): LongArray = synchronized(lock) {
            if (handle == 0L) LongArray(STAT_FIELDS) else NativeUsbRadio.nativeStats(handle)
        }
    }

    private fun shutdown() {
        synchronized(lock) { closeLocked() }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun closeLocked() {
        if (handle != 0L) {
            NativeUsbRadio.nativeClose(handle)
            handle = 0L
        }
        started = false
    }

    private fun promote() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL,
            getString(R.string.radio_service_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = getString(R.string.radio_service_channel_description) }
        manager.createNotificationChannel(channel)
        val notification: Notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_radio_notification)
            .setContentTitle(getString(R.string.radio_service_title))
            .setContentText(getString(R.string.radio_service_text))
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    }

    companion object {
        private const val TAG = "AdosUsbRadio"
        private const val NOTIFICATION_CHANNEL = "phone_radio"
        private const val NOTIFICATION_ID = 0x5241
        private const val DEFAULT_BANDWIDTH_MHZ = 20
        const val STAT_FIELDS = 6

        fun intent(context: Context): Intent = Intent(context, UsbRadioService::class.java)
    }
}
