package com.altnautica.gcs.core.link.demo

import com.altnautica.gcs.core.link.demo.ArduCopterSimulator.Companion.PARAM_STREAM_GAP_MS
import com.altnautica.gcs.core.mavlink.enums.MavParamType
import com.altnautica.gcs.core.mavlink.messages.ParamRequestRead
import com.altnautica.gcs.core.mavlink.messages.ParamSet
import com.altnautica.gcs.core.mavlink.messages.ParamValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal data class SimParam(val name: String, val value: Float, val type: Int)

/** The vehicle side of the parameter protocol over an ArduCopter-like default set. */
internal class SimParams(private val sim: SimState) {
    private val params: MutableList<SimParam> = defaultParams().toMutableList()
    private var streamedOnce = false

    /** Streams the whole list; the first stream skips [DemoOptions.droppedParamIndices] to exercise gap repair. */
    fun streamAll() {
        sim.scope.launch {
            val dropped = sim.options.droppedParamIndices
            for (i in params.indices) {
                if (i in dropped && !streamedOnce) continue
                sim.send(valueAt(i))
                delay(PARAM_STREAM_GAP_MS)
            }
            streamedOnce = true
        }
    }

    fun onRead(msg: ParamRequestRead) {
        val index = if (msg.paramIndex >= 0) msg.paramIndex else params.indexOfFirst { it.name == msg.paramId }
        if (index in params.indices) sim.send(valueAt(index))
    }

    fun onSet(msg: ParamSet) {
        val index = params.indexOfFirst { it.name == msg.paramId }
        if (index >= 0) {
            params[index] = params[index].copy(value = msg.paramValue)
            sim.send(valueAt(index))
        }
    }

    fun value(name: String): Float = params.firstOrNull { it.name == name }?.value ?: 0f

    private fun valueAt(index: Int): ParamValue {
        val p = params[index]
        return ParamValue(paramId = p.name, paramValue = p.value, paramType = p.type, paramCount = params.size, paramIndex = index)
    }

    companion object {
        fun defaultParams(): List<SimParam> {
            val i8 = MavParamType.MAV_PARAM_TYPE_INT8
            val i16 = MavParamType.MAV_PARAM_TYPE_INT16
            val i32 = MavParamType.MAV_PARAM_TYPE_INT32
            val f = MavParamType.MAV_PARAM_TYPE_REAL32
            return listOf(
                SimParam("SYSID_THISMAV", 1f, i16),
                SimParam("SYSID_MYGCS", 255f, i16),
                SimParam("FRAME_CLASS", 1f, i8),
                SimParam("FRAME_TYPE", 1f, i8),
                SimParam("ARMING_CHECK", 1f, i32),
                SimParam("ANGLE_MAX", 3000f, i16),
                SimParam("BATT_MONITOR", 4f, i8),
                SimParam("BATT_CAPACITY", 5200f, i32),
                SimParam("BATT_LOW_VOLT", 14.0f, f),
                SimParam("BATT_CRT_VOLT", 13.2f, f),
                SimParam("BATT_FS_LOW_ACT", 2f, i8),
                SimParam("BATT_FS_CRT_ACT", 1f, i8),
                SimParam("FS_THR_ENABLE", 1f, i8),
                SimParam("FS_THR_VALUE", 975f, i16),
                SimParam("FS_GCS_ENABLE", 1f, i8),
                SimParam("FS_EKF_ACTION", 1f, i8),
                SimParam("FS_EKF_THRESH", 0.8f, f),
                SimParam("FENCE_ENABLE", 0f, i8),
                SimParam("FENCE_TYPE", 7f, i8),
                SimParam("FENCE_ACTION", 1f, i8),
                SimParam("FENCE_ALT_MAX", 100f, f),
                SimParam("FENCE_RADIUS", 300f, f),
                SimParam("RTL_ALT", 1500f, i32),
                SimParam("RTL_LOIT_TIME", 5000f, i32),
                SimParam("LAND_SPEED", 50f, i16),
                SimParam("PILOT_SPEED_UP", 250f, i16),
                SimParam("WPNAV_SPEED", 500f, f),
                SimParam("WPNAV_SPEED_UP", 250f, f),
                SimParam("WPNAV_SPEED_DN", 150f, f),
                SimParam("WPNAV_RADIUS", 200f, f),
                SimParam("WPNAV_ACCEL", 100f, f),
                SimParam("FLTMODE_CH", 5f, i8),
                SimParam("FLTMODE1", 0f, i8),
                SimParam("FLTMODE2", 2f, i8),
                SimParam("FLTMODE3", 5f, i8),
                SimParam("FLTMODE4", 6f, i8),
                SimParam("FLTMODE5", 9f, i8),
                SimParam("FLTMODE6", 3f, i8),
                SimParam("RCMAP_ROLL", 1f, i8),
                SimParam("RCMAP_PITCH", 2f, i8),
                SimParam("RCMAP_THROTTLE", 3f, i8),
                SimParam("RCMAP_YAW", 4f, i8),
                SimParam("RC1_MIN", 1100f, i16),
                SimParam("RC1_MAX", 1900f, i16),
                SimParam("RC1_TRIM", 1500f, i16),
                SimParam("RC2_MIN", 1100f, i16),
                SimParam("RC2_MAX", 1900f, i16),
                SimParam("RC2_TRIM", 1500f, i16),
                SimParam("RC3_MIN", 1100f, i16),
                SimParam("RC3_MAX", 1900f, i16),
                SimParam("RC3_TRIM", 1100f, i16),
                SimParam("RC4_MIN", 1100f, i16),
                SimParam("RC4_MAX", 1900f, i16),
                SimParam("RC4_TRIM", 1500f, i16),
                SimParam("ATC_RAT_RLL_P", 0.135f, f),
                SimParam("ATC_RAT_RLL_I", 0.135f, f),
                SimParam("ATC_RAT_RLL_D", 0.0036f, f),
                SimParam("ATC_RAT_PIT_P", 0.135f, f),
                SimParam("ATC_RAT_PIT_I", 0.135f, f),
                SimParam("ATC_RAT_PIT_D", 0.0036f, f),
                SimParam("ATC_RAT_YAW_P", 0.18f, f),
                SimParam("ATC_RAT_YAW_I", 0.018f, f),
                SimParam("COMPASS_USE", 1f, i8),
                SimParam("COMPASS_OFS_X", 12f, i16),
                SimParam("COMPASS_OFS_Y", -7f, i16),
                SimParam("COMPASS_OFS_Z", 30f, i16),
                SimParam("INS_ACCOFFS_X", 0.01f, f),
                SimParam("INS_ACCOFFS_Y", -0.02f, f),
                SimParam("INS_ACCOFFS_Z", 0.05f, f),
                SimParam("MOT_SPIN_ARM", 0.1f, f),
                SimParam("MOT_SPIN_MIN", 0.15f, f),
                SimParam("MOT_BAT_VOLT_MAX", 16.8f, f),
                SimParam("MOT_BAT_VOLT_MIN", 13.2f, f),
                SimParam("LOG_BITMASK", 176126f, i32),
                SimParam("GPS_TYPE", 1f, i8),
                SimParam("EK3_ENABLE", 1f, i8),
                SimParam("AHRS_EKF_TYPE", 3f, i8),
                SimParam("SERIAL0_BAUD", 115f, i32),
            )
        }
    }
}
