package com.altnautica.gcs.core.skills.input.controller

/** USB/Bluetooth vendor id of PlayStation controllers. */
const val PLAYSTATION_VENDOR_ID = 0x054C

/** Product ids of the DualShock 4 (first and second revision, wireless adapter). */
val DUALSHOCK4_PRODUCT_IDS: Set<Int> = setOf(0x05C4, 0x09CC, 0x0BA0)

/** Button-glyph family of a controller, decided by vendor id only. */
enum class ControllerFamily { PlayStation, Generic }

/** An attached game controller. [batteryPct] is null when the platform does not report it. */
data class ControllerInfo(
    val deviceId: Int,
    val name: String,
    val vendorId: Int,
    val productId: Int,
    val family: ControllerFamily,
    val batteryPct: Int?,
    val charging: Boolean,
)

fun ControllerInfo.isDualShock4(): Boolean = vendorId == PLAYSTATION_VENDOR_ID && productId in DUALSHOCK4_PRODUCT_IDS

/** Controller family from the vendor id; device names are never used. */
fun controllerFamily(vendorId: Int): ControllerFamily =
    if (vendorId == PLAYSTATION_VENDOR_ID) ControllerFamily.PlayStation else ControllerFamily.Generic
