package com.altnautica.gcs.core.extensions.data

import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.FcVariant
import com.altnautica.gcs.core.model.Liveness
import com.altnautica.gcs.core.model.NodeProfile
import com.altnautica.gcs.core.model.NodeSource
import com.altnautica.gcs.core.model.NodeSummary
import com.altnautica.gcs.core.model.Reach
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class MergeSourcesTest {

    private val drone = DeviceId("drone-1")

    @Test
    fun `a source that has not reported yet does not hide the others`() = runTest {
        val lan = object : NodeSource {
            override val nodes = flowOf(
                listOf(NodeSummary(drone, "Drone", NodeProfile.Drone, listOf(Reach.Lan), Liveness.Live, null, FcVariant.MavlinkArdupilot, null, null)),
            )
        }
        val silent = object : NodeSource { override val nodes = flow<List<NodeSummary>> { awaitCancellation() } }

        val merged = ExtensionRepository.mergeSources(setOf(lan, silent)).first { it.isNotEmpty() }

        assertEquals(setOf(drone), merged.keys)
    }
}
