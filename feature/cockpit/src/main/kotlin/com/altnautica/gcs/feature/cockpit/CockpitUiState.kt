package com.altnautica.gcs.feature.cockpit

import com.altnautica.gcs.core.model.GpsFix
import com.altnautica.gcs.core.model.NodeProfile
import com.altnautica.gcs.core.model.Reach
import com.altnautica.gcs.core.model.Skill
import com.altnautica.gcs.core.model.SkillState
import com.altnautica.gcs.core.model.StreamDescriptor
import com.altnautica.gcs.core.model.VideoSource
import com.altnautica.gcs.core.model.VideoState
import com.altnautica.gcs.feature.cockpit.alerts.LinkHealth

/** What the cockpit can show for the requested node (CS-9). */
sealed interface CockpitStatus {
    /** The node is not known to any link source. */
    data object UnknownNode : CockpitStatus

    /** A ground station with no carried drone to fly. */
    data object NoCarriedDrone : CockpitStatus

    /** Opening the vehicle link. */
    data object Connecting : CockpitStatus

    /** The vehicle link is open. */
    data object Ready : CockpitStatus
}

/** Reach badge (CS-2). */
enum class ReachBadge { Direct, Lan, ViaGround, Cloud, PhoneRadio, Blocked }

fun Reach.badge(): ReachBadge = when (this) {
    Reach.DirectFc -> ReachBadge.Direct
    Reach.Lan -> ReachBadge.Lan
    is Reach.ViaGround -> ReachBadge.ViaGround
    Reach.CloudLive, Reach.CloudQueue -> ReachBadge.Cloud
    Reach.PhoneRadio -> ReachBadge.PhoneRadio
    is Reach.Blocked -> ReachBadge.Blocked
}

/** Stick-control chip state (CS-2). */
sealed interface StickChip {
    /** No gamepad attached: the chip is hidden. */
    data object NoGamepad : StickChip

    /** The current reach cannot carry manual control (cloud, phone radio, MSP). */
    data object LinkBlocked : StickChip

    data class Available(val on: Boolean) : StickChip
}

/**
 * Safety-band values. Each field is coarse (whole percent, fix class, link bucket)
 * so the band recomposes only when a displayed value changes.
 */
data class BandState(
    val nodeName: String?,
    val profile: NodeProfile,
    val reach: ReachBadge?,
    val armed: Boolean?,
    val modeName: String?,
    /** Null when unknown; shown as a dash, never as zero. */
    val batteryPct: Int?,
    val timeToReserveS: Int?,
    val batteryLevel: BatteryBand,
    val gpsFix: GpsFix?,
    val satellites: Int?,
    val link: LinkHealth,
    /** Heartbeat age in tenths of a second, for the link chip. */
    val linkAgeDeciS: Int?,
    val rssiDbm: Int?,
    val lqPct: Int?,
    val video: VideoState,
    val stick: StickChip,
    val preflightPassed: Int,
    val preflightTotal: Int,
    /** Wall-clock start of the current recording, or null when not recording. */
    val recordingStartedAtMs: Long?,
    /** Monotonic arm time, or null when disarmed. */
    val armedSinceMs: Long?,
) {
    companion object {
        val Empty = BandState(
            nodeName = null, profile = NodeProfile.Unknown, reach = null, armed = null, modeName = null,
            batteryPct = null, timeToReserveS = null, batteryLevel = BatteryBand.Unknown, gpsFix = null, satellites = null,
            link = LinkHealth.None, linkAgeDeciS = null, rssiDbm = null, lqPct = null, video = VideoState.NoSource,
            stick = StickChip.NoGamepad, preflightPassed = 0, preflightTotal = 0, recordingStartedAtMs = null, armedSinceMs = null,
        )
    }
}

enum class BatteryBand { Unknown, Good, Low, Critical }

/** One Skill Bar slot. */
data class SlotUi(
    val index: Int,
    val skill: Skill?,
    val state: SkillState?,
    val keyChord: String?,
    val gamepadButton: Int?,
)

/** Video sources for the main feed and picture-in-picture, plus the stream tabs (CS-11). */
data class VideoUi(
    val main: VideoSource?,
    val pip: VideoSource?,
    val streams: List<StreamDescriptor>,
    val selectedStreamId: String?,
    /** A switchable stream being dialled; its tab shimmers. */
    val switchingStreamId: String?,
)

/** CS-4 telemetry strip values, sampled at 2 Hz. Null fields render as a dash. */
data class StripValues(
    val homeDistanceM: Float?,
    val homeBearingDeg: Float?,
    val climbMs: Float?,
    val throttlePct: Int?,
    val missionSeq: Int?,
    val missionTotal: Int?,
    val satellites: Int?,
    val hdop: Float?,
)

/** Minimap content. */
data class MinimapState(
    val vehicle: com.altnautica.gcs.core.map.MapVehicle?,
    val home: com.altnautica.gcs.core.map.GeoPoint?,
    val trail: List<com.altnautica.gcs.core.map.GeoPoint>,
)
