package com.altnautica.gcs.core.agent.fcsetup

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** State of one setup step. */
@Serializable
enum class SetupStepState {
    @SerialName("pending") Pending,
    @SerialName("running") Running,
    @SerialName("waiting_user") WaitingUser,
    @SerialName("done") Done,
    @SerialName("failed") Failed,
    @SerialName("skipped") Skipped,
}

@Serializable
enum class SetupActionStyle {
    @SerialName("primary") Primary,
    @SerialName("secondary") Secondary,
    @SerialName("danger") Danger,
}

/** How a step action is confirmed before it is sent. */
@Serializable
enum class SetupConfirm {
    @SerialName("none") None,
    @SerialName("hold") Hold,

    /** A hold-confirm whose label reads "Propellers are removed". */
    @SerialName("hold_props_off") HoldPropsOff,
}

@Serializable
data class SetupAction(
    val id: String,
    @SerialName("label_key") val labelKey: String,
    val style: SetupActionStyle = SetupActionStyle.Secondary,
    val confirm: SetupConfirm = SetupConfirm.None,
)

/** One step of a setup wizard, rendered generically by every surface. */
@Serializable
data class SetupStep(
    val wizard: String,
    val step: String,
    @SerialName("title_key") val titleKey: String,
    @SerialName("body_key") val bodyKey: String? = null,
    val args: JsonObject = JsonObject(emptyMap()),
    val image: String? = null,
    val state: SetupStepState = SetupStepState.Pending,
    /** 0..1, or null when the step reports no progress. */
    val progress: Float? = null,
    /** Live values keyed by their label key, in wire order. */
    val live: JsonObject = JsonObject(emptyMap()),
    val actions: List<SetupAction> = emptyList(),
    val blockers: List<String> = emptyList(),
    val result: JsonObject? = null,
)

@Serializable
enum class SetupWizardState {
    @SerialName("not_started") NotStarted,
    @SerialName("running") Running,
    @SerialName("done") Done,
    @SerialName("failed") Failed,
    @SerialName("aborted") Aborted,
}

@Serializable
data class SetupWizard(
    val id: String,
    val state: SetupWizardState = SetupWizardState.NotStarted,
    val available: Boolean = true,
    val blockers: List<String> = emptyList(),
    @SerialName("completed_at") val completedAt: String? = null,
    val steps: List<SetupStep> = emptyList(),
) {
    val completed: Boolean get() = state == SetupWizardState.Done
}

@Serializable
data class SetupRunning(val wizard: String, val step: String)

@Serializable
data class SetupSnapshot(val file: String, val ts: String? = null)

/** The per-aircraft setup report: each step's stored result and the parameter snapshots. */
@Serializable
data class SetupReport(
    val date: String? = null,
    val firmware: String? = null,
    @SerialName("firmware_version") val firmwareVersion: String? = null,
    @SerialName("vehicle_class") val vehicleClass: String? = null,
    val steps: Map<String, JsonElement> = emptyMap(),
    val snapshots: List<SetupSnapshot> = emptyList(),
)

/** `GET /api/v1/fc-setup`: the wizards of the connected flight controller and their state. */
@Serializable
data class FcSetupOverview(
    val v: Int = 1,
    val uid: String? = null,
    val firmware: String? = null,
    @SerialName("firmware_version") val firmwareVersion: String? = null,
    @SerialName("vehicle_class") val vehicleClass: String? = null,
    val running: SetupRunning? = null,
    val wizards: List<SetupWizard> = emptyList(),
    val report: SetupReport? = null,
) {
    fun wizard(id: String): SetupWizard? = wizards.firstOrNull { it.id == id }

    /** True when this flight controller has never finished the `ground` wizard. */
    val needsGroundSetup: Boolean get() = uid != null && wizard(FcSetupApi.WIZARD_GROUND)?.completed != true
}

@Serializable
data class WizardReply(val ok: Boolean = true, val wizard: SetupWizard? = null)

@Serializable
data class StepReply(val ok: Boolean = true, val step: SetupStep? = null)

@Serializable
data class RevertReply(val ok: Boolean = true, val restored: Int = 0)

/** One frame of `WS /api/v1/fc-setup/stream`. */
sealed interface FcSetupEvent {
    data class Step(val tsMs: Long, val step: SetupStep) : FcSetupEvent
    data class Wizard(val tsMs: Long, val wizard: SetupWizard) : FcSetupEvent
}
