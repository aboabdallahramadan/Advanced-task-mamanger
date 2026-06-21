package net.qmindtech.tmap.notifications

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import net.qmindtech.tmap.data.local.AppDatabase
import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.local.entities.TaskEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDate

/**
 * Recording fake of the [ReminderScheduler] seam (the arm/cancel surface ReminderRearmer depends on;
 * AlarmReminderScheduler is the real impl). Records arm/cancel calls in order.
 */
private class FakeReminderScheduler : ReminderScheduler {
    val armed = mutableListOf<String>()
    val cancelled = mutableListOf<String>()
    override fun arm(task: TaskEntity) { armed += task.id }
    override fun cancel(taskId: String) { cancelled += taskId }
    override fun canScheduleExact(): Boolean = true
}

@RunWith(RobolectricTestRunner::class)
class ReminderRearmerTest {

    private lateinit var db: AppDatabase
    private val scheduler = FakeReminderScheduler()

    private fun task(
        id: String,
        status: TaskStatus = TaskStatus.Scheduled,
        scheduledStart: Instant? = Instant.parse("2026-06-18T09:00:00Z"),
        reminderMinutes: Int? = 15,
        plannedDate: LocalDate? = null,
        isTemplate: Boolean = false,
    ) = TaskEntity(
        id = id, title = "T-$id", notes = null, projectId = null, labels = emptyList(),
        source = null, status = status, plannedDate = plannedDate, scheduledStart = scheduledStart,
        scheduledEnd = null, durationMinutes = null, actualTimeMinutes = 0, priority = null,
        reminderMinutes = reminderMinutes, rank = null, dueDate = null, recurrenceRuleId = null,
        isRecurrenceTemplate = isTemplate, recurrenceDetached = false, recurrenceOriginalDate = null,
        completedAt = null, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH, changeSeq = 0,
    )

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun rearmer() = ReminderRearmer(scheduler, db.taskDao())

    @Test
    fun `reconcile arms changed tasks and cancels deleted ids`() = runTest {
        val changed = listOf(task("a"), task("b"))
        rearmer().reconcile(changed, deletedIds = listOf("x", "y"))
        assertEquals(listOf("a", "b"), scheduler.armed)
        assertEquals(listOf("x", "y"), scheduler.cancelled)
    }

    @Test
    fun `reconcile with empty lists does nothing`() = runTest {
        rearmer().reconcile(emptyList(), emptyList())
        assertEquals(emptyList<String>(), scheduler.armed)
        assertEquals(emptyList<String>(), scheduler.cancelled)
    }

    @Test
    fun `rearmAll arms every non-template task in the store`() = runTest {
        db.taskDao().upsertAll(
            listOf(
                task("a"),
                task("b", status = TaskStatus.Done),    // arm() will no-op internally; rearmer still calls it
                task("tmpl", isTemplate = true),         // templates excluded by observeAll()
            ),
        )
        rearmer().rearmAll()
        // observeAll() returns non-template rows only; rearmAll arms each (scheduler decides fire/no-fire).
        assertEquals(setOf("a", "b"), scheduler.armed.toSet())
        assertEquals(2, scheduler.armed.size)
    }
}
