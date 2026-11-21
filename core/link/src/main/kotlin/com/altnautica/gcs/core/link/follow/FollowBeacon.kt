package com.altnautica.gcs.core.link.follow

import com.altnautica.gcs.core.mavlink.messages.FollowTarget

/**
 * One phone position fix, as the follow beacon sends it.
 *
 * [fixElapsedMs] is when the fix was taken, in ms since boot (the elapsed-realtime base the
 * location stack stamps fixes on and `FOLLOW_TARGET.timestamp` carries). [mslAltM] is null when
 * the location stack gave no altitude above mean sea level; [velNorthMps] and [velEastMps] are
 * null when it measured no ground velocity; [vertAccM] is null when the vertical accuracy is unknown.
 */
data class BeaconFix(
    val latDeg: Double,
    val lonDeg: Double,
    val mslAltM: Double?,
    val horizAccM: Float,
    val vertAccM: Float?,
    val velNorthMps: Float?,
    val velEastMps: Float?,
    val fixElapsedMs: Long,
) {
    companion object {
        /**
         * A fix from location-stack readings: ground [speedMps] and [bearingDeg] (degrees clockwise
         * from true north) become north and east velocity. A standstill reports no bearing, so a
         * speed below [STILL_MPS] with no bearing is a measured zero velocity.
         */
        fun of(
            latDeg: Double,
            lonDeg: Double,
            mslAltM: Double?,
            horizAccM: Float,
            vertAccM: Float?,
            speedMps: Float?,
            bearingDeg: Float?,
            fixElapsedMs: Long,
        ): BeaconFix {
            val vel = when {
                speedMps == null -> null
                bearingDeg != null -> {
                    val rad = Math.toRadians(bearingDeg.toDouble())
                    (speedMps * Math.cos(rad)).toFloat() to (speedMps * Math.sin(rad)).toFloat()
                }
                speedMps < STILL_MPS -> 0f to 0f
                else -> null
            }
            return BeaconFix(latDeg, lonDeg, mslAltM, horizAccM, vertAccM, vel?.first, vel?.second, fixElapsedMs)
        }

        /** Below this ground speed a missing bearing means standing still. */
        const val STILL_MPS = 0.2f
    }
}

/** `FOLLOW_TARGET` (#144) encoding of a [BeaconFix]. */
object FollowBeaconCodec {
    /** `FOLLOW_TARGET_CAP_FLAGS`: the position is valid. */
    const val CAP_POS = 1

    /** `FOLLOW_TARGET_CAP_FLAGS`: the velocity is measured. */
    const val CAP_VEL = 2

    /**
     * The message for [fix]: position in degE7, altitude above mean sea level (NaN when unknown),
     * north-east-down velocity with down 0 (a phone measures ground velocity only; the bit is set
     * only when that was measured), `position_cov` = horizontal and vertical accuracy in metres
     * (the message's `eph epv`, 0 when unknown). Acceleration, attitude and rates are not measured
     * and stay zero with their bits clear.
     */
    fun encode(fix: BeaconFix): FollowTarget {
        val vel = if (fix.velNorthMps != null && fix.velEastMps != null) floatArrayOf(fix.velNorthMps, fix.velEastMps, 0f) else null
        return FollowTarget(
            timestamp = fix.fixElapsedMs,
            estCapabilities = CAP_POS or (if (vel != null) CAP_VEL else 0),
            lat = Math.round(fix.latDeg * 1e7).toInt(),
            lon = Math.round(fix.lonDeg * 1e7).toInt(),
            alt = fix.mslAltM?.toFloat() ?: Float.NaN,
            vel = vel ?: FloatArray(3),
            positionCov = floatArrayOf(fix.horizAccM, fix.vertAccM ?: 0f, 0f),
        )
    }
}

/** Why the beacon is not sharing although the operator turned it on. */
enum class ShareBlock {
    /** No vehicle is connected. */
    NoVehicle,

    /** Follow-me is not installed on the flown node. */
    NoFollow,

    /** Precise location permission is not granted. */
    NoPermission,

    /** The app has been in the background longer than the grace period. */
    Background,

    /** No fix newer than [FollowBeaconPolicy.MAX_FIX_AGE_MS]. */
    NoFix,

    /** Fresh fixes, but the link does not take them (a receive-only or unauthorised link). */
    NotSent,

    /** Sent, but the vehicle's router has published none of it lately (it drops them, or cannot be asked). */
    NotConfirmed,
}

/** What the beacon is doing, for the cockpit chip. */
sealed interface BeaconShare {
    /** The operator has not turned sharing on. */
    data object Off : BeaconShare

    /** Turned on, but [reason] stops it. */
    data class Blocked(val reason: ShareBlock) : BeaconShare

    /** Sending fixes; the newest was accurate to [accuracyM] metres. */
    data class Sharing(val accuracyM: Float) : BeaconShare
}

/** The conditions the beacon shares under, once the operator turned it on. */
data class ShareInputs(
    val vehicleConnected: Boolean,
    val followAvailable: Boolean,
    val permission: Boolean,
    /** Elapsed ms when the app went to the background; null while it is in the foreground. */
    val backgroundSinceMs: Long?,
)

/** When the beacon may share, and how often. */
object FollowBeaconPolicy {
    /** 5 Hz. */
    const val PERIOD_MS = 200L

    /** A fix older than this is never sent; the vehicle drops it at the same age. */
    const val MAX_FIX_AGE_MS = 2_000L

    /** How long sharing survives the app in the background, unless the operator set another value. */
    const val DEFAULT_BACKGROUND_GRACE_MS = 30_000L

    /** Null when [inputs] allow sharing at [nowMs], else the first reason they do not. */
    fun block(inputs: ShareInputs, nowMs: Long, backgroundGraceMs: Long): ShareBlock? = when {
        !inputs.vehicleConnected -> ShareBlock.NoVehicle
        !inputs.followAvailable -> ShareBlock.NoFollow
        !inputs.permission -> ShareBlock.NoPermission
        inputs.backgroundSinceMs != null && nowMs - inputs.backgroundSinceMs > backgroundGraceMs -> ShareBlock.Background
        else -> null
    }

    /** True when [fix] is fresh enough to send at [nowMs]. */
    fun fresh(fix: BeaconFix, nowMs: Long): Boolean = nowMs - fix.fixElapsedMs in 0..MAX_FIX_AGE_MS
}
