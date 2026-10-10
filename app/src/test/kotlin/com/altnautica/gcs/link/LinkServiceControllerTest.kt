package com.altnautica.gcs.link

import com.altnautica.gcs.core.link.vehicle.OpenLink
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.Reach
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LinkServiceControllerTest {

    private class Recorder : LinkServiceStarter {
        val calls = mutableListOf<String>()
        override fun start() {
            calls += "start"
        }

        override fun stop() {
            calls += "stop"
        }
    }

    private val a = OpenLink(DeviceId("a"), Reach.Lan)
    private val b = OpenLink(DeviceId("b"), Reach.DirectFc)

    @Test
    fun startsOnFirstLinkStopsOnLastAndNeverAtBoot() = runTest(UnconfinedTestDispatcher()) {
        val links = MutableStateFlow<List<OpenLink>>(emptyList())
        val recorder = Recorder()
        LinkServiceController(links, recorder).start(backgroundScope)
        assertEquals(emptyList<String>(), recorder.calls)

        links.value = listOf(a)
        links.value = listOf(a, b)
        links.value = listOf(b)
        assertEquals(listOf("start"), recorder.calls)

        links.value = emptyList()
        assertEquals(listOf("start", "stop"), recorder.calls)

        links.value = listOf(b)
        assertEquals(listOf("start", "stop", "start"), recorder.calls)
    }

    @Test
    fun alreadyOpenAtStartStartsOnce() = runTest(UnconfinedTestDispatcher()) {
        val recorder = Recorder()
        LinkServiceController(MutableStateFlow(listOf(a)), recorder).start(backgroundScope)
        assertEquals(listOf("start"), recorder.calls)
    }

    @Test
    fun noticeTextPerLinkCount() {
        assertEquals(LinkNotice.Starting, LinkNotice.from(emptyList()))
        assertEquals(
            LinkNotice.Single("Drone", "LOITER", null),
            LinkNotice.from(listOf(LinkLine(DeviceId("a"), "Drone", "LOITER", -1))),
        )
        assertEquals(
            LinkNotice.Many(2, listOf("A", "B")),
            LinkNotice.from(listOf(LinkLine(DeviceId("a"), "A", null, 50), LinkLine(DeviceId("b"), "B", null, null))),
        )
    }
}
