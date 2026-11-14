package com.altnautica.gcs.feature.configure.params

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

/** FLOW_ORIENT_YAW is centidegrees, -17999..18000 in steps of 10, with or without bundled documentation. */
class FlowOrientYawValidatorTest {
    private val copter: ParamMeta? = GZIPInputStream(File("src/main/assets/param-metadata/ardupilot-copter.json.gz").inputStream())
        .bufferedReader().use { ParamMetadataParser.parse(it.readText()) }["FLOW_ORIENT_YAW"]

    private fun reason(meta: ParamMeta?, input: String): String? =
        (ParamValidator.validate("FLOW_ORIENT_YAW", meta, input) as? ParamValidation.Invalid)?.reason?.name

    @Test
    fun `the bundled documentation names centidegrees`() {
        assertEquals("cdeg", copter?.units)
        assertEquals(-17999.0, copter?.min)
        assertEquals(18000.0, copter?.max)
    }

    @Test
    fun `whole multiples of 10 in range are accepted`() {
        for (meta in listOf(copter, null)) {
            assertEquals(ParamValidation.Valid(18000.0), ParamValidator.validate("FLOW_ORIENT_YAW", meta, "18000"))
            assertEquals(ParamValidation.Valid(-9000.0), ParamValidator.validate("FLOW_ORIENT_YAW", meta, "-9000"))
            assertEquals(ParamValidation.Valid(-17990.0), ParamValidator.validate("FLOW_ORIENT_YAW", meta, "-17990"))
        }
    }

    @Test
    fun `off-step, fractional and out-of-range yaws are refused`() {
        for (meta in listOf(copter, null)) {
            assertEquals("NotOnStep", reason(meta, "17995"))
            assertEquals("NotOnStep", reason(meta, "-17999"))
            assertEquals("NotInteger", reason(meta, "90.5"))
            assertEquals("AboveMax", reason(meta, "18010"))
            assertEquals("BelowMin", reason(meta, "-18000"))
            // Degrees typed by mistake still pass the range; 180 is a valid 1.8 degree yaw.
            assertEquals(null, reason(meta, "180"))
        }
    }
}
