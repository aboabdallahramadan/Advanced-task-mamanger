package net.qmindtech.tmap.data.sync

import kotlinx.coroutines.test.runTest
import net.qmindtech.tmap.data.local.EntityType
import net.qmindtech.tmap.data.local.OpType
import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.local.entities.TaskEntity
import net.qmindtech.tmap.data.remote.dto.CreateTaskRequest
import net.qmindtech.tmap.util.Clock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/**
 * BUG 1(a) — a PARKED (poison) op must BLOCK the destructive full-resync. The gate uses TOTAL outbox
 * count (countAll), not countUnparked, so a parked op's local-only row is not wiped (it could never
 * replay otherwise).
 */
@RunWith(RobolectricTestRunner::class)
class PullRunnerParkedResyncGateTest {

    private lateinit var env: SyncTestEnv
    private lateinit var runner: PullRunner
    private lateinit var outbox: OutboxRepository
    private val rearmer = FakeRearmer()
    private val clock: Clock = FixedClock()

    @Before
    fun setUp() {
        env = SyncTestEnv()
        outbox = OutboxRepository(env.db.outboxDao(), env.json, clock)
        runner = PullRunner(
            env.api, env.db, env.db.taskDao(), env.db.subtaskDao(), env.db.projectDao(),
            env.db.settingsDao(), env.db.syncStateDao(), env.db.outboxDao(), rearmer,
        )
    }

    @After
    fun tearDown() = env.close()

    private fun localRow(id: String) = TaskEntity(
        id = id, title = "local-only", notes = null, projectId = null, labels = emptyList(),
        source = "android", status = TaskStatus.Inbox, plannedDate = null, scheduledStart = null,
        scheduledEnd = null, durationMinutes = null, actualTimeMinutes = 0, priority = null,
        reminderMinutes = null, rank = null, dueDate = null, recurrenceRuleId = null,
        isRecurrenceTemplate = false, recurrenceDetached = false, recurrenceOriginalDate = null,
        completedAt = null, createdAt = Instant.parse("2026-06-18T00:00:00Z"),
        updatedAt = Instant.parse("2026-06-18T00:00:00Z"), changeSeq = 1,
    )

    @Test
    fun `a parked op blocks the destructive full-resync — its local row survives`() = runTest {
        env.db.taskDao().upsertAll(listOf(localRow("poison")))
        env.db.syncStateDao().upsert(env.db.syncStateDao().get().copy(lastSeq = 9999))
        // Enqueue an op for that row and PARK it (parkedAt non-null). countUnparked()==0 but countAll()==1.
        outbox.enqueueRaw(
            EntityType.TASK, "poison", OpType.CREATE,
            env.json.encodeToString(CreateTaskRequest.serializer(), CreateTaskRequest(id = "poison", title = "p")),
        )
        env.db.outboxDao().bumpAttempts(env.db.outboxDao().allForTest().single().localSeq, clock.now())
        assertEquals(0, outbox.countUnparked())
        assertEquals(1, outbox.countAll())

        env.server.enqueue(env.jsonResponse(200, """{"changes":{},"nextSince":12000,"hasMore":false,"fullResyncRequired":true}"""))

        val outcome = runner.pullAll()

        assertEquals(false, outcome.fullResynced) // deferred — a parked op blocks it
        assertNotNull(env.db.taskDao().getById("poison")) // local-only row NOT wiped
        assertEquals(9999L, env.db.syncStateDao().get().lastSeq) // cursor unchanged
        assertEquals(1, env.server.requestCount) // no from-0 refill issued
    }
}
