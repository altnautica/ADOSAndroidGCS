package com.altnautica.gcs.feature.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EndpointValidationTest {

    @Test
    fun ports() {
        assertNull(EndpointValidation.port("14550"))
        assertNull(EndpointValidation.port(" 1 "))
        assertEquals(InputError.Required, EndpointValidation.port(""))
        assertEquals(InputError.InvalidPort, EndpointValidation.port("0"))
        assertEquals(InputError.InvalidPort, EndpointValidation.port("65536"))
        assertEquals(InputError.InvalidPort, EndpointValidation.port("abc"))
    }

    @Test
    fun hosts() {
        assertNull(EndpointValidation.host("192.168.1.50"))
        assertNull(EndpointValidation.host("192.168.1.50:8080"))
        assertNull(EndpointValidation.host("ados-drone.local"))
        assertNull(EndpointValidation.host("fe80::1"))
        assertNull(EndpointValidation.host("[fe80::1]:8080"))
        assertEquals(InputError.Required, EndpointValidation.host("  "))
        assertEquals(InputError.InvalidHost, EndpointValidation.host("http://192.168.1.50"))
        assertEquals(InputError.InvalidHost, EndpointValidation.host("192.168.1.300"))
        assertEquals(InputError.InvalidHost, EndpointValidation.host("host:99999"))
        assertEquals(InputError.InvalidHost, EndpointValidation.host("bad host"))
        assertEquals(InputError.InvalidHost, EndpointValidation.host("-bad.local"))
    }

    @Test
    fun tcpHostRejectsPortSuffix() {
        assertEquals(InputError.InvalidHost, EndpointValidation.host("192.168.1.50:5760", allowPort = false))
        assertNull(EndpointValidation.host("192.168.1.50", allowPort = false))
    }

    @Test
    fun codes() {
        assertNull(EndpointValidation.code("abcd-2345"))
        assertEquals(InputError.Required, EndpointValidation.code(""))
        // I, O, 0 and 1 are outside the code alphabet.
        assertEquals(InputError.InvalidCode, EndpointValidation.code("IO01"))
    }

    @Test
    fun apiKeys() {
        assertNull(EndpointValidation.apiKey(""))
        assertNull(EndpointValidation.apiKey("0123456789abcdef0123"))
        assertEquals(InputError.KeyTooShort, EndpointValidation.apiKey("short"))
    }
}
