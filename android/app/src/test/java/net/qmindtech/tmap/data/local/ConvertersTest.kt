package net.qmindtech.tmap.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class ConvertersTest {

    private val c = Converters()

    @Test
    fun `LocalDate round-trips as ISO_LOCAL_DATE`() {
        val d = LocalDate.of(2026, 6, 18)
        val s = c.fromLocalDate(d)
        assertEquals("2026-06-18", s)
        assertEquals(d, c.toLocalDate(s))
    }

    @Test
    fun `null LocalDate round-trips as null`() {
        assertNull(c.fromLocalDate(null))
        assertNull(c.toLocalDate(null))
    }

    @Test
    fun `Instant round-trips as ISO-8601`() {
        val i = Instant.parse("2026-06-18T09:30:00Z")
        val s = c.fromInstant(i)
        assertEquals("2026-06-18T09:30:00Z", s)
        assertEquals(i, c.toInstant(s))
    }

    @Test
    fun `null Instant round-trips as null`() {
        assertNull(c.fromInstant(null))
        assertNull(c.toInstant(null))
    }

    @Test
    fun `List of String round-trips through JSON`() {
        val labels = listOf("work", "حجوزات", "p1")
        val s = c.fromStringList(labels)
        assertEquals(labels, c.toStringList(s))
    }

    @Test
    fun `empty and single-element lists round-trip`() {
        assertEquals(emptyList<String>(), c.toStringList(c.fromStringList(emptyList())))
        assertEquals(listOf("only"), c.toStringList(c.fromStringList(listOf("only"))))
    }

    @Test
    fun `TaskStatus round-trips as canonical PascalCase`() {
        assertEquals("Scheduled", c.fromTaskStatus(TaskStatus.Scheduled))
        assertEquals(TaskStatus.Scheduled, c.toTaskStatus("Scheduled"))
    }

    @Test
    fun `TaskStatus read path tolerates non-canonical casing and defaults to Inbox`() {
        assertEquals(TaskStatus.Done, c.toTaskStatus("done"))
        assertEquals(TaskStatus.Inbox, c.toTaskStatus("garbage"))
    }
}
