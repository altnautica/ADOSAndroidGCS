package com.altnautica.gcs.feature.settings.licences

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every component in the notices links a bundled licence text, and vendored copies match their source. */
class LicenceTextsTest {
    private val root = File("../..")
    private val assetDirs = listOf(File("src/main/assets"), File(root, "core/designsystem/src/main/assets"))

    private fun assetExists(path: String) = assetDirs.any { File(it, path).isFile }

    @Test
    fun everyNoticesRowHasAnExistingLicenceText() {
        val blocks = NoticesMarkdown.parse(File(root, "THIRD_PARTY_NOTICES.md").readText())
        val rows = blocks.filterIsInstance<NoticeBlock.Table>().flatMap { it.rows }
        assertTrue(rows.isNotEmpty())
        rows.forEach { row ->
            val assets = licenceAssetsFor(row)
            assertTrue("no licence text for ${row.first()}", assets.isNotEmpty())
            assets.forEach { assertTrue("missing asset $it", assetExists(it)) }
        }
        assertTrue(assetExists(APP_LICENCE_ASSET))
    }

    @Test
    fun vendoredLicenceCopiesMatchTheirSource() {
        mapOf(
            "LICENSE" to "ados-gpl-3.0.txt",
            "radio/usb/src/main/cpp/vendor/devourer/LICENSE" to "devourer-gpl-2.0.txt",
            "radio/usb/src/main/cpp/vendor/libusb/COPYING" to "libusb-lgpl-2.1.txt",
            "radio/rx/src/main/cpp/vendor/wfb-ng/LICENSE.txt" to "wfb-ng-gpl-3.0.txt",
            "radio/rx/src/main/cpp/vendor/libsodium/LICENSE" to "libsodium-isc.txt",
            "core/mavlink/definitions/COPYING" to "mavlink.txt",
        ).forEach { (source, copy) ->
            assertEquals(copy, File(root, source).readText(), File("src/main/assets/licenses/$copy").readText())
        }
    }
}
