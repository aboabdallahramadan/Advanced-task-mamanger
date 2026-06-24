package net.qmindtech.tmap.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EntityTypeTest {

    @Test
    fun `the four new synced domains are present alongside the originals`() {
        val names = EntityType.entries.map { it.name }.toSet()
        assertTrue(names.containsAll(setOf("TASK", "SUBTASK", "PROJECT", "SETTINGS")))
        assertTrue(names.containsAll(setOf("NOTE", "NOTE_GROUP", "FOCUS_SESSION", "DAILY_PLAN")))
    }

    @Test
    fun `valueOf round-trips each new domain by name`() {
        assertEquals(EntityType.NOTE, EntityType.valueOf("NOTE"))
        assertEquals(EntityType.NOTE_GROUP, EntityType.valueOf("NOTE_GROUP"))
        assertEquals(EntityType.FOCUS_SESSION, EntityType.valueOf("FOCUS_SESSION"))
        assertEquals(EntityType.DAILY_PLAN, EntityType.valueOf("DAILY_PLAN"))
    }
}
