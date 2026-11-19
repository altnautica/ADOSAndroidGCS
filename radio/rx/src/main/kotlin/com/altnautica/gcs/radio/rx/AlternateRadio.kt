package com.altnautica.gcs.radio.rx

import com.altnautica.gcs.radio.usb.IUsbRadio

/**
 * A radio the phone-radio session runs on in place of the USB dongle. Only a
 * debug build provides one (the fleet bench's relay); a release build binds
 * none, so its session always uses the dongle.
 */
interface AlternateRadio {
    /** Whether it is configured now; read at every session start. */
    val available: Boolean

    /** Whether the phone holds the uplink while it runs on this radio, and so transmits. */
    val holdsUplink: Boolean

    /**
     * A radio ready for [IUsbRadio.open] carrying exactly these channel ids
     * (`link_id << 8 | port`): a run's planes, or the bind session's.
     */
    fun openChannels(rxChannels: List<Long>, txChannels: List<Long>): IUsbRadio
}
