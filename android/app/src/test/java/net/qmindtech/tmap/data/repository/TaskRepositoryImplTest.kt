package net.qmindtech.tmap.data.repository

import app.cash.turbine.test
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import net.qmindtech.tmap.data.local.AppDatabase
import net.qmindtech.tmap.data.local.OpType
import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.local.entities.TaskEntity
import net.qmindtech.tmap.data.remote.dto.CreateTaskRequest
import net.qmindtech.tmap.data.remote.dto.UpdateTaskRequest
import net.qmindtech.tmap.data.sync.OutboxRepository
import net.qmindtech.tmap.notifications.ReminderScheduler
import net.qmindtech.tmap.util.Clock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class TaskRepositoryImplTest {

    private lateinit var db: AppDatabase
    private lateinit var outbox: OutboxRepository
    private lateinit var scheduler: FakeSyncScheduler
    private lateinit var reminder: RecordingReminderScheduler
    private lateinit var repo: TaskRepositoryImpl
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private val fixedNow = Instant.parse("2026-06-18T12:00:00Z")
    private val fixedToday = LocalDate.parse("2026-06-18")
    private val clock = object : Clock {
        override fun now() = fixedNow
        override fun today() = fixedToday
    }

    /** Captures arm/cancel without an AlarmManager. */
    private class RecordingReminderScheduler : ReminderScheduler {
        val armed = mutableListOf<TaskEntity>()
        val cancelled = mutableListOf<String>()
        override fun arm(task: TaskEntity) { armed += task }
        override fun cancel(taskId: String) { cancelled += taskId }
        override fun canScheduleExact() = true
    }

    @Before
    fun setUp() {
        db = repoTestDb()
        outbox = OutboxRepository(db.outboxDao(), json, clock)
        scheduler = FakeSyncScheduler()
        reminder = RecordingReminderScheduler()
        repo = TaskRepositoryImpl(db.taskDao(), db.subtaskDao(), outbox, db, scheduler, clock, reminder)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `create returns id, row is immediately observable, enqueues CREATE, arms reminder, nudges sync`() = runTest {
        val id = repo.create(TaskDraft(title = "Plan day", status = TaskStatus.Inbox, reminderMinutes = 15))

        // Optimistic: the row is in Room right away.
        val row = db.taskDao().getById(id)
        assertNotNull(row)
        assertEquals("Plan day", row!!.title)
        assertEquals(TaskStatus.Inbox, row.status)
        assertEquals(fixedNow, row.createdAt)
        assertEquals(0L, row.changeSeq) // never-synced local row

        // Outbox carries a CREATE whose payload deserializes to a CreateTaskRequest with this id.
        val op = outbox.peek()!!
        assertEquals(OpType.CREATE, op.opType)
        assertEquals(id, op.entityId)
        val sent = json.decodeFromString(CreateTaskRequest.serializer(), op.payloadJson)
        assertEquals(id, sent.id)
        assertEquals("Plan day", sent.title)
        assertEquals("Inbox", sent.status)

        // Reminder armed; sync nudged exactly once.
        assertEquals(1, reminder.armed.size)
        assertEquals(id, reminder.armed.first().id)
        assertEquals(1, scheduler.expeditedCount)
    }

    @Test
    fun `update mutates the row, enqueues UPDATE with changed fields, re-arms reminder, nudges sync`() = runTest {
        val id = repo.create(TaskDraft(title = "old"))
        scheduler.expeditedCount = 0
        reminder.armed.clear()

        repo.update(id, TaskEdit(title = "new", priority = 2, plannedDate = LocalDate.parse("2026-06-20")))

        val row = db.taskDao().getById(id)!!
        assertEquals("new", row.title)
        assertEquals(2, row.priority)
        assertEquals(LocalDate.parse("2026-06-20"), row.plannedDate)
        assertEquals(fixedNow, row.updatedAt)

        // The newest op is an UPDATE for this id.
        val update = drainToList(outbox).last { it.entityId == id && it.opType == OpType.UPDATE }
        val sent = json.decodeFromString(UpdateTaskRequest.serializer(), update.payloadJson)
        assertEquals("new", sent.title)
        assertEquals(2, sent.priority)
        assertEquals("2026-06-20", sent.plannedDate)

        assertEquals(1, reminder.armed.size)
        assertEquals(1, scheduler.expeditedCount)
    }

    @Test
    fun `markDone sets status Done and completedAt, cancels reminder, enqueues UPDATE`() = runTest {
        val id = repo.create(TaskDraft(title = "finish me"))
        reminder.cancelled.clear()
        scheduler.expeditedCount = 0

        repo.markDone(id)

        val row = db.taskDao().getById(id)!!
        assertEquals(TaskStatus.Done, row.status)
        assertEquals(fixedNow, row.completedAt)
        assertTrue(reminder.cancelled.contains(id))
        val update = drainToList(outbox).last { it.entityId == id && it.opType == OpType.UPDATE }
        val sent = json.decodeFromString(UpdateTaskRequest.serializer(), update.payloadJson)
        assertEquals("Done", sent.status)
        assertEquals(1, scheduler.expeditedCount)
    }

    @Test
    fun `delete hard-deletes the row, cancels reminder, enqueues DELETE, nudges sync`() = runTest {
        val id = repo.create(TaskDraft(title = "gone"))
        reminder.cancelled.clear()
        scheduler.expeditedCount = 0

        repo.delete(id)

        assertNull(db.taskDao().getById(id))
        assertTrue(reminder.cancelled.contains(id))
        val del = drainToList(outbox).last { it.entityId == id }
        assertEquals(OpType.DELETE, del.opType)
        assertEquals(1, scheduler.expeditedCount)
    }

    @Test
    fun `observe Flows reflect optimistic writes`() = runTest {
        repo.observeByStatus(TaskStatus.Inbox).test {
            assertEquals(emptyList<TaskEntity>(), awaitItem())
            repo.create(TaskDraft(title = "captured", status = TaskStatus.Inbox))
            assertEquals(listOf("captured"), awaitItem().map { it.title })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observeToday returns only rows planned for today, time-ordered`() = runTest {
        repo.create(TaskDraft(title = "today-1", status = TaskStatus.Planned, plannedDate = fixedToday))
        repo.create(TaskDraft(title = "tomorrow", status = TaskStatus.Planned, plannedDate = fixedToday.plusDays(1)))
        val titles = repo.observeToday(fixedToday).first().map { it.title }
        assertEquals(listOf("today-1"), titles)
    }

    @Test
    fun `addActualTime increments the local actualTimeMinutes and enqueues an UPDATE`() = runTest {
        val id = repo.create(TaskDraft(title = "focus me"))
        scheduler.expeditedCount = 0
        repo.addActualTime(id, 25)
        repo.addActualTime(id, 10)
        assertEquals(35, db.taskDao().getById(id)!!.actualTimeMinutes)
        assertEquals(OpType.UPDATE, db.outboxDao().allForTest().last { it.entityId == id }.opType)
    }

    /** Reads every queued op (parked or not) by repeatedly peeking + deleting a copy db is not safe; use the dao. */
    private suspend fun drainToList(outbox: OutboxRepository): List<net.qmindtech.tmap.data.local.entities.OutboxOp> {
        // Snapshot all rows via the dao's observe (single emission) without mutating the queue.
        return db.outboxDao().allForTest()
    }
}
