package com.altnautica.gcs.core.cloud

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Entering a node's pairing code is "Pair" everywhere, as in the other operator apps. */
class CloudWordingTest {

    private val strings: Map<String, String> =
        Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""")
            .findAll(File("src/main/res/values/strings.xml").readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    @Test
    fun pairingActionSaysPair() {
        assertEquals("Pair", strings["cloud_claim"])
        assertEquals("Pairing…", strings["cloud_claiming"])
        assertEquals("Could not pair the node: %1\$s", strings["cloud_claim_failed"])
    }

    @Test
    fun noOperatorTextSaysClaim() {
        val claims = strings.filterValues { it.contains("claim", ignoreCase = true) }
        assertTrue("operator text still says claim: $claims", claims.isEmpty())
    }
}
