package com.altnautica.gcs.feature.settings.controller

import com.altnautica.gcs.core.data.controller.ControllerSettings
import com.altnautica.gcs.core.skills.binding.BindingConflict
import com.altnautica.gcs.core.skills.controls.ControlsProfile
import com.altnautica.gcs.core.skills.controls.PadKind
import com.altnautica.gcs.core.skills.controls.StickMode
import com.altnautica.gcs.core.skills.input.controller.ControllerCapabilities
import com.altnautica.gcs.core.skills.input.controller.ControllerInfo
import com.altnautica.gcs.core.skills.spec.ControlBindingSpec
import com.altnautica.gcs.core.skills.spec.LockedActionSpec

/** One installed plugin skill as a binding target. */
data class SkillOption(val action: String, val label: String)

/** A plugin's skills, listed under its name in the action picker. */
data class SkillGroup(val pluginName: String, val skills: List<SkillOption>)

/** "Press a control" mode: the next physical press opens that control, or moves [row] onto it. */
data class PressMode(val row: ControlBindingSpec? = null)

enum class CalibrationPhase { Moving, Release, Saved }

/** The stick calibration card: [coverage] is the share of the eight stick edges reached so far. */
data class CalibrationUi(val phase: CalibrationPhase, val coverage: Float)

/** A hardware check in progress. */
enum class HardwareTest { Rumble, Lightbar, Sticks }

/** One-shot outcomes shown as a snackbar. */
sealed interface ControlsMessage {
    data object Locked : ControlsMessage

    data object SameControl : ControlsMessage

    data object Saved : ControlsMessage

    data object SaveRefused : ControlsMessage

    data object Imported : ControlsMessage

    data object ImportUnreadable : ControlsMessage

    /** [actions] are the missing locked actions' labels. */
    data class ImportMissingLocked(val actions: List<String>) : ControlsMessage

    data object ImportRefused : ControlsMessage

    data object FileFailed : ControlsMessage
}

data class ControlsUiState(
    val loaded: Boolean = false,
    /** The controller driving input, or null with none attached. */
    val controller: ControllerInfo? = null,
    val connection: PadConnection = PadConnection.Unknown,
    val capabilities: ControllerCapabilities = ControllerCapabilities.None,
    val pad: PadKind = PadKind.Ds4,
    val stickMode: StickMode = StickMode.Mode2,
    val deadzone: Float = 0f,
    val profile: ControlsProfile = ControlsProfile.AdosDefault,
    /** The binding list on screen: the unsaved edit when there is one, else the saved list. */
    val rows: List<ControlBindingSpec> = emptyList(),
    val conflicts: List<BindingConflict> = emptyList(),
    val missingLocked: List<LockedActionSpec> = emptyList(),
    /** True while [rows] holds edits not yet saved. */
    val dirty: Boolean = false,
    /** The control whose binding sheet is open. */
    val editing: String? = null,
    val press: PressMode? = null,
    val skillGroups: List<SkillGroup> = emptyList(),
    val calibration: CalibrationUi? = null,
    /** True when the attached pad has a stored calibration. */
    val calibrated: Boolean = false,
    val test: HardwareTest? = null,
    /** The first-connect tour card on screen (0..2), or null. */
    val tourStep: Int? = null,
    val settings: ControllerSettings = ControllerSettings(),
    /** Extension skills whose suggested keyboard key another skill or the cockpit already held. */
    val keyConflicts: List<KeyConflictUi> = emptyList(),
    val onScreenSticks: Boolean = false,
    val allModesVehicles: List<AllModesVehicle> = emptyList(),
    /** True once a gamepad or joystick was attached at some point in this process. */
    val padSeen: Boolean = false,
) {
    /** Every control of every conflicting row: the hotspots outlined red. */
    val conflictControls: Set<String> get() = conflicts.flatMapTo(HashSet()) { it.controls }

    val canSave: Boolean get() = dirty && conflicts.isEmpty() && missingLocked.isEmpty()

    /** The prompt shown while no controller drives input. */
    val padPrompt: PadPrompt get() = padPromptFor(controller != null, padSeen)
}
