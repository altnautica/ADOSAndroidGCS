package com.altnautica.gcs.core.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PropertyCacheTest {
    @Test
    fun `each property is read once, unset ones included`() {
        val reads = mutableListOf<String>()
        val cache = PropertyCache { name ->
            reads += name
            when (name) {
                "set" -> "1"
                "empty" -> ""
                else -> null
            }
        }
        repeat(3) {
            assertEquals("1", cache.get("set"))
            assertNull(cache.get("empty"))
            assertNull(cache.get("unreadable"))
        }
        assertEquals(listOf("set", "empty", "unreadable"), reads)
    }
}
