package net.qmindtech.tmap.data.repository

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import net.qmindtech.tmap.data.local.AppDatabase
import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.sync.OutboxRepository
import net.qmindtech.tmap.notifications.ReminderScheduler
import net.qmindtech.tmap.data.local.entities.TaskEntity
import net.qmindtech.tmap.util.Clock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class ProjectProgressRepoTest {

    private lateinit var db: AppDatabase
    private lateinit var outbox: OutboxRepository
    private lateinit var scheduler: FakeSyncScheduler
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private val fixedNow = Instant.parse("2026-06-18T12:00:00Z")
    private val fixedToday = LocalDate.parse("2026-06-18")
    private val clock = object : Clock {
        override fun now() = fixedNow
        override fun today() = fixedToday
    }

    private class NoOpReminderScheduler : ReminderScheduler {
        override fun arm(task: TaskEntity) = Unit
        override fun cancel(taskId: String) = Unit
        override fun canScheduleExact() = true
    }

    private fun projectRepository(): ProjectRepositoryImpl =
        ProjectRepositoryImpl(db.projectDao(), outbox, db, scheduler, clock)

    private fun taskRepository(): TaskRepositoryImpl =
        TaskRepositoryImpl(db.taskDao(), db.subtaskDao(), outbox, db, scheduler, clock, NoOpReminderScheduler())

    @Before
    fun setUp() {
        db = repoTestDb()
        outbox = OutboxRepository(db.outboxDao(), json, clock)
        scheduler = FakeSyncScheduler()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `observeProgress reflects done over total via the DAO`() = runTest {
        val projectRepo = projectRepository()
        val pid = projectRepo.create("Work", "#6EA8FE", "💼")
        val taskRepo = taskRepository()
        taskRepo.create(TaskDraft(title = "open", projectId = pid, status = TaskStatus.Planned))
        val doneId = taskRepo.create(TaskDraft(title = "done", projectId = pid, status = TaskStatus.Planned))
        taskRepo.markDone(doneId)

        val row = projectRepo.observeProgress().first().single()
        assertEquals(pid, row.projectId)
        assertEquals(2, row.total)
        assertEquals(1, row.done)
    }
}
