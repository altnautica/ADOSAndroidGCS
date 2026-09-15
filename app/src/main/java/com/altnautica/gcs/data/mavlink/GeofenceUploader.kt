package com.altnautica.gcs.data.mavlink

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/** One geofence vertex, in degrees. */
data class FenceVertex(val latitude: Double, val longitude: Double)

/**
 * Uploads a geofence to the flight controller.
 *
 * Lives in the data layer because it is MAVLink command encoding: it used to be
 * a top-level function in the geofence composable's file that took the command
 * sender and logged from the UI layer, which put vehicle-command construction
 * inside the composition's reach.
 */
@Singleton
class GeofenceUploader @Inject constructor(
    private val commandSender: MavLinkCommandSender,
) {

    companion object {
        private const val TAG = "GeofenceUploader"

        /** MAV_PARAM_TYPE_REAL32. */
        private const val PARAM_TYPE_REAL32 = 9

        /** MAV_CMD_DO_FENCE_ENABLE. */
        private const val CMD_DO_FENCE_ENABLE = 207

        /** FENCE_ACTION = RTL on breach. */
        private const val FENCE_ACTION_RTL = 1f
    }

    /**
     * Upload [vertices] as the active fence, setting FENCE_ACTION to RTL.
     *
     * A non-null [radiusM] uploads a circular fence (FENCE_RADIUS, then
     * re-enable); otherwise the FENCE_POINT protocol runs for the polygon:
     * disable the fence, set FENCE_TOTAL, send each point, re-enable.
     */
    suspend fun upload(vertices: List<FenceVertex>, radiusM: Float?) {
        Log.i(TAG, "upload fence: ${vertices.size} vertices, radius=$radiusM")

        commandSender.sendParamSet("FENCE_ACTION", FENCE_ACTION_RTL, PARAM_TYPE_REAL32)

        if (radiusM != null) {
            commandSender.sendParamSet("FENCE_RADIUS", radiusM, PARAM_TYPE_REAL32)
            commandSender.sendCommandLongRaw(commandId = CMD_DO_FENCE_ENABLE, param1 = 1f)
            Log.i(TAG, "set FENCE_RADIUS=$radiusM")
            return
        }

        commandSender.sendFencePoints(vertices.map { it.latitude to it.longitude })
        Log.i(TAG, "uploaded ${vertices.size} fence points")
    }
}
