package com.altnautica.gcs.feature.configure.calibration

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.altnautica.gcs.core.designsystem.component.overlay.ProvideOverlayHost
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.link.calibration.CalibrationKind
import com.altnautica.gcs.core.model.FcVariant
import com.altnautica.gcs.feature.configure.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Calibrations that spin motors or need the operator's hands start only after a confirmation. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CalibrationPrecautionTest {
    @get:Rule
    val compose = createComposeRule()

    private val res = ApplicationProvider.getApplicationContext<android.content.Context>().resources

    @Test
    fun escStartsOnlyAfterThePropellerWarningIsConfirmed() {
        val started = mutableListOf<CalibrationKind>()
        compose.setContent {
            AdosTheme {
                // As in the app: sheets draw in the overlay host, above the screen.
                ProvideOverlayHost {
                    CalibrationScreen(
                        CalibrationUiState(block = null, variant = FcVariant.MavlinkPx4, mavType = 2),
                        onStart = { started += it },
                        onConfirmPose = {},
                        onAcceptCompass = {},
                        onSaveRadio = {},
                        onCancel = {},
                    )
                }
            }
        }
        // The ESC card sits past the window edge in the horizontally scrolling card row.
        compose.onNodeWithText(res.getString(R.string.cfg_cal_esc)).performScrollTo().performClick()
        compose.onNodeWithText(res.getString(R.string.cfg_cal_esc_precaution)).assertExists()
        assertEquals(emptyList<CalibrationKind>(), started)
        compose.onNodeWithText(res.getString(R.string.cfg_cal_start)).performClick()
        assertEquals(listOf(CalibrationKind.Esc), started)
    }
}
