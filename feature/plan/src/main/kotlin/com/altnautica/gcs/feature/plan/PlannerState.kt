package com.altnautica.gcs.feature.plan

import com.altnautica.gcs.core.database.SavedPlanEntity
import com.altnautica.gcs.core.link.mission.MissionError
import com.altnautica.gcs.core.link.mission.MissionType
import com.altnautica.gcs.core.link.mission.TransferDirection
import com.altnautica.gcs.core.map.GeoPoint
import com.altnautica.gcs.core.map.MapBasemap
import com.altnautica.gcs.core.map.MapFeatureRef
import com.altnautica.gcs.core.map.MapProvider
import com.altnautica.gcs.core.map.PmtilesPack
import com.altnautica.gcs.core.model.CommandResult
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.GpsFix
import com.altnautica.gcs.core.model.MissionState
import com.altnautica.gcs.core.model.NodeSummary
import com.altnautica.gcs.core.model.PilotActionResult
import com.altnautica.gcs.core.model.PlaceClass
import com.altnautica.gcs.core.model.RouteCheck
import com.altnautica.gcs.core.link.mission.PilotPlanView
import com.altnautica.gcs.core.skills.hud.SpecText
import com.altnautica.gcs.feature.plan.io.PlanFileFormat
import com.altnautica.gcs.feature.plan.io.PlanFormatException
import com.altnautica.gcs.feature.plan.model.AltitudeRef
import com.altnautica.gcs.feature.plan.model.Autopilot
import com.altnautica.gcs.feature.plan.model.MissionPlan
import com.altnautica.gcs.feature.plan.model.FenceLimits
import com.altnautica.gcs.feature.plan.model.ValidationProfile
import com.altnautica.gcs.feature.plan.model.MissionValidator
import com.altnautica.gcs.feature.plan.model.PlanIssue
import com.altnautica.gcs.feature.plan.model.PlanWarning
import com.altnautica.gcs.feature.plan.model.PlanStats
import com.altnautica.gcs.feature.plan.survey.SurveyPlan
import com.altnautica.gcs.feature.plan.survey.SurveySettings

/** What a map tap does. */
enum class EditTool { Waypoint, Survey, FencePolygon, FenceCircle, Rally }

/** The thing the editor panel shows. */
sealed interface Selection {
    data class Item(val id: Long) : Selection

    data class Rally(val id: Long) : Selection

    data class Fence(val id: Long) : Selection
}

/** Connection to the vehicle the plan is for (states). */
sealed interface VehicleLink {
    /** No vehicle chosen. */
    data object None : VehicleLink

    /** Vehicle chosen, link not up yet. */
    data object Connecting : VehicleLink

    /** The vehicle is not reachable over MAVLink (for example an MSP flight controller). */
    data object Unsupported : VehicleLink

    data class Connected(
        val armed: Boolean?,
        /** Heartbeat within the last 3 s. */
        val linkFresh: Boolean,
        val position: GeoPoint?,
        val headingDeg: Float?,
        val home: GeoPoint?,
        val homeAltMslM: Float?,
        val gpsFix: GpsFix?,
        /** ArduPilot keeps home in mission slot 0; PX4 does not. */
        val homeSlot: Boolean,
        /** HEARTBEAT `type` (MAV_TYPE); null before the first heartbeat. */
        val vehicleType: Int? = null,
        val autopilot: Autopilot = Autopilot.Unknown,
        /** Armed and in the autopilot's mission mode (heartbeat AUTO): flying a mission. */
        val flyingMission: Boolean = false,
        /** Fence limits from the vehicle's parameters; null when none apply or none are loaded. */
        val fence: FenceLimits? = null,
    ) : VehicleLink
}

/** Mission/fence/rally transfer progress. */
sealed interface TransferState {
    data object Idle : TransferState

    data class Running(val direction: TransferDirection, val type: MissionType, val done: Int, val total: Int) : TransferState

    /** Upload finished; reading [type] back to compare it with what was sent. */
    data class Verifying(val type: MissionType) : TransferState

    data class Done(val direction: TransferDirection, val itemCount: Int) : TransferState

    data class Failed(val direction: TransferDirection, val type: MissionType, val error: MissionError) : TransferState
}

enum class StartCheckKind(val blocking: Boolean) {
    LinkFresh(blocking = true),
    PlanOnVehicle(blocking = true),

    /**
     * The plan's place class can be flown: the pilot already flies it, or reports a class Start
     * can set. A confined plan on a vehicle with no pilot reporting fails.
     */
    PlaceClass(blocking = true),

    /** The pilot's route check of a confined plan does not read infeasible. */
    RouteCheck(blocking = true),
    GpsFix(blocking = false),
    HomeSet(blocking = false),
}

data class StartCheck(val kind: StartCheckKind, val ok: Boolean)

sealed interface StartError {
    data class ArmRefused(val result: CommandResult) : StartError

    data class StartRefused(val result: CommandResult) : StartError

    /** The pilot did not take the plan's place class: [result] is its answer. */
    data class PlaceClassRefused(val result: PilotActionResult) : StartError

    /** The pilot accepted the place class but a fresh read still reports [reported]. */
    data class PlaceClassNotSet(val reported: PlaceClass?) : StartError

    /** Once in the plan's class, the pilot's route check reads infeasible: nothing was armed. */
    data object RouteInfeasible : StartError

    /** A confined plan the pilot has not route-checked within the read-back: nothing was armed. */
    data object RouteNotChecked : StartError
}

/** "Start mission" confirm sheet (slide tier). */
data class StartMissionSheet(
    val checks: List<StartCheck>,
    val armed: Boolean,
    val overrideChecklist: Boolean = false,
    val running: Boolean = false,
    val error: StartError? = null,
    /** The plan's place class (null: the plan leaves it as the pilot has it), and the one the vehicle's pilot flies now (null with no pilot). */
    val placeClass: PlaceClass? = PlaceClass.Open,
    val pilotPlaceClass: PlaceClass? = null,
    /** The pilot's route check of the loaded mission, while it flies the plan's class. */
    val routeCheck: RouteCheck? = null,
) {
    private val blockingOk get() = checks.filter { it.kind.blocking }.all { it.ok }
    private val softOk get() = checks.filterNot { it.kind.blocking }.all { it.ok }

    val failing: List<StartCheck> get() = checks.filterNot { it.ok }

    /** The slide is live when hard checks pass and soft checks pass or are overridden. */
    val canSlide: Boolean get() = !running && blockingOk && (softOk || overrideChecklist)

    val needsOverride: Boolean get() = blockingOk && !softOk

    /** Start sets the plan's place class on the pilot before arming. */
    val setsPlaceClass: Boolean get() = placeClass != null && pilotPlaceClass != null && pilotPlaceClass != placeClass
}

/** One-shot notices. */
sealed interface PlannerMessage {
    data class Saved(val name: String) : PlannerMessage

    data class Loaded(val name: String) : PlannerMessage

    data object Deleted : PlannerMessage

    data class Imported(val name: String, val warnings: Int) : PlannerMessage

    /** [reason] null means the file itself could not be read; the exception text stays in the log. */
    data class ImportFailed(val reason: PlanFormatException.Reason?) : PlannerMessage

    data class Exported(val format: PlanFileFormat) : PlannerMessage

    data object ExportFailed : PlannerMessage

    data object TransferInProgress : PlannerMessage

    data object SurveyEmpty : PlannerMessage

    data object VehicleHasNoMission : PlannerMessage

    data class DownloadPartial(val failed: MissionType) : PlannerMessage
}

/** An action that drops unsaved edits; asked for first while the plan is dirty. */
sealed interface DiscardAction {
    data object New : DiscardAction

    data class Load(val id: String) : DiscardAction

    data object Import : DiscardAction

    /** Leaving the planner. */
    data object Leave : DiscardAction
}

/** A feature being dragged: drawn at [point] until the drag ends and commits once. */
data class DragPreview(val ref: MapFeatureRef, val point: GeoPoint)

data class PlannerUiState(
    val plan: MissionPlan = MissionPlan(),
    val name: String = "",
    val savedId: String? = null,
    val dirty: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val dragPreview: DragPreview? = null,
    val selection: Selection? = null,
    val tool: EditTool = EditTool.Waypoint,
    val defaultAltM: Float = 30f,
    val defaultRef: AltitudeRef = AltitudeRef.Relative,
    val survey: List<GeoPoint> = emptyList(),
    val surveySettings: SurveySettings = SurveySettings(),
    val surveyPreview: SurveyPlan? = null,
    val fenceDraft: List<GeoPoint> = emptyList(),
    val fenceDraftInclusion: Boolean = true,
    val basemap: MapBasemap = MapBasemap.Online(MapProvider.Osm),
    /** Imported raster PMTiles packs usable as the basemap. */
    val packs: List<PmtilesPack> = emptyList(),
    val nodeId: DeviceId? = null,
    val nodeName: String? = null,
    val nodes: List<NodeSummary> = emptyList(),
    val link: VehicleLink = VehicleLink.None,
    val transfer: TransferState = TransferState.Idle,
    /** The plan as last uploaded (and read back) or downloaded from the vehicle. */
    val vehiclePlan: MissionPlan? = null,
    /** The vehicle's own plan from its agent's mirror; null without an agent path. */
    val drone: DroneMissionUi? = null,
    /** Waypoints of the vehicle's plan its pilot skipped this flight, where the mirror places them. */
    val skipped: List<SkippedWaypoint> = emptyList(),
    val confirmReplaceWithDownload: Boolean = false,
    val library: List<SavedPlanEntity> = emptyList(),
    /** A discarding action waiting for the operator's confirm. */
    val pendingDiscard: DiscardAction? = null,
    /** One-shot: the screen opens the import file picker, then calls `importPickerShown`. */
    val pickImport: Boolean = false,
    /** One-shot: leaving was confirmed; the screen pops, then calls `leaveHandled`. */
    val leaveApproved: Boolean = false,
    /** A library plan waiting for the delete confirm. */
    val pendingDelete: SavedPlanEntity? = null,
    val startSheet: StartMissionSheet? = null,
    /** Set once the mission started; the screen routes to the cockpit and clears it. */
    val missionStartedFor: DeviceId? = null,
    val message: PlannerMessage? = null,
    /** What the last import dropped or skipped, listed in a sheet until dismissed. */
    val importWarnings: List<PlanWarning> = emptyList(),
    /** The vehicle's pilot as last read: its place class and route check of the loaded mission. */
    val pilot: PilotPlanView = PilotPlanView.None,
    /** The cockpit spec's strings, for the place class and route check in the operators' shared words. */
    val specText: SpecText? = null,
) {
    val inSyncWithVehicle: Boolean
        get() = vehiclePlan != null &&
            vehiclePlan.items == plan.items &&
            vehiclePlan.fence == plan.fence &&
            vehiclePlan.rally == plan.rally
    val transferring: Boolean get() = transfer is TransferState.Running || transfer is TransferState.Verifying

    /**
     * This plan is what the drone holds: in sync with the last transfer, not empty, and no
     * other client has replaced it since (the agent mirror says the drone's plan differs).
     */
    val planOnVehicle: Boolean get() = inSyncWithVehicle && plan.items.isNotEmpty() && drone?.differs != true

    /**
     * The drone is flying its mission, by its agent's mirror or its own heartbeat (armed in
     * mission mode): uploads would swap the mission or limits under it.
     */
    val missionInFlight: Boolean
        get() = drone?.state == MissionState.Active || (link as? VehicleLink.Connected)?.flyingMission == true

    /**
     * Connected, but the arm state is not known: no heartbeat yet, or the last one is stale.
     * The drone may be flying, so uploads wait for a fresh heartbeat.
     */
    val armStateUnknown: Boolean
        get() = (link as? VehicleLink.Connected)?.let { it.armed == null || !it.linkFresh } == true

    /** The plan as drawn: [plan] with an active drag applied. */
    val displayPlan: MissionPlan get() = dragPreview?.applyTo(plan) ?: plan

    /** Pre-upload findings for the current plan. */
    val issues: List<PlanIssue>
        get() {
            val connected = link as? VehicleLink.Connected
            return MissionValidator.validate(
                plan,
                ValidationProfile(connected?.vehicleType, connected?.autopilot ?: Autopilot.Unknown, connected?.fence, connected?.home),
            )
        }

    val stats: PlanStats get() = PlanStats.of(plan)
}
