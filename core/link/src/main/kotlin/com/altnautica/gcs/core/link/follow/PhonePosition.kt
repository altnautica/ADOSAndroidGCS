package com.altnautica.gcs.core.link.follow

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow

/** What the follow beacon reads from the phone: its position, the permission for it, and whether the app is in front. */
interface PhonePosition {
    /** True while precise location is granted. */
    fun hasPermission(): Boolean

    /**
     * Position fixes while collected. Fails with [SecurityException] when the permission is
     * missing or withdrawn. A fix without a horizontal accuracy is skipped.
     */
    fun fixes(): Flow<BeaconFix>

    /** Now, in ms since boot: the base fixes are stamped on. */
    fun nowMs(): Long

    /** True while an activity of the app is started. */
    val foreground: StateFlow<Boolean>
}

/**
 * [PhonePosition] from the platform location manager: the fused provider where the platform has
 * one (Android 12+), else GPS, asked for a fix every [FollowBeaconPolicy.PERIOD_MS].
 *
 * Foreground is counted from activity start and stop callbacks. The first reader is a visible
 * screen, so it starts as one started activity.
 */
@Singleton
class AndroidPhonePosition @Inject constructor(
    @ApplicationContext private val context: Context,
) : PhonePosition {

    private val _foreground = MutableStateFlow(true)
    override val foreground: StateFlow<Boolean> = _foreground.asStateFlow()

    init {
        (context.applicationContext as? Application)?.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                private var started = 1

                override fun onActivityStarted(activity: Activity) {
                    started += 1
                    _foreground.value = started > 0
                }

                override fun onActivityStopped(activity: Activity) {
                    started = (started - 1).coerceAtLeast(0)
                    _foreground.value = started > 0
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
    }

    override fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    override fun nowMs(): Long = SystemClock.elapsedRealtime()

    // The permission is checked first; a withdrawal after that surfaces as the SecurityException the contract names.
    @SuppressLint("MissingPermission")
    override fun fixes(): Flow<BeaconFix> = callbackFlow {
        if (!hasPermission()) throw SecurityException("precise location is not granted")
        val manager = context.getSystemService(LocationManager::class.java) ?: throw IllegalStateException("no location service")
        val provider = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && manager.hasProvider(LocationManager.FUSED_PROVIDER)) {
            LocationManager.FUSED_PROVIDER
        } else {
            LocationManager.GPS_PROVIDER
        }
        val listener = LocationListenerCompat { location -> location.toBeaconFix()?.let { trySend(it) } }
        val request = LocationRequestCompat.Builder(FollowBeaconPolicy.PERIOD_MS)
            .setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY)
            .build()
        LocationManagerCompat.requestLocationUpdates(manager, provider, request, listener, Looper.getMainLooper())
        awaitClose { LocationManagerCompat.removeUpdates(manager, listener) }
    }
}

/** The fix a platform [Location] carries, or null without a horizontal accuracy. */
internal fun Location.toBeaconFix(): BeaconFix? {
    if (!hasAccuracy()) return null
    val msl = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && hasMslAltitude()
    return BeaconFix.of(
        latDeg = latitude,
        lonDeg = longitude,
        mslAltM = if (msl) mslAltitudeMeters else null,
        horizAccM = accuracy,
        vertAccM = if (msl && hasMslAltitudeAccuracy()) mslAltitudeAccuracyMeters else null,
        speedMps = if (hasSpeed()) speed else null,
        bearingDeg = if (hasBearing()) bearing else null,
        fixElapsedMs = elapsedRealtimeNanos / 1_000_000L,
    )
}

@Module
@InstallIn(SingletonComponent::class)
abstract class PhonePositionModule {
    @Binds
    abstract fun phonePosition(impl: AndroidPhonePosition): PhonePosition
}
