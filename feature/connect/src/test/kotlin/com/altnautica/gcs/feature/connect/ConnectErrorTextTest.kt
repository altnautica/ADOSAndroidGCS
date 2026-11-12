package com.altnautica.gcs.feature.connect

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectErrorTextTest {

    @Test
    fun `an agent's own server message is never shown raw`() {
        assertEquals(R.string.connect_error_other, connectErrorText(ConnectError.Other("HTTP 500: internal pairing table locked")))
        assertEquals(R.string.connect_error_other, connectErrorText(ConnectError.Other(null)))
    }
}
