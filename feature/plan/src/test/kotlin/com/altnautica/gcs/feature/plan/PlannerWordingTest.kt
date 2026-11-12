package com.altnautica.gcs.feature.plan

import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altnautica.gcs.core.link.mission.MissionError
import com.altnautica.gcs.core.model.CommandResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PlannerWordingTest {
    private val res: Resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources

    @Test
    fun transferButtonsNameTheDrone() {
        assertEquals("Upload to Hawk 1", uploadLabel(res, "Hawk 1"))
        assertEquals("Download from Hawk 1", downloadLabel(res, "Hawk 1"))
        assertEquals("Upload to drone", uploadLabel(res, null))
        assertEquals("Download from drone", downloadLabel(res, null))
    }

    @Test
    fun reasonTokensAndRejectCodesBecomeWords() {
        listOf("receiveOnly", "notAuthorized", "someInternalToken").forEach { token ->
            val write = missionErrorText(res, MissionError.WriteFailed(token))
            assertFalse(write, write.contains(token))
            val denied = commandResultText(res, CommandResult.Denied(token))
            assertFalse(denied, denied.contains(token))
        }
        assertEquals("This link is receive-only. Take control to send commands", missionErrorText(res, MissionError.WriteFailed("receiveOnly")))
        val rejected = missionErrorText(res, MissionError.Rejected(5))
        assertFalse(rejected, rejected.contains("code"))
        assertFalse(rejected, rejected.contains("5"))
    }

    @Test
    fun refusedCommandsShowWordsNotCodes() {
        assertEquals("the drone refused it", commandResultText(res, CommandResult.Rejected(2, "denied")))
        assertEquals("the drone tried and failed", commandResultText(res, CommandResult.Rejected(4, "denied")))
        assertEquals("the drone can't do that right now; try again", commandResultText(res, CommandResult.Rejected(1, "temporarily_rejected")))
        (0..12).forEach { code ->
            val text = commandResultText(res, CommandResult.Rejected(code, "x"))
            assertFalse(text, text.contains("code") || text.contains("$code") || text.contains("x "))
        }
        // The flight controller's own sentence passes through.
        assertEquals("PreArm: GPS not healthy", commandResultText(res, CommandResult.Rejected(1, "PreArm: GPS not healthy")))
    }

    @Test
    fun importAndExportFailuresHideExceptionText() {
        assertEquals("Could not import: the file could not be read", importFailedText(res, null))
        val export = res.getString(R.string.plan_msg_export_failed)
        assertFalse(export.contains("%"))
    }

    @Test
    fun importWarningsNameTheRows() {
        val text = importWarningText(res, com.altnautica.gcs.feature.plan.model.PlanWarning.DroppedJump(6, 42))
        assertTrue(text, text.contains("Item 6") && text.contains("item 42"))
    }

    @Test
    fun plannerStringsCallTheFlyingThingADrone() {
        val ids = listOf(
            R.string.plan_vehicle_choose, R.string.plan_vehicle_none, R.string.plan_vehicle_picker_title,
            R.string.plan_vehicle_picker_empty_title, R.string.plan_vehicle_picker_detach, R.string.plan_vehicle_needed,
            R.string.plan_vehicle_connecting_detail, R.string.plan_vehicle_stale_detail, R.string.plan_vehicle_action,
            R.string.plan_in_sync, R.string.plan_not_in_sync, R.string.plan_msg_vehicle_empty,
        )
        ids.forEach { id ->
            val text = res.getString(id)
            assertFalse(text, text.contains("vehicle", ignoreCase = true))
            assertTrue(text, text.contains("drone", ignoreCase = true))
        }
    }
}
