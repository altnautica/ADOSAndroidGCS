package com.altnautica.gcs.core.link.text

import com.altnautica.gcs.core.link.R
import com.altnautica.gcs.core.link.calibration.MavlinkCalibrationService
import com.altnautica.gcs.core.link.param.ParamSetResult
import com.altnautica.gcs.core.link.command.CommandService
import com.altnautica.gcs.core.link.log.MavlinkLogService
import com.altnautica.gcs.core.link.param.MavlinkParamService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkReasonTextTest {

    @Test
    fun everyServiceReasonTokenHasOwnWords() {
        val tokens = listOf(
            CommandService.REASON_NO_LINK,
            CommandService.REASON_RECEIVE_ONLY,
            CommandService.REASON_NOT_AUTHORIZED,
            MavlinkLogService.REASON_WRITE,
            MavlinkLogService.REASON_TIMEOUT,
            MavlinkParamService.REASON_NO_LINK,
            MavlinkParamService.REASON_TIMEOUT,
            "unsupported", "noRcInput", "notWaiting", "failed",
        )
        tokens.forEach { assertNotEquals(it, R.string.link_reason_unknown, linkReasonRes(it)) }
        assertEquals(R.string.link_reason_no_fc_link, linkReasonRes("noFcLink"))
        assertEquals(R.string.link_reason_no_rc_input, linkReasonRes("noRcInput"))
    }

    @Test
    fun unknownOrMissingReasonMapsToGenericWords() {
        assertEquals(R.string.link_reason_unknown, linkReasonRes("someInternalToken"))
        assertEquals(R.string.link_reason_unknown, linkReasonRes(null))
        assertNull(knownLinkReasonRes("PreArm: compass not calibrated"))
    }

    @Test
    fun radioCalibrationWriteFailureCarriesKnownReason() {
        val reason = MavlinkCalibrationService.paramWriteReason(ParamSetResult.UnknownParam)
        assertEquals("writeFailed", reason)
        assertEquals(R.string.link_reason_write_failed, linkReasonRes(reason))
        assertEquals(R.string.link_reason_timeout, linkReasonRes(MavlinkCalibrationService.paramWriteReason(ParamSetResult.Timeout)))
    }

    @Test
    fun missionResultCodesMapToWords() {
        assertEquals(R.string.link_mission_result_invalid, missionResultRes(5))
        assertEquals(R.string.link_mission_result_no_space, missionResultRes(4))
        assertEquals(R.string.link_mission_result_denied, missionResultRes(14))
        assertEquals(R.string.link_mission_result_unknown, missionResultRes(99))
    }
}
