package net.qmindtech.tmap.data.sync

import kotlinx.coroutines.test.runTest
import net.qmindtech.tmap.data.local.EntityType
import net.qmindtech.tmap.data.local.OpType
import net.qmindtech.tmap.data.local.entities.TaskEntity
import net.qmindtech.tmap.data.remote.dto.CreateTaskRequest
import net.qmindtech.tmap.data.remote.dto.UpdateTaskRequest
import net.qmindtech.tmap.data.repository.FakeSyncScheduler
import net.qmindtech.tmap.data.repository.TaskDraft
import net.qmindtech.tmap.data.repository.TaskRepositoryImpl
import net.qmindtech.tmap.notifications.ReminderScheduler
import net.qmindtech.tmap.util.Clock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * BUG 0 — definitive-4xx Drop must recover ghost rows + schedule a recovery pull (SP3 mirror).
 * Unlike PushRunner4xxDropTest (ops with no backing Room row), these tests create a REAL Room row
 * via the repository so a rejected CREATE leaves an orphan that must be deleted.
 */
@RunWith(RobolectricTestRunner::class)
class PushRunnerGhostRecoveryTest {

    private lateinit var env: SyncTestEnv
    private lateinit var outbox: OutboxRepository
    private lateinit var runner: PushRunner
    private lateinit var repo: TaskRepositoryImpl
    private val clock: Clock = FixedClock()

    private class NoopReminder : ReminderScheduler {
        override fun arm(task: TaskEntity) {}
        override fun cancel(taskId: String) {}
        override fun canScheduleExact() = true
    }

    @Before
    fun setUp() {
        env = SyncTestEnv()
        outbox = OutboxRepository(env.db.outboxDao(), env.json, clock)
        runner = PushRunner(
            env.api, outbox, env.db.taskDao(), env.db.subtaskDao(), env.db.projectDao(),
            env.db.noteDao(), env.db.noteGroupDao(), env.db.focusSessionDao(), env.db.dailyPlanDao(),
            env.db.syncStateDao(), env.json, { },
        )
        repo = TaskRepositoryImpl(
            env.db.taskDao(), env.db.subtaskDao(), outbox, env.db,
            FakeSyncScheduler(), clock, NoopReminder(),
        )
    }

    @After
    fun tearDown() = env.close()

    @Test
    fun `a rejected CREATE deletes the orphan local row and its outbox op`() = runTest {
        // A real Room row + a CREATE op exist (write-through repository).
        val id = repo.create(TaskDraft(title = "ghost"))
        assertNotNull(env.db.taskDao().getById(id)) // the orphan exists pre-drain
        assertEquals(1, outbox.countAll())

        // Server rejects the create with a definitive 400 (e.g. validation).
        env.server.enqueue(env.jsonResponse(400, """{"title":"validation failed","status":400}"""))

        val outcome = runner.drain()

        assertEquals(1, outcome.rejected)
        assertNull(env.db.taskDao().getById(id)) // orphan Room row is GONE (recoverGhostRows)
        assertEquals(0, outbox.countAll()) // the create op is gone
    }

    @Test
    fun `a rejected CREATE also drops other queued ops for the same id`() = runTest {
        val id = repo.create(TaskDraft(title = "ghost"))
        // A follow-up UPDATE queued for the same (still-ghost) id — it would also 4xx, so must be purged.
        outbox.enqueue(
            EntityType.TASK, id, OpType.UPDATE,
            UpdateTaskRequest(title = "edited"), UpdateTaskRequest.serializer(),
        )
        assertEquals(2, outbox.countAll())

        env.server.enqueue(env.jsonResponse(400, """{"title":"bad","status":400}"""))

        runner.drain()

        assertNull(env.db.taskDao().getById(id))
        assertEquals(0, outbox.countAll()) // BOTH the create and the follow-up update are gone
    }

    @Test
    fun `any drop sets the durable pendingRecovery flag`() = runTest {
        // A bare CREATE op with no backing row (a rejected UPDATE-style divergence still needs recovery).
        outbox.enqueueRaw(
            EntityType.TASK, "x", OpType.CREATE,
            env.json.encodeToString(CreateTaskRequest.serializer(), CreateTaskRequest(id = "x", title = "x")),
        )
        env.db.syncStateDao().get() // ensure the (1) row exists
        assertFalse(env.db.syncStateDao().get().pendingRecovery)

        env.server.enqueue(env.jsonResponse(400, """{"title":"bad","status":400}"""))

        runner.drain()

        assertTrue(env.db.syncStateDao().get().pendingRecovery)
    }
}
