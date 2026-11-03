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
}
