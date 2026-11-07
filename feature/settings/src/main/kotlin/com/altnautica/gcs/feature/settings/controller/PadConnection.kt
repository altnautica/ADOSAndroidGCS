package com.altnautica.gcs.feature.settings.controller

import android.content.Context
import android.hardware.usb.UsbManager
import com.altnautica.gcs.core.skills.input.controller.ControllerInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** How a controller reaches the device. */
enum class PadConnection { Usb, Bluetooth, Unknown }

/**
 * Tells USB from Bluetooth pads: a controller whose vendor and product id appear among
 * the attached USB devices is on USB; any other physical pad is on Bluetooth.
 */
class PadConnectionProbe internal constructor(private val usbIds: () -> Set<Pair<Int, Int>>) {
    @Inject
    constructor(@ApplicationContext context: Context) : this({
        context.getSystemService(UsbManager::class.java)?.deviceList?.values
            ?.mapTo(HashSet()) { it.vendorId to it.productId }
            .orEmpty()
    })

    fun connection(controller: ControllerInfo): PadConnection =
        if (controller.vendorId to controller.productId in usbIds()) PadConnection.Usb else PadConnection.Bluetooth
}
