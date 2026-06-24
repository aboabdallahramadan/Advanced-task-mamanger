package net.qmindtech.tmap.widget

import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.local.entities.TaskEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class WidgetStatsTest {

    private val today = LocalDate.of(2026, 6, 21)

    private fun task(
        id: String,
        status: TaskStatus,
        plannedDate: LocalDate? = today,
    ) = TaskEntity(
        id = id, title = id, notes = null, projectId = null, labels = emptyList(),
        source = "test", status = status, plannedDate = plannedDate,
        scheduledStart = null, scheduledEnd = null, durationMinutes = null,
        actualTimeMinutes = 0, priority = null, reminderMinutes = null, rank = null,
        dueDate = null, recurrenceRuleId = null, isRecurrenceTemplate = false,
        recurrenceDetached = false, recurrenceOriginalDate = null, completedAt = null,
        createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH, changeSeq = 0L,
    )

    @Test fun `todayProgress is 0 when no tasks`() {
        assertEquals(0f, WidgetStats.todayProgress(emptyList()), 0.0001f)
    }

    @Test fun `todayProgress is done over total`() {
        val tasks = listOf(
            task("a", TaskStatus.Done),
            task("b", TaskStatus.Done),
            task("c", TaskStatus.Scheduled),
            task("d", TaskStatus.Planned),
        )
        assertEquals(0.5f, WidgetStats.todayProgress(tasks), 0.0001f)
    }

    @Test fun `todayProgress ignores archived in the denominator`() {
        val tasks = listOf(
            task("a", TaskStatus.Done),
            task("b", TaskStatus.Scheduled),
            task("c", TaskStatus.Archived),
        )
        assertEquals(0.5f, WidgetStats.todayProgress(tasks), 0.0001f)
    }

    @Test fun `dayStreak counts consecutive days ending today`() {
        val map = mapOf(
            today to true,
            today.minusDays(1) to true,
            today.minusDays(2) to true,
            today.minusDays(3) to false, // breaks the chain
            today.minusDays(4) to true,
        )
        assertEquals(3, WidgetStats.dayStreak(map, today))
    }

    @Test fun `dayStreak still counts yesterday-anchored chain when today is empty`() {
        // No completion logged today yet, but yesterday + before were completed.
        val map = mapOf(
            today.minusDays(1) to true,
            today.minusDays(2) to true,
        )
        assertEquals(2, WidgetStats.dayStreak(map, today))
    }

    @Test fun `dayStreak is 0 when neither today nor yesterday completed`() {
        val map = mapOf(today.minusDays(2) to true)
        assertEquals(0, WidgetStats.dayStreak(map, today))
    }
}
