package com.altnautica.gcs.feature.plan

import android.content.res.Resources
import com.altnautica.gcs.core.link.mission.MissionError
import com.altnautica.gcs.core.link.mission.MissionType
import com.altnautica.gcs.core.link.text.linkReasonRes
import com.altnautica.gcs.core.link.text.missionResultRes
import com.altnautica.gcs.core.model.CommandResult
import com.altnautica.gcs.feature.plan.io.PlanFormatException
import com.altnautica.gcs.feature.plan.model.PlanWarning

/*
 * Operator text for planner transfers, results and messages. Plain functions over [Resources]
 * so the wording is unit-testable; no internal token or exception text reaches the screen.
 */

/** "Upload to {drone}", or "Upload to drone" before a drone is named. */
internal fun uploadLabel(res: Resources, droneName: String?): String =
    droneName?.let { res.getString(R.string.plan_upload_to, it) } ?: res.getString(R.string.plan_upload_to_drone)

/** "Download from {drone}", or "Download from drone" before a drone is named. */
internal fun downloadLabel(res: Resources, droneName: String?): String =
    droneName?.let { res.getString(R.string.plan_download_from, it) } ?: res.getString(R.string.plan_download_from_drone)

internal fun missionTypeText(res: Resources, type: MissionType): String = res.getString(
    when (type) {
        MissionType.Mission -> R.string.plan_transfer_mission
        MissionType.Fence -> R.string.plan_transfer_fence
        MissionType.Rally -> R.string.plan_transfer_rally
    },
)

internal fun missionErrorText(res: Resources, error: MissionError): String = when (error) {
    MissionError.NoLink -> res.getString(R.string.plan_error_no_link)
    MissionError.Busy -> res.getString(R.string.plan_error_busy)
    MissionError.Timeout -> res.getString(R.string.plan_error_timeout)
    is MissionError.WriteFailed -> res.getString(linkReasonRes(error.reason))
    is MissionError.Rejected -> res.getString(missionResultRes(error.code))
    is MissionError.VerifyMismatch -> res.getString(R.string.plan_error_verify, missionTypeText(res, error.type))
}

internal fun commandResultText(res: Resources, result: CommandResult): String = when (result) {
    CommandResult.Accepted -> ""
    // An FC's own sentence (a status line) is shown as-is; a bare token gives way to the result code's words.
    is CommandResult.Rejected -> result.reason.trim().takeIf { ' ' in it } ?: res.getString(commandRejectedRes(result.code))
    CommandResult.Unsupported -> res.getString(R.string.plan_result_unsupported)
    CommandResult.Timeout -> res.getString(R.string.plan_result_timeout)
    is CommandResult.Denied -> res.getString(linkReasonRes(result.reason))
    is CommandResult.Queued -> res.getString(R.string.plan_result_queued)
}

/** Operator words for a MAV_RESULT code of a refused command. */
private fun commandRejectedRes(code: Int): Int = when (code) {
    1 -> R.string.plan_result_temporarily_rejected
    3, 7, 8, 9 -> R.string.plan_result_unsupported
    4 -> R.string.plan_result_failed
    5 -> R.string.plan_result_in_progress
    6 -> R.string.plan_result_cancelled
    else -> R.string.plan_result_denied
}

internal fun importFailedText(res: Resources, reason: PlanFormatException.Reason?): String = res.getString(
    R.string.plan_msg_import_failed,
    res.getString(
        when (reason) {
            PlanFormatException.Reason.NotWaypoints -> R.string.plan_msg_import_not_waypoints
            PlanFormatException.Reason.NotPlan -> R.string.plan_msg_import_not_plan
            PlanFormatException.Reason.Malformed -> R.string.plan_msg_import_malformed
            PlanFormatException.Reason.ComplexWithoutGeometry -> R.string.plan_msg_import_complex_geometry
            PlanFormatException.Reason.ComplexWithoutAltitude -> R.string.plan_msg_import_complex_altitude
            null -> R.string.plan_msg_import_unreadable
        },
    ),
)

/** One import warning with the file row it came from. */
internal fun importWarningText(res: Resources, warning: PlanWarning): String = when (warning) {
    is PlanWarning.DroppedJump -> res.getString(R.string.plan_import_warning_jump, warning.seq, warning.target)
    is PlanWarning.SkippedItem -> res.getString(R.string.plan_import_warning_skipped, warning.type)
}
