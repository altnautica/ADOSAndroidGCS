package com.altnautica.gcs.core.agent

import com.altnautica.gcs.core.agent.model.StatusFull
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RadioFleetApplyTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun status(outcome: String) = json.decodeFromString(
        StatusFull.serializer(),
        """{"radioFleetApply":{"outcome":"$outcome","fleetId":7,"fleetSlot":3,"unit":"ados-wfb-drone","sinceMs":10,"atMs":20}}""",
    )

    @Test
    fun `a delivered slot waiting for landing and a failed restart are read from the full status`() {
        assertEquals(3, status("deferred_armed").radioFleetApply?.waitingSlot)
        assertNull(status("deferred_armed").radioFleetApply?.failedSlot)
        assertEquals(3, status("restart_failed").radioFleetApply?.failedSlot)
        assertNull(status("applied").radioFleetApply?.waitingSlot)
        assertNull(status("pending").radioFleetApply?.waitingSlot)
        assertNull(json.decodeFromString(StatusFull.serializer(), "{}").radioFleetApply)
    }
}
