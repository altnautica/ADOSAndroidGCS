package com.altnautica.gcs.feature.cockpit.chrome

import com.altnautica.gcs.core.model.ParamDoc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Parameter values, their labels and why-lines as the alert cards write them. */
class ParamTextTest {
    private fun doc(units: String? = null, options: Map<Double, String> = emptyMap(), bits: Map<Int, String> = emptyMap()) =
        ParamDoc("TEST_PARAM", "Test parameter", "A parameter for tests.", units, options, bits, rebootRequired = false)

    @Test
    fun `a value carries its units`() {
        assertEquals("5 s", ParamText.value(5.0, doc(units = "s")))
        assertEquals("0.2", ParamText.value(0.2, doc()))
        assertEquals("5", ParamText.value(5.0, null))
    }

    @Test
    fun `an enumerated value reads as its option label`() {
        assertEquals("SmartRTL or Land", ParamText.label(4.0, doc(options = mapOf(4.0 to "SmartRTL or Land"))))
        assertNull(ParamText.label(4.0, null))
        assertNull(ParamText.label(null, doc(options = mapOf(4.0 to "SmartRTL or Land"))))
    }

    @Test
    fun `a bitmask value reads as its set bits`() {
        assertEquals("A, B", ParamText.label(3.0, doc(bits = mapOf(0 to "A", 1 to "B"))))
        assertEquals("B", ParamText.label(2.0, doc(bits = mapOf(0 to "A", 1 to "B"))))
        assertNull(ParamText.label(0.0, doc(bits = mapOf(0 to "A", 1 to "B"))))
    }

    @Test
    fun `only a single number is a wanted value`() {
        assertNull(ParamText.wantValue("0.15 to 0.2"))
        assertNull(ParamText.wantValue("bits 3 set"))
        assertEquals(4.0, ParamText.wantValue(" 4 ")!!, 0.0)
    }

    @Test
    fun `alternative names share a why-line`() {
        assertNotNull(ParamText.why("MAV_GCS_SYSID"))
        assertEquals(ParamText.why("SYSID_MYGCS"), ParamText.why("MAV_GCS_SYSID"))
        assertEquals(ParamText.why("PRX_TYPE"), ParamText.why("PRX1_TYPE"))
        assertNull(ParamText.why("NOT_A_PARAM"))
    }
}
