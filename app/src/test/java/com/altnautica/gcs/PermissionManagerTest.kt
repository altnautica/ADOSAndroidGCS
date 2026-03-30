package com.altnautica.gcs

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import com.altnautica.gcs.util.PermissionManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PermissionManagerTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        mockkStatic(androidx.core.content.ContextCompat::class)
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    @Test
    fun `hasLocationPermission returns true when granted`() {
        every {
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        } returns PackageManager.PERMISSION_GRANTED

        assertTrue(PermissionManager.hasLocationPermission(context))
    }

    @Test
    fun `hasLocationPermission returns false when denied`() {
        every {
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        } returns PackageManager.PERMISSION_DENIED

        assertFalse(PermissionManager.hasLocationPermission(context))
    }
}
