package net.qmindtech.tmap.data.sync

import kotlinx.coroutines.test.runTest
import net.qmindtech.tmap.data.local.EntityType
import net.qmindtech.tmap.data.local.OpType
import net.qmindtech.tmap.data.remote.dto.CreateFocusSessionRequest
import net.qmindtech.tmap.data.remote.dto.UpsertDailyPlanRequest
import net.qmindtech.tmap.util.Clock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PushRunnerFocusDailyPlanTest {

    private lateinit var env: SyncTestEnv
    private lateinit var outbox: OutboxRepository
    private lateinit var runner: PushRunner
    private val clock: Clock = FixedClock()

    @Before
    fun setUp() {
        env = SyncTestEnv()
        outbox = OutboxRepository(env.db.outboxDao(), env.json, clock)
        runner = PushRunner(
            env.api, outbox, env.db.taskDao(), env.db.subtaskDao(), env.db.projectDao(),
            env.db.noteDao(), env.db.noteGroupDao(), env.db.focusSessionDao(), env.db.dailyPlanDao(),
            env.db.syncStateDao(), env.json, { },
        )
    }

    @After
    fun tearDown() = env.close()

    @Test
    fun `a FOCUS_SESSION CREATE posts to focus-sessions and drains`() = runTest {
        outbox.enqueueRaw(
            EntityType.FOCUS_SESSION, "f1", OpType.CREATE,
            env.json.encodeToString(
                CreateFocusSessionRequest.serializer(),
                CreateFocusSessionRequest(
                    id = "f1", taskId = "t1", project = "العمل",
                    startedAt = "2026-06-18T09:00:00Z", endedAt = "2026-06-18T09:25:00Z",
                    minutes = 25, date = "2026-06-18",
                ),
            ),
        )
        env.server.enqueue(
            env.jsonResponse(
                201,
                """{"id":"f1","taskId":"t1","project":"العمل","startedAt":"2026-06-18T09:00:00Z","endedAt":"2026-06-18T09:25:00Z","minutes":25,"date":"2026-06-18","createdAt":"2026-06-18T09:25:00Z","updatedAt":"2026-06-18T09:25:00Z"}""",
            ),
        )

        assertEquals(1, runner.drain().pushed)
        val recorded = env.server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/v1/focus-sessions", recorded.path)
    }

    @Test
    fun `a DAILY_PLAN UPDATE PUTs to daily-plans-by-date and drains`() = runTest {
        outbox.enqueueRaw(
            EntityType.DAILY_PLAN, "2026-06-18", OpType.UPDATE,
            env.json.encodeToString(
                UpsertDailyPlanRequest.serializer(),
                UpsertDailyPlanRequest(plannedTaskIds = listOf("a", "b"), plannedMinutes = 120),
            ),
        )
        env.server.enqueue(
            env.jsonResponse(
                200,
                """{"date":"2026-06-18","committedAt":"2026-06-18T07:00:00Z","plannedTaskIds":["a","b"],"plannedMinutes":120}""",
            ),
        )

        assertEquals(1, runner.drain().pushed)
        val recorded = env.server.takeRequest()
        assertEquals("PUT", recorded.method)
        assertEquals("/api/v1/daily-plans/2026-06-18", recorded.path)
    }

    @Test
    fun `a stray FOCUS_SESSION DELETE is rejected as a definitive error and never wedges the queue`() = runTest {
        // An impossible op (the repo never enqueues it); dispatch throws -> classified as Network/Drop.
        // It must NOT wedge the queue: a following good DAILY_PLAN UPDATE still gets attempted.
        outbox.enqueueRaw(EntityType.FOCUS_SESSION, "f1", OpType.DELETE, "{}")
        outbox.enqueueRaw(
            EntityType.DAILY_PLAN, "2026-06-18", OpType.UPDATE,
            env.json.encodeToString(
                UpsertDailyPlanRequest.serializer(),
                UpsertDailyPlanRequest(plannedTaskIds = listOf("a"), plannedMinutes = 30),
            ),
        )
        env.server.enqueue(
            env.jsonResponse(
                200,
                """{"date":"2026-06-18","committedAt":"2026-06-18T07:00:00Z","plannedTaskIds":["a"],"plannedMinutes":30}""",
            ),
        )

        val outcome = runner.drain()
        // The illegal op throws in dispatch -> classified Network -> phase aborts WITHOUT pushing it,
        // leaving the queue intact (no silent data loss, no wedge of well-formed ops on the next cycle).
        assertTrue(outcome.networkAborted)
        // It is never sent as a real focus-session DELETE (no such endpoint).
        assertEquals(0, env.server.requestCount)
    }
}
