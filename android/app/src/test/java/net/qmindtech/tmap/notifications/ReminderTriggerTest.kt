package net.qmindtech.tmap.notifications

import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.local.entities.TaskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class ReminderTriggerTest {

    private val utc = ZoneOffset.UTC

    private fun task(
        id: String = "t1",
        status: TaskStatus = TaskStatus.Scheduled,
        plannedDate: LocalDate? = null,
        scheduledStart: Instant? = null,
        reminderMinutes: Int? = null,
        dueDate: LocalDate? = null,
        completedAt: Instant? = null,
    ) = TaskEntity(
        id = id, title = "T", notes = null, projectId = null, labels = emptyList(),
        source = null, status = status, plannedDate = plannedDate, scheduledStart = scheduledStart,
        scheduledEnd = null, durationMinutes = null, actualTimeMinutes = 0, priority = null,
        reminderMinutes = reminderMinutes, rank = null, dueDate = dueDate, recurrenceRuleId = null,
        isRecurrenceTemplate = false, recurrenceDetached = false, recurrenceOriginalDate = null,
        completedAt = completedAt, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH, changeSeq = 0,
    )

    @Test
    fun `scheduledStart minus reminderMinutes wins when both present`() {
        val start = Instant.parse("2026-06-18T09:00:00Z")
        val t = task(scheduledStart = start, reminderMinutes = 15, plannedDate = LocalDate.parse("2026-06-18"))
        assertEquals(Instant.parse("2026-06-18T08:45:00Z"), ReminderTrigger.computeTriggerAt(t, utc))
    }

    @Test
    fun `zero reminderMinutes on scheduledStart fires exactly at start`() {
        val start = Instant.parse("2026-06-18T09:00:00Z")
        assertEquals(start, ReminderTrigger.computeTriggerAt(task(scheduledStart = start, reminderMinutes = 0), utc))
    }

    @Test
    fun `plannedDate at 9am local minus reminderMinutes when no scheduledStart`() {
        val t = task(plannedDate = LocalDate.parse("2026-06-18"), reminderMinutes = 30)
        // 09:00 UTC - 30m = 08:30 UTC
        assertEquals(Instant.parse("2026-06-18T08:30:00Z"), ReminderTrigger.computeTriggerAt(t, utc))
    }

    @Test
    fun `plannedDate 9am local respects the supplied zone`() {
        val t = task(plannedDate = LocalDate.parse("2026-06-18"), reminderMinutes = 0)
        // 09:00 in +03:00 == 06:00 UTC
        val plus3 = ZoneOffset.ofHours(3)
        assertEquals(Instant.parse("2026-06-18T06:00:00Z"), ReminderTrigger.computeTriggerAt(t, plus3))
    }

    @Test
    fun `dueDate at 9am local when no scheduledStart and no plannedDate, ignoring reminderMinutes offset`() {
        val t = task(dueDate = LocalDate.parse("2026-06-20"), reminderMinutes = 30)
        // dueDate fires at 09:00 local with no minute offset.
        assertEquals(Instant.parse("2026-06-20T09:00:00Z"), ReminderTrigger.computeTriggerAt(t, utc))
    }

    @Test
    fun `dueDate fires at 9am even with no reminderMinutes set`() {
        val t = task(dueDate = LocalDate.parse("2026-06-20"), reminderMinutes = null)
        assertEquals(Instant.parse("2026-06-20T09:00:00Z"), ReminderTrigger.computeTriggerAt(t, utc))
    }

    @Test
    fun `no trigger when reminderMinutes is null and there is no dueDate`() {
        assertNull(ReminderTrigger.computeTriggerAt(task(scheduledStart = Instant.parse("2026-06-18T09:00:00Z")), utc))
        assertNull(ReminderTrigger.computeTriggerAt(task(plannedDate = LocalDate.parse("2026-06-18")), utc))
    }

    @Test
    fun `no trigger when task is done`() {
        val t = task(
            status = TaskStatus.Done, scheduledStart = Instant.parse("2026-06-18T09:00:00Z"),
            reminderMinutes = 15, completedAt = Instant.parse("2026-06-18T07:00:00Z"),
        )
        assertNull(ReminderTrigger.computeTriggerAt(t, utc))
    }

    @Test
    fun `no trigger when completedAt is set even if status not Done`() {
        val t = task(scheduledStart = Instant.parse("2026-06-18T09:00:00Z"), reminderMinutes = 15,
            completedAt = Instant.parse("2026-06-18T07:00:00Z"))
        assertNull(ReminderTrigger.computeTriggerAt(t, utc))
    }

    @Test
    fun `no trigger when archived`() {
        val t = task(status = TaskStatus.Archived, scheduledStart = Instant.parse("2026-06-18T09:00:00Z"),
            reminderMinutes = 15)
        assertNull(ReminderTrigger.computeTriggerAt(t, utc))
    }
}
