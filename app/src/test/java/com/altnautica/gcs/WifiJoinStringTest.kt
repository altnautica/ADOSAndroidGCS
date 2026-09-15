package com.altnautica.gcs

import com.altnautica.gcs.data.wifi.WifiJoinString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The on-box panel renders the AP credential as a WPA join string. Parsing it
 * is the only way an operator gets a per-unit passphrase into this app without
 * transcribing it, so a parse that succeeds but yields the wrong passphrase is
 * worse than one that fails: it looks correct and joins nothing.
 */
class WifiJoinStringTest {

    @Test
    fun `parses ssid and passphrase from a wpa join string`() {
        val parsed = WifiJoinString.parse("WIFI:T:WPA;S:ADOS-GS-40BB;P:EXAMPLEPASS99;;")

        assertEquals("EXAMPLEPASS99", parsed?.passphrase)
        assertEquals("ADOS-GS-40BB", parsed?.ssid)
    }

    @Test
    fun `an escaped separator inside the passphrase survives the round trip`() {
        // The node escapes the format's own separators. Splitting naively on
        // ';' truncates the passphrase at the first one.
        val parsed = WifiJoinString.parse("WIFI:T:WPA;S:ADOS-GS-40BB;P:pa\\:ss\\;word;;")

        assertEquals("pa:ss;word", parsed?.passphrase)
    }

    @Test
    fun `an open network is refused rather than treated as a blank passphrase`() {
        // A blank passphrase is the one value the join path must never attempt.
        assertNull(WifiJoinString.parse("WIFI:T:nopass;S:ADOS-GS-40BB;;"))
    }

    @Test
    fun `a string with no passphrase field yields nothing`() {
        assertNull(WifiJoinString.parse("WIFI:T:WPA;S:ADOS-GS-40BB;;"))
    }

    @Test
    fun `text that is not a join string yields nothing`() {
        assertNull(WifiJoinString.parse("EXAMPLEPASS99"))
    }

    @Test
    fun `ssid suffix narrows a prefix match to one unit`() {
        val parsed = WifiJoinString.parse("WIFI:T:WPA;S:ADOS-GS-40BB;P:EXAMPLEPASS99;;")

        assertEquals("40BB", parsed?.ssidSuffix)
    }

    @Test
    fun `a bare passphrase matches any ados ground station`() {
        val parsed = WifiJoinString.parse("WIFI:T:WPA;P:EXAMPLEPASS99;;")

        assertEquals("", parsed?.ssidSuffix)
    }
}
