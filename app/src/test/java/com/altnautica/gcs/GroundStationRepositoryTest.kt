package com.altnautica.gcs

import com.altnautica.gcs.data.groundstation.ApConfig
import com.altnautica.gcs.data.groundstation.ApUpdate
import com.altnautica.gcs.data.groundstation.CameraNotSupportedError
import com.altnautica.gcs.data.groundstation.CameraSwitchRequest
import com.altnautica.gcs.data.groundstation.CameraSwitchResponse
import com.altnautica.gcs.data.groundstation.GroundStationApi
import com.altnautica.gcs.data.groundstation.GroundStationRepository
import com.altnautica.gcs.data.groundstation.NetworkConfig
import com.altnautica.gcs.data.groundstation.RecordingInfo
import com.altnautica.gcs.data.groundstation.RecordingListResponse
import com.altnautica.gcs.data.groundstation.RecordingStartRequest
import com.altnautica.gcs.data.groundstation.RecordingStartResponse
import com.altnautica.gcs.data.groundstation.RecordingStopResponse
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
    fun `startRecording forwards filename hint and returns response`() = runTest {
        val expected = RecordingStartResponse(
            filename = "ados-2026-05-07T1200.mp4",
            startedAt = "2026-05-07T12:00:00+00:00",
            path = "/var/lib/ados/recordings/ados-2026-05-07T1200.mp4",
        )
        coEvery { api.startRecording(RecordingStartRequest("test-flight")) } returns expected

        val result = repository.startRecording("test-flight")

        assertTrue(result.isSuccess)
        assertEquals(expected.filename, result.getOrNull()?.filename)
        coVerify(exactly = 1) { api.startRecording(RecordingStartRequest("test-flight")) }
    }

    @Test
    fun `stopRecording returns duration and size`() = runTest {
        val expected = RecordingStopResponse(
            filename = "ados-2026-05-07T1200.mp4",
            stoppedAt = "2026-05-07T12:01:30+00:00",
            durationSeconds = 90.0f,
            sizeBytes = 12_345_678L,
        )
        coEvery { api.stopRecording() } returns expected

        val result = repository.stopRecording()

        assertTrue(result.isSuccess)
        assertEquals(90.0f, result.getOrNull()?.durationSeconds)
        assertEquals(12_345_678L, result.getOrNull()?.sizeBytes)
    }

    @Test
    fun `listRecordings returns disk listing`() = runTest {
        val expected = RecordingListResponse(
            recording = false,
            currentFilename = null,
            items = listOf(
                RecordingInfo(filename = "a.mp4", sizeBytes = 1024, mtime = 1715000000.0),
                RecordingInfo(filename = "b.mp4", sizeBytes = 2048, mtime = 1715001000.0),
            ),
        )
        coEvery { api.listRecordings() } returns expected

        val result = repository.listRecordings()

        assertTrue(result.isSuccess)
        assertEquals(2, result.getOrNull()?.items?.size)
        assertEquals("a.mp4", result.getOrNull()?.items?.first()?.filename)
    }

    @Test
    fun `switchCamera returns body on multi-camera success`() = runTest {
        val request = CameraSwitchRequest(cameraId = "2")
        val body = CameraSwitchResponse(cameraId = "2", accepted = true, reason = null)
        coEvery { api.switchCamera(request) } returns Response.success(body)

        val result = repository.switchCamera("2")

        assertTrue(result.isSuccess)
        assertEquals("2", result.getOrNull()?.cameraId)
        assertTrue(result.getOrNull()?.accepted == true)
    }

    @Test
    fun `switchCamera surfaces 501 as CameraNotSupportedError`() = runTest {
        val request = CameraSwitchRequest(cameraId = "2")
        coEvery { api.switchCamera(request) } returns
            Response.error(501, okhttp3.ResponseBody.create(null, ""))

        val result = repository.switchCamera("2")

        assertTrue(result.isFailure)
        assertTrue(
            "Expected CameraNotSupportedError, got ${result.exceptionOrNull()}",
            result.exceptionOrNull() is CameraNotSupportedError,
        )
    }

    @Test
    fun `switchCamera surfaces non-501 errors as IllegalStateException`() = runTest {
        val request = CameraSwitchRequest(cameraId = "2")
        coEvery { api.switchCamera(request) } returns
            Response.error(500, okhttp3.ResponseBody.create(null, ""))

        val result = repository.switchCamera("2")

        assertTrue(result.isFailure)
        assertFalse(result.exceptionOrNull() is CameraNotSupportedError)
    }

    @Test
    fun `reboot and pushOta remain stubs`() = runTest {
        val reboot = repository.reboot()
        val ota = repository.pushOta("https://example.com/fw.bin", "1.0.0")

        for (r in listOf(reboot, ota)) {
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
