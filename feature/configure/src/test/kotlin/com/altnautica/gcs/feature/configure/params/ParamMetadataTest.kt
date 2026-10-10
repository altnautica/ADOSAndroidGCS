package com.altnautica.gcs.feature.configure.params

import com.altnautica.gcs.core.model.FcVariant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException

class ParamMetadataTest {
    private val repo = ParamMetadataRepository { key ->
        val f = File("src/main/assets/param-metadata/$key.json.gz")
        if (!f.exists()) throw FileNotFoundException(key)
        f.inputStream()
    }

    @Test
    fun bundledCopterFileParsesWithEditorsAndGroups() = runTest {
        val set = repo.load("ardupilot-copter")!!
        assertEquals("ardupilot-copter", set.firmware)
        assertTrue(set.params.size > 1000)
        val acro = set["ACRO_OPTIONS"]!!
        assertEquals(ParamEditorKind.Bitmask, acro.editor)
        assertEquals("ACRO", acro.group)
        val rate = set["ACRO_RP_RATE"]!!
        assertEquals(1.0, rate.min!!, 0.0)
        assertEquals(1080.0, rate.max!!, 0.0)
        assertEquals("deg/s", rate.units)
    }

    @Test
    fun px4UsesItsDeclaredGroupAndType() = runTest {
        val set = repo.load("px4")!!
        val p = set["ADSB_EMERGC"]!!
        assertEquals("ADSB", p.group)
        assertEquals("int32", p.valueType)
        assertTrue(p.isInteger)
    }

    @Test
    fun missingFileIsNull() = runTest {
        assertNull(repo.load("nope"))
    }

    @Test
    fun packagedAssetsAreReadPlainUnderTheNameWithoutGz() = runTest {
        // The APK carries `param-metadata/<key>.json` gunzipped; the old `.json.gz` lookup found nothing on device.
        var asked: String? = null
        val packaged = ParamMetadataRepository { key ->
            asked = key
            java.util.zip.GZIPInputStream(File("src/main/assets/param-metadata/$key.json.gz").inputStream())
        }
        val key = ParamMetadataKey.forVehicle(FcVariant.MavlinkArdupilot, 20)!! // quadplane tailsitter
        val set = packaged.load(key)!!
        assertEquals("ardupilot-plane", asked)
        assertEquals("ardupilot-plane", set.firmware)
        assertTrue(set.params.size > 1000)
    }

    @Test
    fun keySelectsFirmwareByVehicleType() {
        assertEquals("ardupilot-plane", ParamMetadataKey.forVehicle(FcVariant.MavlinkArdupilot, 1))
        assertEquals("ardupilot-rover", ParamMetadataKey.forVehicle(FcVariant.MavlinkArdupilot, 10))
        assertEquals("ardupilot-sub", ParamMetadataKey.forVehicle(FcVariant.MavlinkArdupilot, 12))
        assertEquals("ardupilot-copter", ParamMetadataKey.forVehicle(FcVariant.MavlinkArdupilot, 2))
        assertEquals("px4", ParamMetadataKey.forVehicle(FcVariant.MavlinkPx4, 2))
        assertNull(ParamMetadataKey.forVehicle(FcVariant.Msp, 2))
    }

    @Test
    fun parsesInlineDocument() {
        val set = ParamMetadataParser.parse(
            """{"provenance":{"firmware":"x"},"params":[{"name":"FS_OPT","humanName":"","description":"d",
               "values":[[0,"Off"],[1,"On"]]}]}""",
        )
        val p = set["FS_OPT"]!!
        assertEquals("FS_OPT", p.humanName)
        assertEquals(ParamEditorKind.Enum, p.editor)
        assertEquals(listOf(ParamOption(0.0, "Off"), ParamOption(1.0, "On")), p.options)
    }
}
