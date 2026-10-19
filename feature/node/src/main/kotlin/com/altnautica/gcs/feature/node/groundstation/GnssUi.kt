package com.altnautica.gcs.feature.node.groundstation

import com.altnautica.gcs.core.agent.sensors.GnssBase
import com.altnautica.gcs.core.agent.sensors.GnssReceiver
import com.altnautica.gcs.core.agent.sensors.GnssStatus

enum class GnssFixKind { None, Fix2d, Fix3d, Dgps, RtkFloat, RtkFixed;

    val hasFix: Boolean get() = this != None && this != Fix2d
}

enum class BaseState { Off, Surveying, Fixed, Failed, Unknown }

data class PositionUi(val latDeg: Double, val lonDeg: Double, val hEllipsoidM: Double)

data class ReceiverUi(
    val id: String,
    val model: String?,
    val fix: GnssFixKind,
    val sats: Int?,
    val hdop: Double?,
    val rtk: String?,
    /** Null unless latitude, longitude and ellipsoidal height are all known. */
    val position: PositionUi?,
    val latDeg: Double?,
    val lonDeg: Double?,
    val hEllipsoidM: Double?,
    val ppsLocked: Boolean?,
    val source: String?,
)

data class BaseUi(
    val isOff: Boolean,
    val state: BaseState,
    val elapsedS: Long?,
    val minDurS: Long?,
    val accuracyM: Double?,
    val targetAccuracyM: Double?,
    val fixed: PositionUi?,
    val rtcmMsgsPerS: Double?,
    val rtcmTargets: Int?,
    val rtcmBytes: Long?,
)

data class HeadingUi(val deg: Double?, val accuracyDeg: Double?, val baselineM: Double?)

/** The GNSS tab's view of `GET /api/v1/gnss`. */
data class GnssUi(val receivers: List<ReceiverUi>, val base: BaseUi?, val heading: HeadingUi?)

object GnssMapper {

    /** Null when no receiver is fitted (`present: false`). */
    fun map(s: GnssStatus): GnssUi? = if (!s.present) {
        null
    } else {
        GnssUi(
            receivers = s.receivers.map(::receiver),
            base = s.base?.let(::base),
            heading = s.heading?.takeIf { it.available }?.let { HeadingUi(it.deg, it.accuracyDeg, it.baselineM) },
        )
    }

    fun receiver(r: GnssReceiver): ReceiverUi {
        val lat = r.latDeg
        val lon = r.lonDeg
        val h = r.hEllipsoidM
        return ReceiverUi(
            id = r.id,
            model = r.model,
            fix = when (r.fix) {
                "2d" -> GnssFixKind.Fix2d
                "3d" -> GnssFixKind.Fix3d
                "dgps" -> GnssFixKind.Dgps
                "rtk_float" -> GnssFixKind.RtkFloat
                "rtk_fixed" -> GnssFixKind.RtkFixed
                else -> GnssFixKind.None
            },
            sats = r.sats,
            hdop = r.hdop,
            rtk = r.rtk,
            position = if (lat != null && lon != null && h != null) PositionUi(lat, lon, h) else null,
            latDeg = lat,
            lonDeg = lon,
            hEllipsoidM = h,
            ppsLocked = r.ppsLocked,
            source = r.source,
        )
    }

    fun base(b: GnssBase): BaseUi = BaseUi(
        isOff = b.mode == "off",
        state = when (b.state) {
            "off" -> BaseState.Off
            "surveying" -> BaseState.Surveying
            "fixed" -> BaseState.Fixed
            "failed" -> BaseState.Failed
            else -> BaseState.Unknown
        },
        elapsedS = b.elapsedS,
        minDurS = b.minDurS,
        accuracyM = b.accuracyM,
        targetAccuracyM = b.targetAccuracyM,
        fixed = b.fixed?.let { PositionUi(it.latDeg, it.lonDeg, it.hEllipsoidM) },
        rtcmMsgsPerS = b.rtcmOut?.msgsPerS,
        rtcmTargets = b.rtcmOut?.targets,
        rtcmBytes = b.rtcmOut?.bytesTotal,
    )
}
