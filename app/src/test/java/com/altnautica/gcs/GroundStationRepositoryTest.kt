package com.altnautica.gcs

import com.altnautica.gcs.data.groundstation.ApConfig
import com.altnautica.gcs.data.groundstation.ApUpdate
import com.altnautica.gcs.data.groundstation.GroundStationApi
import com.altnautica.gcs.data.groundstation.GroundStationRepository
import com.altnautica.gcs.data.groundstation.NetworkConfig
import com.altnautica.gcs.data.groundstation.StationStatus
import com.altnautica.gcs.data.groundstation.WfbConfig
import com.altnautica.gcs.data.groundstation.WfbUpdate
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class GroundStationRepositoryTest {

    private lateinit var api: GroundStationApi
    private lateinit var repository: GroundStationRepository

    @Before
    fun setup() {
        api = mockk(relaxed = true)
        repository = GroundStationRepository(api)
    }

    @Test
    fun `fetchWfb propagates result and updates flow`() = runTest {
        val expected = WfbConfig(channel = 149, bitrateProfile = "high", fec = "8/12")
        coEvery { api.getWfb() } returns expected

        val result = repository.fetchWfb()

        assertTrue(result.isSuccess)
        assertEquals(expected, result.getOrNull())
        assertEquals(expected, repository.wfb.value)
    }

    @Test
    fun `updateWfb hits PUT endpoint and refreshes flow`() = runTest {
        val update = WfbUpdate(channel = 161)
        val applied = WfbConfig(channel = 161, bitrateProfile = "default", fec = "8/12")
        coEvery { api.putWfb(update) } returns applied

        val result = repository.updateWfb(update)

        assertTrue(result.isSuccess)
        assertEquals(161, repository.wfb.value.channel)
        coVerify(exactly = 1) { api.putWfb(update) }
    }

    @Test
    fun `fetchNetwork updates network flow`() = runTest {
        val expected = NetworkConfig(activeUplink = "ethernet", priority = listOf("ethernet", "wifi_client"))
        coEvery { api.getNetwork() } returns expected

        val result = repository.fetchNetwork()

        assertTrue(result.isSuccess)
        assertEquals("ethernet", repository.network.value.activeUplink)
    }

    @Test
    fun `updateNetworkAp returns body on success`() = runTest {
        val update = ApUpdate(ssid = "ADOS-GS-LAB", channel = 6)
        val body = ApConfig(enabled = true, running = true, ssid = "ADOS-GS-LAB", channel = 6)
        coEvery { api.putNetworkAp(update) } returns Response.success(body)

        val result = repository.updateNetworkAp(update)

        assertTrue(result.isSuccess)
        assertEquals("ADOS-GS-LAB", result.getOrNull()?.ssid)
    }

    @Test
    fun `updateNetworkAp surfaces failure on non-200`() = runTest {
        val update = ApUpdate(enabled = true)
        coEvery { api.putNetworkAp(update) } returns Response.error(500, okhttp3.ResponseBody.create(null, ""))

        val result = repository.updateNetworkAp(update)

        assertTrue(result.isFailure)
    }

    @Test
    fun `recording stubs return failure with not-implemented marker`() = runTest {
        val start = repository.startRecording()
        val stop = repository.stopRecording()
        val cam = repository.switchCamera("cam1")
        val reboot = repository.reboot()
        val ota = repository.pushOta("https://example.com/fw.bin", "1.0.0")

        for (r in listOf(start, stop, cam, reboot, ota)) {
            assertTrue(r.isFailure)
            assertNotNull(r.exceptionOrNull())
            assertTrue(r.exceptionOrNull() is UnsupportedOperationException)
        }
    }

    @Test
    fun `default flows expose empty status`() {
        assertEquals(StationStatus(), repository.status.value)
        assertFalse(repository.reachable.value)
    }
}
