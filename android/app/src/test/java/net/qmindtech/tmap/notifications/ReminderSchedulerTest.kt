package net.qmindtech.tmap.notifications

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.local.entities.TaskEntity
import net.qmindtech.tmap.util.Clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

private class FakeClock(
    private var instant: Instant = Instant.parse("2026-06-18T00:00:00Z"),
    private val zoneId: ZoneId = ZoneOffset.UTC,
) : Clock {
    override fun now(): Instant = instant
    override fun today(): LocalDate = LocalDate.ofInstant(instant, zoneId)
    override fun zone(): ZoneId = zoneId
}

@RunWith(RobolectricTestRunner::class)
class ReminderSchedulerTest {

    private lateinit var context: Context
    private lateinit var am: AlarmManager
    private val clock = FakeClock()

    private fun task(
        id: String = "t1",
        status: TaskStatus = TaskStatus.Scheduled,
        scheduledStart: Instant? = Instant.parse("2026-06-18T09:00:00Z"),
        reminderMinutes: Int? = 15,
        plannedDate: LocalDate? = null,
        dueDate: LocalDate? = null,
        completedAt: Instant? = null,
    ) = TaskEntity(
        id = id, title = "Title-$id", notes = null, projectId = null, labels = emptyList(),
        source = null, status = status, plannedDate = plannedDate, scheduledStart = scheduledStart,
        scheduledEnd = null, durationMinutes = null, actualTimeMinutes = 0, priority = null,
        reminderMinutes = reminderMinutes, rank = null, dueDate = dueDate, recurrenceRuleId = null,
        isRecurrenceTemplate = false, recurrenceDetached = false, recurrenceOriginalDate = null,
        completedAt = completedAt, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH, changeSeq = 0,
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        am = context.getSystemService(AlarmManager::class.java)
    }

    private fun scheduler() = AlarmReminderScheduler(context, am, clock)

    @Test
    fun `arm schedules an exact alarm at scheduledStart minus reminderMinutes`() {
        scheduler().arm(task())
        val shadow = shadowOf(am)
        val scheduled = shadow.scheduledAlarms
        assertEquals(1, scheduled.size)
        // 09:00Z - 15m = 08:45Z
        assertEquals(Instant.parse("2026-06-18T08:45:00Z").toEpochMilli(), scheduled[0].triggerAtTime)
    }

    @Test
    fun `arm is a no-op for a done task`() {
        scheduler().arm(task(status = TaskStatus.Done, completedAt = Instant.parse("2026-06-18T07:00:00Z")))
        assertTrue(shadowOf(am).scheduledAlarms.isEmpty())
    }

    @Test
    fun `arm is a no-op for a task with no time anchor`() {
        scheduler().arm(task(scheduledStart = null, reminderMinutes = null, dueDate = null))
        assertTrue(shadowOf(am).scheduledAlarms.isEmpty())
    }

    @Test
    fun `arm is a no-op when the trigger is in the past`() {
        // now = 2026-06-18T00:00:00Z; a trigger at 2026-06-17T... is past.
        scheduler().arm(task(scheduledStart = Instant.parse("2026-06-17T09:00:00Z"), reminderMinutes = 0))
        assertTrue(shadowOf(am).scheduledAlarms.isEmpty())
    }

    @Test
    fun `arm schedules a dueDate-only task at 9am local`() {
        scheduler().arm(task(scheduledStart = null, reminderMinutes = null, dueDate = LocalDate.parse("2026-06-20")))
        val scheduled = shadowOf(am).scheduledAlarms
        assertEquals(1, scheduled.size)
        assertEquals(Instant.parse("2026-06-20T09:00:00Z").toEpochMilli(), scheduled[0].triggerAtTime)
    }

    @Test
    fun `re-arming the same task id replaces, not duplicates, the alarm`() {
        val s = scheduler()
        s.arm(task(reminderMinutes = 15))
        s.arm(task(reminderMinutes = 30)) // same id -> same PendingIntent request code -> replaced
        val scheduled = shadowOf(am).scheduledAlarms
        assertEquals(1, scheduled.size)
        assertEquals(Instant.parse("2026-06-18T08:30:00Z").toEpochMilli(), scheduled[0].triggerAtTime)
    }

    @Test
    fun `cancel removes a pending alarm`() {
        val s = scheduler()
        s.arm(task())
        assertEquals(1, shadowOf(am).scheduledAlarms.size)
        s.cancel("t1")
        assertNull(shadowOf(am).getNextScheduledAlarm())
    }
}
