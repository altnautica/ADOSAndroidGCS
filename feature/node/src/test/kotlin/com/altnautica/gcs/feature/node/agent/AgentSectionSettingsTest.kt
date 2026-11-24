package com.altnautica.gcs.feature.node.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** An alert's Open settings lands on the Agent page section that holds those settings. */
class AgentSectionSettingsTest {
    @Test
    fun visionOpensVideoAndVision() = assertEquals("videoVision", AgentSection.forSettings("vision"))

    @Test
    fun aSectionOfTheSameIdOpensItself() = assertEquals("cloud", AgentSection.forSettings("cloud"))

    @Test
    fun aSectionThisPageLacksOpensTheList() = assertNull(AgentSection.forSettings("sensors"))
}
