package com.altnautica.gcs.feature.configure.calibration

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.link.calibration.CalibrationKind
import com.altnautica.gcs.core.link.calibration.CalibrationState
import com.altnautica.gcs.core.model.CommandResult
import com.altnautica.gcs.feature.configure.R
import com.altnautica.gcs.feature.configure.common.commandResultText
import com.altnautica.gcs.feature.configure.wizard.SetupLocalProblem
import com.altnautica.gcs.feature.configure.wizard.SetupProblem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Refusals and failures on the calibration screen read as sentences, never silence or raw tokens. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CalibrationProblemTextTest {
    @get:Rule
    val compose = createComposeRule()

    private val res = ApplicationProvider.getApplicationContext<android.content.Context>().resources

    private fun show(state: CalibrationUiState) = compose.setContent {
        AdosTheme {
            CalibrationScreen(state, onStart = {}, onConfirmPose = {}, onAcceptCompass = {}, onSaveRadio = {}, onCancel = {})
        }
    }

    @Test
    fun anAgentRefusalIsShown() {
        show(CalibrationUiState(block = null, agent = true, agentProblem = SetupProblem(local = SetupLocalProblem.Unauthorized)))
        compose.onNodeWithText(res.getString(R.string.cfg_setup_err_unauthorized)).assertExists()
    }

    @Test
    fun aFailedCalibrationNamesTheReasonInWords() {
        show(CalibrationUiState(block = null, calibration = CalibrationState.Failed(CalibrationKind.Radio, "noRcInput")))
        val reason = res.getString(com.altnautica.gcs.core.link.R.string.link_reason_no_rc_input)
        compose.onNodeWithText(res.getString(R.string.cfg_cal_failed, res.getString(R.string.cfg_cal_radio), reason)).assertExists()
    }

    @Test
    fun commandDenialsReadAsSentences() {
        val denied = commandResultText(res, CommandResult.Denied("receiveOnly"))
        assertEquals(res.getString(R.string.cfg_result_denied, res.getString(com.altnautica.gcs.core.link.R.string.link_reason_receive_only)), denied)
        assertFalse("receiveOnly" in denied)
        val rejected = commandResultText(res, CommandResult.Rejected(4, "result_4"))
        assertFalse("result_4" in rejected)
    }
}
