package com.altnautica.gcs.feature.cockpit.chrome

import com.altnautica.gcs.core.designsystem.theme.AdosFonts
import com.altnautica.gcs.feature.cockpit.testSpec
import org.junit.Assert.assertEquals
import org.junit.Test

/** Flight-screen chrome text (pill, battery, chip, labels) is set in the design-token sans, not a monospace face. */
class ChromeTypeTest {
    @Test
    fun `every chrome text style uses the token sans family with tabular figures`() {
        val chrome = Chrome.from(testSpec.tokens.chrome)
        for ((name, style) in listOf(
            "topBar" to chrome.topBarText,
            "value" to chrome.valueText,
            "label" to chrome.labelText,
            "chip" to chrome.chipText,
        )) {
            assertEquals(name, AdosFonts.Inter, style.fontFamily)
            assertEquals(name, "tnum", style.fontFeatureSettings)
        }
    }
}
