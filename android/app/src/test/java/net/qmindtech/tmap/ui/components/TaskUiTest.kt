package net.qmindtech.tmap.ui.components

import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.local.entities.ProjectEntity
import net.qmindtech.tmap.data.local.entities.TaskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class TaskUiTest {
    private val utc = ZoneId.of("UTC")
    private val t0 = Instant.parse("2026-06-21T00:00:00Z")

    private fun task(
        id: String = "t1",
        title: String = "Design review",
        status: TaskStatus = TaskStatus.Planned,
        projectId: String? = null,
        scheduledStart: Instant? = null,
        durationMinutes: Int? = null,
        priority: Int? = null,
        reminderMinutes: Int? = null,
    ) = TaskEntity(
        id = id, title = title, notes = null, projectId = projectId, labels = emptyList(),
        source = null, status = status, plannedDate = LocalDate.parse("2026-06-21"),
        scheduledStart = scheduledStart, scheduledEnd = null, durationMinutes = durationMinutes,
        actualTimeMinutes = 0, priority = priority, reminderMinutes = reminderMinutes, rank = null,
        dueDate = null, recurrenceRuleId = null, isRecurrenceTemplate = false,
        recurrenceDetached = false, recurrenceOriginalDate = null, completedAt = null,
        createdAt = t0, updatedAt = t0, changeSeq = 1L,
    )

    private fun project(name: String = "Work", color: String = "#6EA8FE") = ProjectEntity(
        id = "p1", name = name, color = color, emoji = "💼", rank = null,
        actualTimeMinutes = 0, createdAt = t0, updatedAt = t0, changeSeq = 1L,
    )

    @Test
    fun mapsCoreFieldsWithProject() {
        val ui = task(projectId = "p1", priority = 2, reminderMinutes = 15).toUi(project(), zone = utc)
        assertEquals("t1", ui.id)
        assertEquals("Design review", ui.title)
        assertEquals("Work", ui.projectName)
        assertEquals(0xFF6EA8FEL, ui.projectColor)
        assertEquals(2, ui.priority)
        assertEquals(true, ui.hasReminder)
        assertEquals(false, ui.isDone)
    }

    @Test
    fun nullProjectYieldsNullNameAndColor() {
        val ui = task().toUi(null, zone = utc)
        assertNull(ui.projectName)
        assertNull(ui.projectColor)
    }

    @Test
    fun nullPriorityBecomesZeroAndNoReminderIsFalse() {
        val ui = task(priority = null, reminderMinutes = null).toUi(null, zone = utc)
        assertEquals(0, ui.priority)
        assertEquals(false, ui.hasReminder)
    }

    @Test
    fun doneStatusSetsIsDone() {
        assertEquals(true, task(status = TaskStatus.Done).toUi(null, zone = utc).isDone)
    }

    @Test
    fun subtaskCountsDefaultToZeroButArePassedThrough() {
        assertEquals(0, task().toUi(null, zone = utc).subtaskTotal)
        val ui = task().toUi(null, subtaskDone = 1, subtaskTotal = 3, zone = utc)
        assertEquals(1, ui.subtaskDone)
        assertEquals(3, ui.subtaskTotal)
    }

    @Test
    fun scheduledLabelStartOnly() {
        val ui = task(scheduledStart = Instant.parse("2026-06-21T09:30:00Z")).toUi(null, zone = utc)
        assertEquals("9:30", ui.scheduledLabel)
    }

    @Test
    fun scheduledLabelStartAndEndFromDuration() {
        val ui = task(
            scheduledStart = Instant.parse("2026-06-21T09:30:00Z"),
            durationMinutes = 45,
        ).toUi(null, zone = utc)
        assertEquals("9:30–10:15", ui.scheduledLabel)
    }

    @Test
    fun scheduledLabelNullWhenUnscheduled() {
        assertNull(task(scheduledStart = null).toUi(null, zone = utc).scheduledLabel)
    }

    @Test
    fun parseProjectColorHandlesHashAndBareHexAndGarbage() {
        assertEquals(0xFF6EA8FEL, parseProjectColor("#6EA8FE"))
        assertEquals(0xFF38D39FL, parseProjectColor("38D39F"))
        assertNull(parseProjectColor(null))
        assertNull(parseProjectColor(""))
        assertNull(parseProjectColor("not-a-color"))
    }

    // --- 24-hour H:mm format edge cases (Midnight Calm design spec) ---

    @Test
    fun scheduledLabel24hSubTenMorningHour() {
        // 09:05 UTC → "9:05" (no leading zero on hour, zero-padded minute)
        val ui = task(scheduledStart = Instant.parse("2026-06-21T09:05:00Z")).toUi(null, zone = utc)
        assertEquals("9:05", ui.scheduledLabel)
    }

    @Test
    fun scheduledLabel24hAfternoonHourNoRange() {
        // 14:00 UTC → "14:00" (24-hour, no duration)
        val ui = task(scheduledStart = Instant.parse("2026-06-21T14:00:00Z")).toUi(null, zone = utc)
        assertEquals("14:00", ui.scheduledLabel)
    }

    @Test
    fun scheduledLabel24hRangeCrossingIntoAfternoon() {
        // 13:30 + 90 min → "13:30–15:00"
        val ui = task(
            scheduledStart = Instant.parse("2026-06-21T13:30:00Z"),
            durationMinutes = 90,
        ).toUi(null, zone = utc)
        assertEquals("13:30–15:00", ui.scheduledLabel)
    }

    @Test
    fun scheduledLabelZeroDurationTreatedAsNoDuration() {
        // duration = 0 → treated as no range, emits start only
        val ui = task(
            scheduledStart = Instant.parse("2026-06-21T09:30:00Z"),
            durationMinutes = 0,
        ).toUi(null, zone = utc)
        assertEquals("9:30", ui.scheduledLabel)
    }
}
