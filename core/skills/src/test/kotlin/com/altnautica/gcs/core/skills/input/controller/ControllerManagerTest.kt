package com.altnautica.gcs.core.skills.input.controller

import com.altnautica.gcs.core.data.controller.ControllerSettings
import com.altnautica.gcs.core.skills.input.AdosInputRouter
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ControllerManagerTest {
    private val activeId = MutableStateFlow<Int?>(null)
    private val router = mockk<AdosInputRouter>(relaxed = true) { every { activeDeviceId } returns activeId }

    private fun info(id: Int, vendor: Int, product: Int) =
        ControllerInfo(id, "pad $id", vendor, product, controllerFamily(vendor), batteryPct = null, charging = false)

    private fun TestScope.manager(devices: () -> List<ControllerInfo>, settings: ControllerSettings = ControllerSettings()) =
        ControllerManager(router, MutableStateFlow(settings), backgroundScope, devices, platform = null)

    @Test
    fun `family comes from the vendor id and the DualShock 4 from its product ids`() {
        val ds4 = info(1, PLAYSTATION_VENDOR_ID, 0x09CC)
        val otherPlayStation = info(2, PLAYSTATION_VENDOR_ID, 0x0CE6)
        val generic = info(3, 0x1234, 0x09CC)
        assertEquals(ControllerFamily.PlayStation, ds4.family)
        assertTrue(ds4.isDualShock4())
        assertEquals(ControllerFamily.PlayStation, otherPlayStation.family)
        assertFalse(otherPlayStation.isDualShock4())
        assertEquals(ControllerFamily.Generic, generic.family)
        assertFalse(generic.isDualShock4())
        DUALSHOCK4_PRODUCT_IDS.forEach { assertTrue(info(4, PLAYSTATION_VENDOR_ID, it).isDualShock4()) }
    }

    @Test
    fun `active is the router's active device, otherwise the first controller`() = runTest(UnconfinedTestDispatcher()) {
        val devices = listOf(info(1, 0x1234, 0x0001), info(2, PLAYSTATION_VENDOR_ID, 0x05C4))
        val m = manager({ devices })
        assertEquals(1, m.active.value?.deviceId)
        activeId.value = 2
        runCurrent()
        assertEquals(ControllerFamily.PlayStation, m.active.value?.family)
        activeId.value = 9
        runCurrent()
        assertEquals(1, m.active.value?.deviceId)
    }

    @Test
    fun `refresh picks up added and removed controllers`() = runTest(UnconfinedTestDispatcher()) {
        var devices = emptyList<ControllerInfo>()
        val m = manager({ devices })
        assertEquals(null, m.active.value)
        devices = listOf(info(5, PLAYSTATION_VENDOR_ID, 0x0BA0))
        m.refresh()
        runCurrent()
        assertEquals(listOf(5), m.controllers.value.map { it.deviceId })
        assertEquals(5, m.active.value?.deviceId)
    }
}
