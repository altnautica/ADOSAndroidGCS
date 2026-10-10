// SPDX-License-Identifier: GPL-2.0-or-later
package com.altnautica.gcs.radio.usb

/**
 * The native USB radio driver. Loaded only inside the radio service process;
 * every call except [load] needs a handle from [nativeOpen].
 */
internal object NativeUsbRadio {

    @Volatile private var loaded = false

    fun load() {
        if (loaded) return
        synchronized(this) {
            if (!loaded) {
                System.loadLibrary("ados_usbradio")
                loaded = true
            }
        }
    }

    /** Opens and claims the radio behind [fd]; throws IllegalStateException with a readable reason. */
    @JvmStatic external fun nativeOpen(fd: Int, lockDir: String): Long

    /**
     * Brings the chip up on [channel] (blocking, about two seconds) and starts
     * writing frame records to [outFd], which the native side then owns.
     */
    @JvmStatic external fun nativeStart(handle: Long, channel: Int, bandwidthMhz: Int, outFd: Int): Boolean

    @JvmStatic external fun nativeSetChannel(handle: Long, channel: Int, bandwidthMhz: Int): Boolean

    /** [running, framesSeen, framesForwarded, framesDropped, framesCorrupt, channel] */
    @JvmStatic external fun nativeStats(handle: Long): LongArray

    @JvmStatic external fun nativeClose(handle: Long)
}
