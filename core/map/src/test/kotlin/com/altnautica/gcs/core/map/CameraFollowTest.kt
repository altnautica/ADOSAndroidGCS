package com.altnautica.gcs.core.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraFollowTest {
    private val home = GeoPoint(47.3977, 8.5456)
    private val vehicle = GeoPoint(47.3980, 8.5460)

    @Test
    fun `nothing moves before a target arrives`() {
        assertNull(CameraFollow().next())
    }

    @Test
    fun `a target requested before the map is ready is applied once it is, at the configured zoom`() {
        val follow = CameraFollow()
        // The parked vehicle reports one position while the map is still loading.
        follow.request(vehicle, 16.0)
        // Map ready: the first move centres and zooms even though the position never changed.
        assertEquals(CameraMove(vehicle, 16.0), follow.next())
        assertNull(follow.next())
    }

    @Test
    fun `home first, then the vehicle pans without resetting the zoom`() {
        val follow = CameraFollow()
        follow.request(home, 16.0)
        assertEquals(CameraMove(home, 16.0), follow.next())
        follow.request(vehicle, 2.0)
        assertEquals(CameraMove(vehicle, null), follow.next())
    }

    @Test
    fun `a new map zooms again`() {
        val follow = CameraFollow()
        follow.request(vehicle, 16.0)
        follow.next()
        follow.reset()
        assertEquals(CameraMove(vehicle, 16.0), follow.next())
    }
}
