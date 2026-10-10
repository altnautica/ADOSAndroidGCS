package com.altnautica.gcs.feature.configure.setup

import com.altnautica.gcs.core.model.FcVariant

/** Parameters behind the flight-mode switch page for one firmware family. */
data class FlightModeParams(val slots: List<String>, val channelParam: String)

/**
 * Curated parameter sets for the Setup pages. Pages show only names the connected flight
 * controller actually reports, so one list can cover several firmware versions.
 */
object SetupCatalog {
    private val PLANE_TYPES = setOf(1, 16, 19, 20, 21, 22, 23, 24, 25)
    private val ROVER_TYPES = setOf(10, 11)

    fun flightModes(variant: FcVariant, mavType: Int?): FlightModeParams? = when (variant) {
        FcVariant.MavlinkPx4 -> FlightModeParams((1..6).map { "COM_FLTMODE$it" }, "RC_MAP_FLTMODE")
        FcVariant.MavlinkArdupilot -> if (mavType in ROVER_TYPES) {
            FlightModeParams((1..6).map { "MODE$it" }, "MODE_CH")
        } else {
            FlightModeParams((1..6).map { "FLTMODE$it" }, "FLTMODE_CH")
        }
        else -> null
    }

    private val BATTERY = listOf(
        "BATT_FS_LOW_ACT", "BATT_FS_CRT_ACT", "BATT_LOW_VOLT", "BATT_CRT_VOLT", "BATT_LOW_MAH", "BATT_CRT_MAH",
        "BATT_FS_VOLTSRC", "BATT_LOW_TIMER",
    )

    fun failsafe(variant: FcVariant, mavType: Int?): List<String> = when (variant) {
        FcVariant.MavlinkPx4 -> listOf(
            "NAV_RCL_ACT", "COM_RC_LOSS_T", "NAV_DLL_ACT", "COM_DL_LOSS_T", "COM_LOW_BAT_ACT",
            "BAT_LOW_THR", "BAT_CRIT_THR", "BAT_EMERGEN_THR", "COM_POSCTL_NAVL", "GF_ACTION",
        )
        FcVariant.MavlinkArdupilot -> when (mavType) {
            in PLANE_TYPES -> listOf(
                "THR_FAILSAFE", "THR_FS_VALUE", "FS_SHORT_ACTN", "FS_SHORT_TIMEOUT", "FS_LONG_ACTN",
                "FS_LONG_TIMEOUT", "FS_GCS_ENABL",
            ) + BATTERY
            in ROVER_TYPES -> listOf(
                "FS_ACTION", "FS_TIMEOUT", "FS_THR_ENABLE", "FS_THR_VALUE", "FS_GCS_ENABLE", "FS_EKF_ACTION",
                "FS_EKF_THRESH", "FS_OPTIONS",
            ) + BATTERY
            else -> listOf(
                "FS_THR_ENABLE", "FS_THR_VALUE", "FS_GCS_ENABLE", "FS_GCS_TIMEOUT", "FS_EKF_ACTION",
                "FS_EKF_THRESH", "FS_OPTIONS", "FS_CRASH_CHECK",
            ) + BATTERY
        }
        else -> emptyList()
    }

    /** Frame parameters shown on the Frame page. */
    fun frame(variant: FcVariant): List<String> = when (variant) {
        FcVariant.MavlinkArdupilot -> listOf("FRAME_CLASS", "FRAME_TYPE")
        FcVariant.MavlinkPx4 -> listOf("SYS_AUTOSTART", "CA_ROTOR_COUNT")
        else -> emptyList()
    }

    /** Motor count implied by the frame parameters, or null when they do not say. */
    fun motorCount(params: Map<String, Double>): Int? {
        params["CA_ROTOR_COUNT"]?.toInt()?.takeIf { it > 0 }?.let { return it }
        return when (params["FRAME_CLASS"]?.toInt()) {
            1 -> 4 // quad
            2 -> 6 // hexa
            3 -> 8 // octa
            4 -> 8 // octa-quad
            5 -> 6 // Y6
            7 -> 3 // tri
            12 -> 12 // dodeca-hexa
            14 -> 10 // deca
            else -> null
        }
    }
}
