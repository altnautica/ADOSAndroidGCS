package com.altnautica.gcs.link

import com.altnautica.gcs.core.skills.builtin.BuiltinIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The link notification's Pause, Land and Return run the cockpit's pause, land and rth built-ins, labelled from the spec. */
class StopActionTest {
    @Test
    fun `each notification action runs its built-in and reads its spec label`() {
        assertEquals(listOf(BuiltinIds.PAUSE, BuiltinIds.LAND, BuiltinIds.RTH), StopAction.entries.map { it.builtinId })
        assertEquals(listOf("shell.notify.pause", "shell.notify.land", "shell.notify.return"), StopAction.entries.map { it.labelKey })
        StopAction.entries.forEach { assertEquals(it, StopAction.of(it.intentAction)) }
        assertNull(StopAction.of(LinkService.ACTION_DISCONNECT))
        assertNull(StopAction.of(null))
    }
}
