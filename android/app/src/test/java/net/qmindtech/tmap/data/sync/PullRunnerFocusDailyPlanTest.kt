package net.qmindtech.tmap.data.sync

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.qmindtech.tmap.data.local.EntityType
import net.qmindtech.tmap.data.local.OpType
import net.qmindtech.tmap.data.local.entities.DailyPlanEntity
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
class PullRunnerFocusDailyPlanTest {

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
            env.db.noteDao(), env.db.noteGroupDao(), env.db.focusSessionDao(), env.db.dailyPlanDao(),
            env.db.settingsDao(), env.db.syncStateDao(), env.db.outboxDao(), rearmer,
        )
    }

    @After
    fun tearDown() = env.close()

    @Test
    fun `a pulled focus session upserts and a tombstone deletes it`() = runTest {
        env.server.enqueue(env.jsonResponse(200,
            """{"changes":{"focusSessions":[{"id":"f1","taskId":"t1","project":"العمل","startedAt":"2026-06-18T09:00:00Z","endedAt":"2026-06-18T09:25:00Z","minutes":25,"date":"2026-06-18","createdAt":"2026-06-18T09:25:00Z","updatedAt":"2026-06-18T09:25:00Z","changeSeq":6,"deletedAt":null}]},"nextSince":50,"hasMore":false}"""))
        runner.pullAll()
        assertEquals(25, env.db.focusSessionDao().getById("f1")!!.minutes)

        env.server.enqueue(env.jsonResponse(200,
            """{"changes":{"focusSessions":[{"id":"f1","taskId":"t1","project":"العمل","startedAt":"2026-06-18T09:00:00Z","endedAt":"2026-06-18T09:25:00Z","minutes":25,"date":"2026-06-18","createdAt":"2026-06-18T09:25:00Z","updatedAt":"2026-06-18T09:25:00Z","changeSeq":7,"deletedAt":"2026-06-18T10:00:00Z"}]},"nextSince":60,"hasMore":false}"""))
        runner.pullAll()
        assertNull(env.db.focusSessionDao().getById("f1"))
    }

    @Test
    fun `a pulled daily plan upserts by date`() = runTest {
        env.server.enqueue(env.jsonResponse(200,
            """{"changes":{"dailyPlans":[{"date":"2026-06-18","committedAt":"2026-06-18T07:00:00Z","plannedTaskIds":["a","b"],"plannedMinutes":120,"changeSeq":8,"deletedAt":null}]},"nextSince":50,"hasMore":false}"""))
        runner.pullAll()
        val row = env.db.dailyPlanDao().getByDate(LocalDate.parse("2026-06-18"))!!
        assertEquals(listOf("a", "b"), row.plannedTaskIds)
        assertEquals(120, row.plannedMinutes)
    }

    @Test
    fun `the shadow rule protects a pending daily-plan upsert keyed by date`() = runTest {
        // Local pending upsert for the date (entityId = date string).
        env.db.dailyPlanDao().upsertAll(listOf(DailyPlanEntity(
            date = LocalDate.parse("2026-06-18"), committedAt = Instant.parse("2026-06-18T07:00:00Z"),
            plannedTaskIds = listOf("MINE"), plannedMinutes = 99, changeSeq = 0, deletedAt = null,
        )))
        outbox.enqueueRaw(EntityType.DAILY_PLAN, "2026-06-18", OpType.UPDATE, "{}")

        env.server.enqueue(env.jsonResponse(200,
            """{"changes":{"dailyPlans":[{"date":"2026-06-18","committedAt":"2026-06-18T05:00:00Z","plannedTaskIds":["SERVER-OLD"],"plannedMinutes":1,"changeSeq":3,"deletedAt":null}]},"nextSince":50,"hasMore":false}"""))
        runner.pullAll()

        // Unparked op owns the date key -> server value is skipped, local pending plan survives.
        assertEquals(listOf("MINE"), env.db.dailyPlanDao().observe(LocalDate.parse("2026-06-18")).first()!!.plannedTaskIds)
    }

    @Test
    fun `a pulled daily-plan tombstone deletes by date`() = runTest {
        env.db.dailyPlanDao().upsertAll(listOf(DailyPlanEntity(
            date = LocalDate.parse("2026-06-18"), committedAt = Instant.parse("2026-06-18T07:00:00Z"),
            plannedTaskIds = listOf("a"), plannedMinutes = 30, changeSeq = 0, deletedAt = null,
        )))
        env.server.enqueue(env.jsonResponse(200,
            """{"changes":{"dailyPlans":[{"date":"2026-06-18","committedAt":"2026-06-18T07:00:00Z","plannedTaskIds":[],"plannedMinutes":0,"changeSeq":9,"deletedAt":"2026-06-18T11:00:00Z"}]},"nextSince":60,"hasMore":false}"""))
        runner.pullAll()
        assertNull(env.db.dailyPlanDao().getByDate(LocalDate.parse("2026-06-18")))
    }
}
