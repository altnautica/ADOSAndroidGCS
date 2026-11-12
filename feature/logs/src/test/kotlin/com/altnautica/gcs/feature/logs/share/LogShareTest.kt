package com.altnautica.gcs.feature.logs.share

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altnautica.gcs.feature.logs.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class LogShareTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun missingFileSaysSoInsteadOfDoingNothing() {
        val gone = File(context.cacheDir, "deleted.bin")
        assertEquals(ShareOutcome.Missing, shareLogFile(context, gone))
        assertEquals("This log file is no longer on the device", ShadowToast.getTextOfLatestToast())
        assertNull(shadowOf(context as android.app.Application).nextStartedActivity)
    }

    @Test
    fun logsWordingNamesFlightLogsAndTheDrone() {
        assertEquals("Flight logs", context.getString(R.string.logs_surface_title))
        listOf(R.string.logs_df_download_none, R.string.logs_df_no_selection, R.string.logs_empty_message).forEach {
            assertFalse(context.getString(it).contains("vehicle"))
        }
        listOf(R.string.logs_error, R.string.logs_detail_tlog_failed).forEach {
            assertFalse("error text takes no raw detail", context.getString(it).contains("%"))
        }
    }

    @Test
    fun dataflashFailuresShowWordsNotTokens() {
        val res = context.resources
        assertEquals("The log list could not be read: No link to the flight controller", com.altnautica.gcs.feature.logs.dataflash.listFailedText(res, "noFcLink"))
        val stopped = com.altnautica.gcs.feature.logs.dataflash.downloadFailedText(res, "timeout")
        assertFalse(stopped, stopped.contains("timeout"))
        assertEquals("Download stopped. The flight controller did not answer in time. Download again to resume.", stopped)
    }
}
