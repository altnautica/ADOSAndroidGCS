package com.altnautica.gcs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.altnautica.gcs.data.settings.BaseUrlProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class BaseUrlProviderTest {

    private lateinit var tempFile: File
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var provider: BaseUrlProvider

    @Before
    fun setup() {
        tempFile = File.createTempFile("test_settings_", ".preferences_pb")
        tempFile.delete()
        dataStore = PreferenceDataStoreFactory.create(produceFile = { tempFile })
        provider = BaseUrlProvider(dataStore)
    }

    @After
    fun teardown() {
        tempFile.delete()
    }

    @Test
    fun `default url is the AP fallback`() {
        assertEquals("http://192.168.4.1:8080/", provider.getBaseUrlBlocking())
    }

    @Test
    fun `setBaseUrl persists across reads`() = runTest {
        val updated = "http://10.0.0.5:8080/"
        val ok = provider.setBaseUrl(updated)
        assertTrue(ok)
        assertEquals(updated, provider.getBaseUrlBlocking())
    }

    @Test
    fun `setBaseUrl rejects invalid scheme`() = runTest {
        val ok = provider.setBaseUrl("ftp://example.com/")
        assertFalse(ok)
        assertEquals(BaseUrlProvider.DEFAULT_BASE_URL, provider.getBaseUrlBlocking())
    }

    @Test
    fun `setBaseUrl rejects missing trailing slash`() = runTest {
        val ok = provider.setBaseUrl("http://10.0.0.5:8080")
        assertFalse(ok)
    }

    @Test
    fun `setBaseUrl rejects empty host`() = runTest {
        val ok = provider.setBaseUrl("http:///path/")
        assertFalse(ok)
    }

    @Test
    fun `toMavlinkWsUrl swaps http to ws`() {
        val ws = BaseUrlProvider.toMavlinkWsUrl("http://10.0.0.5:8080/")
        assertEquals("ws://10.0.0.5:8080/api/v1/ground-station/ws/mavlink", ws)
    }

    @Test
    fun `toMavlinkWsUrl swaps https to wss`() {
        val ws = BaseUrlProvider.toMavlinkWsUrl("https://gs.example.com/")
        assertEquals("wss://gs.example.com/api/v1/ground-station/ws/mavlink", ws)
    }

    @Test
    fun `toMavlinkWsUrl appends trailing slash if missing`() {
        // The function tolerates a missing trailing slash though setBaseUrl does not.
        val ws = BaseUrlProvider.toMavlinkWsUrl("http://10.0.0.5:8080")
        assertEquals("ws://10.0.0.5:8080/api/v1/ground-station/ws/mavlink", ws)
    }
}
