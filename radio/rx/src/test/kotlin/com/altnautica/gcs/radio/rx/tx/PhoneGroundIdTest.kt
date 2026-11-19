package com.altnautica.gcs.radio.rx.tx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class PhoneGroundIdTest {

    @Test
    fun `a generated id is twelve hex characters, the shape of an agent's id`() {
        val id = PhoneGroundId.generate()
        assertEquals(12, id.length)
        assertTrue(PhoneGroundId.isValid(id))
        assertNotEquals(id, PhoneGroundId.generate(SecureRandom()))
    }

    @Test
    fun `an id fits the presence beacon's sixteen bytes`() {
        assertTrue(PhoneGroundId.generate().toByteArray(Charsets.US_ASCII).size <= 16)
    }

    @Test
    fun `anything else is not an id`() {
        for (bad in listOf("", "ABCDEF012345", "0123456789a", "0123456789abc", "0123456789ag")) {
            assertFalse(bad, PhoneGroundId.isValid(bad))
        }
    }
}
