package com.altnautica.gcs.core.skills.spec

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Every JSON file shipped under `assets/cockpit-spec/` is registered, so none ships unread. */
class CockpitSpecFilesTest {
    private val dir = File("../../feature/cockpit/src/main/assets/cockpit-spec")

    @Test
    fun `every shipped spec json file has a CockpitSpecFile entry`() {
        val shipped = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty().map { it.name }.toSortedSet()
        val registered = CockpitSpecFile.entries.map { it.fileName }.toSortedSet()
        assertEquals(shipped, registered)
    }
}
