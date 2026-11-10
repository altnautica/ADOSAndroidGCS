package com.altnautica.gcs.core.skills.hud

import com.altnautica.gcs.core.skills.testSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkLossTest {
    private val text = SpecText(testSpec.setupStrings)

    @Test
    fun `the countdown names the configured action, never the raw id`() {
        assertEquals("Link lost: Return home in 5 s", text.linkLossLine(4.2f, "rth"))
        assertEquals("Link lost: Return home in 3 s", text.linkLossLine(3f, "rtl"))
        assertEquals("Link lost: Land in 1 s", text.linkLossLine(0.5f, "land"))
        assertEquals("Link lost: Hold in 2 s", text.linkLossLine(2f, "loiter"))
        assertEquals("Link lost: — in 2 s", text.linkLossLine(2f, "smart_thing"))
        assertEquals("Link lost: — in 2 s", text.linkLossLine(2f, null))
        assertNull(text.linkLossLine(null, "rth"))
    }
}
