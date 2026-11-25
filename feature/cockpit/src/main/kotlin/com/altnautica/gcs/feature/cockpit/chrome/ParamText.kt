package com.altnautica.gcs.feature.cockpit.chrome

import androidx.annotation.StringRes
import com.altnautica.gcs.core.model.ParamDoc
import com.altnautica.gcs.feature.cockpit.R
import kotlin.math.abs
import kotlin.math.floor

/** How the alert list writes a flight-controller parameter: its value, its value's label and why ADOS sets it. */
internal object ParamText {
    /** A parameter value as the FC holds it: whole numbers without a decimal point. */
    fun number(v: Double): String =
        if (v == floor(v) && !v.isInfinite() && abs(v) < 1e15) v.toLong().toString() else v.toString()

    /** [number] with the parameter's units, e.g. `5 s`. */
    fun value(v: Double, doc: ParamDoc?): String {
        val units = doc?.units
        return if (units.isNullOrBlank()) number(v) else "${number(v)} $units"
    }

    /** What [v] means: the enumerated option's label, else the set bits' labels; null when the doc names neither. */
    fun label(v: Double?, doc: ParamDoc?): String? {
        if (v == null || doc == null) return null
        doc.options[v]?.let { return it }
        if (doc.bits.isEmpty() || v < 0 || v != floor(v) || v >= MAX_BITMASK) return null
        val mask = v.toLong()
        return doc.bits.entries
            .filter { (bit, _) -> bit in 0..62 && (mask shr bit) and 1L == 1L }
            .sortedBy { it.key }
            .joinToString(", ") { it.value }
            .ifEmpty { null }
    }

    /** A wanted value that is a single number; ranges and conditions ("0.15 to 0.2") stay text. */
    fun wantValue(want: String): Double? = want.trim().toDoubleOrNull()

    /** Why ADOS wants this parameter's value; null for a parameter ADOS has no reason line for. */
    @StringRes
    fun why(name: String): Int? = when (name) {
        "MAV_GCS_SYSID", "SYSID_MYGCS" -> R.string.param_why_MAV_GCS_SYSID
        "FS_GCS_ENABLE" -> R.string.param_why_FS_GCS_ENABLE
        "FS_GCS_TIMEOUT" -> R.string.param_why_FS_GCS_TIMEOUT
        "FS_THR_ENABLE" -> R.string.param_why_FS_THR_ENABLE
        "GUID_TIMEOUT" -> R.string.param_why_GUID_TIMEOUT
        "RC_OVERRIDE_TIME" -> R.string.param_why_RC_OVERRIDE_TIME
        "BATT_FS_LOW_ACT" -> R.string.param_why_BATT_FS_LOW_ACT
        "BATT_FS_CRT_ACT" -> R.string.param_why_BATT_FS_CRT_ACT
        "OA_TYPE" -> R.string.param_why_OA_TYPE
        "AVOID_ENABLE" -> R.string.param_why_AVOID_ENABLE
        "AVOID_MARGIN" -> R.string.param_why_AVOID_MARGIN
        "AVOID_BEHAVE" -> R.string.param_why_AVOID_BEHAVE
        "PRX1_TYPE", "PRX_TYPE" -> R.string.param_why_PRX1_TYPE
        "SRTL_POINTS" -> R.string.param_why_SRTL_POINTS
        "SRTL_ACCURACY" -> R.string.param_why_SRTL_ACCURACY
        "ATC_INPUT_TC" -> R.string.param_why_ATC_INPUT_TC
        "GUID_OPTIONS" -> R.string.param_why_GUID_OPTIONS
        "GCS_PID_MASK" -> R.string.param_why_GCS_PID_MASK
        "FS_GCS_ENABL" -> R.string.param_why_FS_GCS_ENABL
        "FS_LONG_TIMEOUT" -> R.string.param_why_FS_LONG_TIMEOUT
        "FS_LONG_ACTN" -> R.string.param_why_FS_LONG_ACTN
        "Q_GUIDED_MODE" -> R.string.param_why_Q_GUIDED_MODE
        "COM_OF_LOSS_T" -> R.string.param_why_COM_OF_LOSS_T
        "COM_OBL_RC_ACT" -> R.string.param_why_COM_OBL_RC_ACT
        "NAV_DLL_ACT" -> R.string.param_why_NAV_DLL_ACT
        "COM_DL_LOSS_T" -> R.string.param_why_COM_DL_LOSS_T
        else -> null
    }

    /** Bit 63 and above cannot be tested on a Long. */
    private const val MAX_BITMASK = 9.223372036854776E18
}
