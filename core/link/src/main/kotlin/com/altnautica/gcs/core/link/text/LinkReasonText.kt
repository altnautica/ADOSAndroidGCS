package com.altnautica.gcs.core.link.text

import android.content.res.Resources
import androidx.annotation.StringRes
import com.altnautica.gcs.core.link.R

/**
 * Operator words for the reason tokens the link services return
 * (command denials, write failures, calibration and log errors).
 * An unknown or missing token maps to a generic sentence; the raw token never reaches the screen.
 */
@StringRes
fun linkReasonRes(reason: String?): Int = knownLinkReasonRes(reason) ?: R.string.link_reason_unknown

/** The words for [reason] when it is a known link token, else null (free text such as an FC status line). */
@StringRes
fun knownLinkReasonRes(reason: String?): Int? = when (reason) {
    "noFcLink" -> R.string.link_reason_no_fc_link
    "receiveOnly" -> R.string.link_reason_receive_only
    "notAuthorized" -> R.string.link_reason_not_authorized
    "timeout" -> R.string.link_reason_timeout
    "unsupported" -> R.string.link_reason_unsupported
    "writeFailed" -> R.string.link_reason_write_failed
    "noRcInput" -> R.string.link_reason_no_rc_input
    "notWaiting" -> R.string.link_reason_not_waiting
    "failed" -> R.string.link_reason_failed
    "cancelled" -> R.string.link_reason_cancelled
    "busy" -> R.string.link_reason_busy
    "notPaired" -> R.string.link_reason_not_paired
    "sticksNotMoved" -> R.string.link_reason_sticks_not_moved
    "pinRequired" -> R.string.link_reason_pin_required
    "armed" -> R.string.link_reason_armed
    "denied", "rejected" -> R.string.link_reason_denied
    "temporarily_rejected" -> R.string.link_reason_temporarily_rejected
    "command_long_only", "command_int_only", "unsupported_frame" -> R.string.link_reason_unsupported
    else -> null
}

fun linkReasonText(resources: Resources, reason: String?): String = resources.getString(linkReasonRes(reason))

/**
 * Like [linkReasonText], but a reason that is already a sentence (an FC status line or an agent's
 * own message, recognised by containing a space) is shown as-is. Single-word tokens never pass through.
 */
fun linkReasonOrMessage(resources: Resources, reason: String?): String {
    knownLinkReasonRes(reason)?.let { return resources.getString(it) }
    val r = reason?.trim()
    return if (r != null && ' ' in r) r else resources.getString(R.string.link_reason_unknown)
}

/** Operator words for a MAV_MISSION_RESULT code from a rejected mission transfer. */
@StringRes
fun missionResultRes(code: Int): Int = when (code) {
    1 -> R.string.link_mission_result_error
    2 -> R.string.link_mission_result_unsupported_frame
    3 -> R.string.link_mission_result_unsupported
    4 -> R.string.link_mission_result_no_space
    in 5..12 -> R.string.link_mission_result_invalid
    13 -> R.string.link_mission_result_invalid_sequence
    14 -> R.string.link_mission_result_denied
    15 -> R.string.link_mission_result_cancelled
    else -> R.string.link_mission_result_unknown
}
