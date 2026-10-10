// SPDX-License-Identifier: GPL-2.0-or-later
package com.altnautica.gcs.radio.usb

/** USB identities of the radios the phone-radio receiver drives (mirrors `res/xml/radio_device_filter.xml`). */
object RadioDongles {
    const val REALTEK_VENDOR_ID = 0x0BDA

    /** RTL8812EU first (the production part), then the RTL8812AU ids. */
    val SUPPORTED_PRODUCT_IDS: Set<Int> = linkedSetOf(0xA81A, 0x8812, 0x881A, 0x881B)

    /** RTL8812BU ids: a different chipset that is sold under the same name and cannot be driven. */
    val UNSUPPORTED_LOOKALIKE_IDS: Set<Int> = setOf(0xB812, 0xB832)

    fun isSupported(vendorId: Int, productId: Int): Boolean =
        vendorId == REALTEK_VENDOR_ID && productId in SUPPORTED_PRODUCT_IDS

    fun isUnsupportedLookalike(vendorId: Int, productId: Int): Boolean =
        vendorId == REALTEK_VENDOR_ID && productId in UNSUPPORTED_LOOKALIKE_IDS
}
