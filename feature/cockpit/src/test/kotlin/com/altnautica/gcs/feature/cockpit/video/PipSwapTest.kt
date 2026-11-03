package com.altnautica.gcs.feature.cockpit.video

import com.altnautica.gcs.core.model.StreamDescriptor
import com.altnautica.gcs.core.model.StreamSwitchKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PipSwapTest {
    private fun leg(id: String, live: Boolean = true) = StreamDescriptor(id, "primary", "h264", live, StreamSwitchKind.Concurrent)

    private val legs = listOf(leg("main"), leg("aux"), leg("belly"))

    @Test
    fun `after a swap the old main leg goes into the PiP`() {
        // PiP showed belly over main; swapping makes belly main and main the PiP, not the first other leg.
        assertEquals("main", pickPip(legs, selected = "belly", preferred = "main")?.id)
    }

    @Test
    fun `without a swap the PiP takes the first other live concurrent leg`() {
        assertEquals("aux", pickPip(legs, selected = "main", preferred = null)?.id)
    }

    @Test
    fun `a preferred leg that is not live falls back`() {
        assertEquals("aux", pickPip(listOf(leg("main", live = false), leg("aux"), leg("belly")), "belly", "main")?.id)
        assertNull(pickPip(listOf(leg("main")), "main", null))
    }
}
