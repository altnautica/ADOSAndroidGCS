package com.altnautica.gcs.feature.plan

import com.altnautica.gcs.core.database.SavedPlanEntity
import com.altnautica.gcs.core.link.mission.MissionError
import com.altnautica.gcs.core.link.mission.MissionType
import com.altnautica.gcs.core.link.mission.TransferDirection
import com.altnautica.gcs.core.map.GeoPoint
import com.altnautica.gcs.core.map.MapBasemap
import com.altnautica.gcs.core.map.MapProvider
import com.altnautica.gcs.core.map.PmtilesPack
import com.altnautica.gcs.core.model.CommandResult
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.GpsFix
import com.altnautica.gcs.core.model.NodeSummary
import com.altnautica.gcs.feature.plan.io.PlanFileFormat
import com.altnautica.gcs.feature.plan.io.PlanFormatException
import com.altnautica.gcs.feature.plan.model.AltitudeRef
import com.altnautica.gcs.feature.plan.model.MissionPlan
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

/** Connection to the vehicle the plan is for (CS-9 states). */
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
    ) : VehicleLink
}

/** Mission/fence/rally transfer progress. */
sealed interface TransferState {
    data object Idle : TransferState

    data class Running(val direction: TransferDirection, val type: MissionType, val done: Int, val total: Int) : TransferState

    data class Done(val direction: TransferDirection, val itemCount: Int) : TransferState

    data class Failed(val direction: TransferDirection, val type: MissionType, val error: MissionError) : TransferState
}

enum class StartCheckKind(val blocking: Boolean) {
    LinkFresh(blocking = true),
    PlanOnVehicle(blocking = true),
    GpsFix(blocking = false),
    HomeSet(blocking = false),
}

data class StartCheck(val kind: StartCheckKind, val ok: Boolean)

sealed interface StartError {
    data class ArmRefused(val result: CommandResult) : StartError

    data class StartRefused(val result: CommandResult) : StartError
}

/** "Start mission" confirm sheet (CS-6 slide tier). */
data class StartMissionSheet(
    val checks: List<StartCheck>,
    val armed: Boolean,
    val overrideChecklist: Boolean = false,
    val running: Boolean = false,
    val error: StartError? = null,
) {
    private val blockingOk get() = checks.filter { it.kind.blocking }.all { it.ok }
    private val softOk get() = checks.filterNot { it.kind.blocking }.all { it.ok }

    val failing: List<StartCheck> get() = checks.filterNot { it.ok }

    /** The slide is live when hard checks pass and soft checks pass or are overridden. */
    val canSlide: Boolean get() = !running && blockingOk && (softOk || overrideChecklist)

    val needsOverride: Boolean get() = blockingOk && !softOk
}

/** One-shot notices. */
sealed interface PlannerMessage {
    data class Saved(val name: String) : PlannerMessage

    data class Loaded(val name: String) : PlannerMessage

    data object Deleted : PlannerMessage

    data class Imported(val name: String, val warnings: Int) : PlannerMessage

    data class ImportFailed(val reason: PlanFormatException.Reason?, val detail: String?) : PlannerMessage

    data class Exported(val format: PlanFileFormat) : PlannerMessage

    data class ExportFailed(val detail: String) : PlannerMessage

    data object SurveyEmpty : PlannerMessage

    data object VehicleHasNoMission : PlannerMessage

    data class DownloadPartial(val failed: MissionType) : PlannerMessage
}

data class PlannerUiState(
    val plan: MissionPlan = MissionPlan(),
    val name: String = "",
    val savedId: String? = null,
    val dirty: Boolean = false,
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
    /** The plan as last uploaded to or downloaded from the vehicle. */
    val vehiclePlan: MissionPlan? = null,
    val confirmReplaceWithDownload: Boolean = false,
    val library: List<SavedPlanEntity> = emptyList(),
    val startSheet: StartMissionSheet? = null,
    /** Set once the mission started; the screen routes to the cockpit and clears it. */
    val missionStartedFor: DeviceId? = null,
    val message: PlannerMessage? = null,
) {
    val inSyncWithVehicle: Boolean get() = vehiclePlan != null && vehiclePlan.items == plan.items
    val transferring: Boolean get() = transfer is TransferState.Running
}
