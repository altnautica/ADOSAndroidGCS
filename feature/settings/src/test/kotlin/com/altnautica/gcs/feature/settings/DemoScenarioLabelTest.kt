package com.altnautica.gcs.feature.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altnautica.gcs.core.demo.DemoScenario
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The demo flight-state picker shows translatable labels, never enum names. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DemoScenarioLabelTest {
    @Test
    fun `every scenario has a resource label`() {
        val res = ApplicationProvider.getApplicationContext<Context>().resources
        DemoScenario.entries.forEach { s ->
            val label = res.getString(s.label)
            assertTrue(label.isNotBlank())
            assertNotEquals(s.name, label)
        }
    }
}
