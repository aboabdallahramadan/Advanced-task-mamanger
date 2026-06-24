package net.qmindtech.tmap.widget

import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.local.entities.ProjectEntity
import net.qmindtech.tmap.data.local.entities.TaskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class WidgetRepositoryListTest {

    private val zone = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 6, 21)

    private fun task(
        id: String,
        status: TaskStatus = TaskStatus.Scheduled,
        start: Instant? = null,
        duration: Int? = null,
        rank: String? = null,
        projectId: String? = null,
    ) = TaskEntity(
        id = id, title = "T-$id", notes = null, projectId = projectId, labels = emptyList(),
        source = "test", status = status, plannedDate = today, scheduledStart = start,
        scheduledEnd = null, durationMinutes = duration, actualTimeMinutes = 0, priority = null,
        reminderMinutes = null, rank = rank, dueDate = null, recurrenceRuleId = null,
        isRecurrenceTemplate = false, recurrenceDetached = false, recurrenceOriginalDate = null,
        completedAt = null, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH, changeSeq = 0L,
    )

    private fun project(id: String, color: String) = ProjectEntity(
        id = id, name = id, color = color, emoji = "", rank = null, actualTimeMinutes = 0,
        createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH, changeSeq = 0L,
    )

    @Test fun `buildItems orders by rank then renders time label and project color`() {
        val nineThirty = LocalDate.of(2026, 6, 21).atTime(9, 30).atZone(zone).toInstant()
        val tasks = listOf(
            task("b", rank = "b", start = nineThirty, projectId = "work"),
            task("a", rank = "a"),
        )
        val projects = mapOf("work" to project("work", "#6EA8FE"))
        val items = WidgetRepository.buildItems(tasks, projects, zone)
        assertEquals(listOf("a", "b"), items.map { it.id })
        assertNull(items[0].timeLabel)
        assertEquals("09:30", items[1].timeLabel)
        assertEquals(0xFF6EA8FEL, items[1].projectColor)
    }

    @Test fun `buildItems marks done tasks`() {
        val items = WidgetRepository.buildItems(
            listOf(task("a", status = TaskStatus.Done)), emptyMap(), zone,
        )
        assertEquals(true, items[0].isDone)
    }

    @Test fun `minutesLeft sums duration of unfinished today tasks`() {
        val tasks = listOf(
            task("a", status = TaskStatus.Done, duration = 30),
            task("b", status = TaskStatus.Scheduled, duration = 60),
            task("c", status = TaskStatus.Planned, duration = 45),
            task("d", status = TaskStatus.Archived, duration = 99),
        )
        assertEquals(105, WidgetRepository.minutesLeft(tasks))
    }
}
