package com.altnautica.gcs.feature.cockpit.chrome

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/** `layout.json` `orientation_lock` key for this screen. */
const val FLIGHT_SCREEN_LOCK_KEY = "android_flight_screen"

/** The activity orientation for a spec lock value; null leaves the orientation free. */
fun orientationFor(lock: String?): Int? = when (lock) {
    "landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    "portrait" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    else -> null
}

/**
 * Holds the activity at the spec's flight-screen orientation while composed (either
 * landscape side, following the sensor) and restores the previous request on leave, so
 * Home, Settings and Planner keep rotating.
 */
@Composable
fun OrientationLock(lock: String?) {
    val activity = LocalContext.current.findActivity() ?: return
    val requested = orientationFor(lock) ?: return
    DisposableEffect(activity, requested) {
        val previous = activity.requestedOrientation
        activity.requestedOrientation = requested
        onDispose { activity.requestedOrientation = previous }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
