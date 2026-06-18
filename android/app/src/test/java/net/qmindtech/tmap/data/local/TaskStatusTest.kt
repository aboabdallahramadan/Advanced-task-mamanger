package net.qmindtech.tmap.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaskStatusTest {

    @Test
    fun `parse is exact for canonical PascalCase`() {
        assertEquals(TaskStatus.Inbox, TaskStatus.parse("Inbox"))
        assertEquals(TaskStatus.Backlog, TaskStatus.parse("Backlog"))
        assertEquals(TaskStatus.Planned, TaskStatus.parse("Planned"))
        assertEquals(TaskStatus.Scheduled, TaskStatus.parse("Scheduled"))
        assertEquals(TaskStatus.Done, TaskStatus.parse("Done"))
        assertEquals(TaskStatus.Archived, TaskStatus.parse("Archived"))
    }

    @Test
    fun `parse is case-insensitive`() {
        assertEquals(TaskStatus.Inbox, TaskStatus.parse("inbox"))
        assertEquals(TaskStatus.Scheduled, TaskStatus.parse("SCHEDULED"))
        assertEquals(TaskStatus.Done, TaskStatus.parse("dOnE"))
    }

    @Test
    fun `parse trims surrounding whitespace`() {
        assertEquals(TaskStatus.Backlog, TaskStatus.parse("  Backlog  "))
    }

    @Test
    fun `parse returns null for null`() {
        assertNull(TaskStatus.parse(null))
    }

    @Test
    fun `parse returns null for unrecognized or blank input`() {
        assertNull(TaskStatus.parse("weird"))
        assertNull(TaskStatus.parse(""))
        assertNull(TaskStatus.parse("   "))
    }
}
