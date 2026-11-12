package com.altnautica.gcs.feature.cockpit

/** One-off cockpit events for snackbars and dialogs. */
sealed interface CockpitEvent {
    data object ExitRequested : CockpitEvent
    data object StreamSwitchFailed : CockpitEvent
    data object RecordingFailed : CockpitEvent
    data object ChecklistOverridden : CockpitEvent

    /** A target action refused before running; [reasonKey] is a skill reason key or extension text. */
    data class TargetRefused(val reasonKey: String) : CockpitEvent

    /** Follow-me re-acquired the target on track [trackId] after a [gapS] s gap. */
    data class Reacquired(val trackId: Int, val similarity: Float, val gapS: Float) : CockpitEvent

    /** A re-acquire mode write was not echoed by follow-me within the timeout. */
    data object ReacquireModeNotApplied : CockpitEvent

    /** The minimap switched to the local frame on its own. */
    data object MinimapLocal : CockpitEvent

    /** A pilot action could not be sent: the vehicle's pilot channel is down. */
    data object PilotUnavailable : CockpitEvent

    /** The pilot refused an action; [reason] is its own text when it gave one. */
    data class PilotRefused(val reason: String?) : CockpitEvent

    /** An action went to the pilot but no answer came back in time. */
    data object PilotNoAnswer : CockpitEvent

    /** The chip's or the Menu's "Set up aircraft" was pressed. */
    data object SetUpAircraft : CockpitEvent

    /** The Menu's Controls: the controller settings. */
    data object OpenControls : CockpitEvent
}

/** The re-acquired toast (`follow.reacquired_toast`): the new track, its similarity to two decimals and the gap to one. */
internal fun reacquiredText(strings: com.altnautica.gcs.core.skills.spec.SetupStringsSpec, e: CockpitEvent.Reacquired): String = strings.format(
    "follow.reacquired_toast",
    mapOf(
        "id" to e.trackId.toString(),
        "sim" to String.format(java.util.Locale.ROOT, "%.2f", e.similarity),
        "gap" to String.format(java.util.Locale.ROOT, "%.1f", e.gapS),
    ),
)
