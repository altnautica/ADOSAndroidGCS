package com.altnautica.gcs.feature.cockpit

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The cockpit's menus and sheets draw in the app window (the overlay host), so the
 * activity input router and the D-pad focus trap reach them. Popup-window Material
 * components would escape both.
 */
class InWindowOverlaySweepTest {
    @Test
    fun `no popup-window menus, dialogs or sheets in the cockpit sources`() {
        val banned = Regex("""\b(DropdownMenu|ModalBottomSheet|AlertDialog)\(""")
        val hits = File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line -> if (banned.containsMatchIn(line)) "${f.name}:${i + 1}" else null }
        }.toList()
        assertEquals(emptyList<String>(), hits)
    }
}
