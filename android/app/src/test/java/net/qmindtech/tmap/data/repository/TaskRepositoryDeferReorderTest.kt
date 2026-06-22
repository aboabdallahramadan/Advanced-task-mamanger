package net.qmindtech.tmap.data.repository

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import net.qmindtech.tmap.data.local.AppDatabase
import net.qmindtech.tmap.data.local.OpType
import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.local.entities.OutboxOp
import net.qmindtech.tmap.data.local.entities.TaskEntity
import net.qmindtech.tmap.data.sync.OutboxRepository
import net.qmindtech.tmap.notifications.ReminderScheduler
import net.qmindtech.tmap.util.Clock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class TaskRepositoryDeferReorderTest {

    private lateinit var db: AppDatabase
    private lateinit var outbox: OutboxRepository
    private lateinit var scheduler: FakeSyncScheduler
    private lateinit var reminder: RecordingReminderScheduler
    private lateinit var repo: TaskRepositoryImpl

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val fixedNow = Instant.parse("2026-06-22T10:00:00Z")
    private val fixedToday = LocalDate.parse("2026-06-22")
    private val clock = object : Clock {
        override fun now() = fixedNow
        override fun today() = fixedToday
    }

    private class RecordingReminderScheduler : ReminderScheduler {
        val armed = mutableListOf<TaskEntity>()
        val cancelled = mutableListOf<String>()
        override fun arm(task: TaskEntity) { armed += task }
        override fun cancel(taskId: String) { cancelled += taskId }
        override fun canScheduleExact() = true
    }

    private suspend fun allOutboxOps(): List<OutboxOp> = db.outboxDao().allForTest()

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
    fun `defer sets plannedDate clears schedule and enqueues UPDATE`() = runTest {
        val id = repo.create(
            TaskDraft(title = "x", status = TaskStatus.Inbox, plannedDate = LocalDate.of(2026, 6, 21)),
        )
        // clear outbox from create op
        outbox.clear()
        scheduler.expeditedCount = 0

        repo.defer(id, LocalDate.of(2026, 6, 22))

        val t = db.taskDao().getById(id)!!
        assertEquals(LocalDate.of(2026, 6, 22), t.plannedDate)
        assertEquals(TaskStatus.Planned, t.status)
        assertNull(t.scheduledStart)

        val ops = allOutboxOps()
        assertEquals(1, ops.size)
        assertEquals(OpType.UPDATE, ops.first().opType)
        assertEquals(1, scheduler.expeditedCount)
    }

    @Test
    fun `moveToDay transitions Inbox to Planned`() = runTest {
        val id = repo.create(TaskDraft(title = "inbox task", status = TaskStatus.Inbox))
        outbox.clear()

        repo.moveToDay(id, LocalDate.of(2026, 6, 23))

        val t = db.taskDao().getById(id)!!
        assertEquals(LocalDate.of(2026, 6, 23), t.plannedDate)
        assertEquals(TaskStatus.Planned, t.status)
        val ops = allOutboxOps()
        assertEquals(1, ops.size)
        assertEquals(OpType.UPDATE, ops.first().opType)
    }

    @Test
    fun `moveToDay keeps status when already Planned`() = runTest {
        val id = repo.create(
            TaskDraft(title = "planned task", status = TaskStatus.Planned, plannedDate = LocalDate.of(2026, 6, 21)),
        )
        outbox.clear()

        repo.moveToDay(id, LocalDate.of(2026, 6, 24))

        val t = db.taskDao().getById(id)!!
        assertEquals(LocalDate.of(2026, 6, 24), t.plannedDate)
        assertEquals(TaskStatus.Planned, t.status)
    }

    @Test
    fun `defer delegates to moveToDay (same outcome)`() = runTest {
        val id = repo.create(
            TaskDraft(title = "y", status = TaskStatus.Backlog, plannedDate = LocalDate.of(2026, 6, 21)),
        )
        outbox.clear()

        repo.defer(id, LocalDate.of(2026, 6, 25))

        val t = db.taskDao().getById(id)!!
        assertEquals(LocalDate.of(2026, 6, 25), t.plannedDate)
        assertEquals(TaskStatus.Planned, t.status)
    }

    @Test
    fun `reorder assigns increasing ranks in list order`() = runTest {
        val a = repo.create(TaskDraft(title = "a"))
        val b = repo.create(TaskDraft(title = "b"))
        val c = repo.create(TaskDraft(title = "c"))
        outbox.clear()

        repo.reorder(listOf(c, a, b))

        val rc = db.taskDao().getById(c)!!.rank
        val ra = db.taskDao().getById(a)!!.rank
        val rb = db.taskDao().getById(b)!!.rank
        assertEquals(true, rc!! < ra!! && ra < rb!!)
        // 3 UPDATE ops enqueued
        val ops = allOutboxOps()
        assertEquals(3, ops.size)
        ops.forEach { assertEquals(OpType.UPDATE, it.opType) }
    }

    @Test
    fun `reorder rank values are zero-padded 6-digit strings`() = runTest {
        val a = repo.create(TaskDraft(title = "a"))
        val b = repo.create(TaskDraft(title = "b"))
        outbox.clear()

        repo.reorder(listOf(a, b))

        assertEquals("000000", db.taskDao().getById(a)!!.rank)
        assertEquals("000001", db.taskDao().getById(b)!!.rank)
    }

    @Test
    fun `addActualTime accumulates`() = runTest {
        val id = repo.create(TaskDraft(title = "x"))
        outbox.clear()

        repo.addActualTime(id, 25)
        repo.addActualTime(id, 5)

        assertEquals(30, db.taskDao().getById(id)!!.actualTimeMinutes)
        // 2 UPDATE ops enqueued
        val ops = allOutboxOps()
        assertEquals(2, ops.size)
        ops.forEach { assertEquals(OpType.UPDATE, it.opType) }
    }

    @Test
    fun `addActualTime nudges sync`() = runTest {
        val id = repo.create(TaskDraft(title = "z"))
        scheduler.expeditedCount = 0

        repo.addActualTime(id, 10)

        assertEquals(1, scheduler.expeditedCount)
    }
}
