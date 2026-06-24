package net.qmindtech.tmap.data.sync

import kotlinx.coroutines.test.runTest
import net.qmindtech.tmap.data.local.EntityType
import net.qmindtech.tmap.data.local.OpType
import net.qmindtech.tmap.data.remote.dto.CreateTaskRequest
import net.qmindtech.tmap.util.Clock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * BUG 0 part 2 — after a Drop sets pendingRecovery, the next clean pull must run ONE extra from-0
 * recovery pull so local state converges to server truth, then clear the flag.
 */
@RunWith(RobolectricTestRunner::class)
class PullRunnerRecoveryTest {

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

    private fun freshRow(id: String, seq: Long) =
        """{"id":"$id","title":"fresh","notes":null,"projectId":null,"source":"web","status":"Inbox","plannedDate":null,"scheduledStart":null,"scheduledEnd":null,"durationMinutes":null,"actualTimeMinutes":0,"priority":null,"reminderMinutes":null,"rank":null,"dueDate":null,"recurrenceRuleId":null,"isRecurrenceTemplate":false,"recurrenceDetached":false,"recurrenceOriginalDate":null,"completedAt":null,"createdAt":"2026-06-18T00:00:00Z","updatedAt":"2026-06-18T00:00:00Z","changeSeq":$seq}"""

    @Test
    fun `pendingRecovery triggers one from-0 recovery pull then clears the flag`() = runTest {
        // A prior Drop stamped the flag; advance the cursor to prove the recovery pull goes from 0.
        env.db.syncStateDao().upsert(env.db.syncStateDao().get().copy(lastSeq = 9999, pendingRecovery = true))

        // Page 1: the normal delta pull (empty). Page 2: the from-0 recovery pull that re-pulls truth.
        env.server.enqueue(env.jsonResponse(200, """{"changes":{},"nextSince":9999,"hasMore":false}"""))
        env.server.enqueue(env.jsonResponse(200, """{"changes":{"tasks":[${freshRow("server1", 50)}]},"nextSince":50,"hasMore":false}"""))

        runner.pullAll()

        // The recovery pull converged server truth into Room.
        assertNotNull(env.db.taskDao().getById("server1"))
        // The flag is cleared (bounded — one recovery pass, not a loop).
        assertFalse(env.db.syncStateDao().get().pendingRecovery)
        // Exactly two requests: the delta pull + ONE recovery pull.
        assertEquals(2, env.server.requestCount)
        env.server.takeRequest() // delta request
        val recoveryReq = env.server.takeRequest()
        assertTrue(recoveryReq.path!!.contains("since=0"))
    }

    @Test
    fun `pendingRecovery recovery pull is deferred while a parked op exists`() = runTest {
        env.db.syncStateDao().upsert(env.db.syncStateDao().get().copy(lastSeq = 9999, pendingRecovery = true))
        // A parked (poison) op must block the destructive-ish recovery pull, like the full-resync gate.
        outbox.enqueueRaw(
            EntityType.TASK, "poison", OpType.CREATE,
            env.json.encodeToString(CreateTaskRequest.serializer(), CreateTaskRequest(id = "poison", title = "p")),
        )
        env.db.outboxDao().bumpAttempts(env.db.outboxDao().allForTest().single().localSeq, clock.now())

        // Only the normal delta pull happens — no recovery pull while ops are pending.
        env.server.enqueue(env.jsonResponse(200, """{"changes":{},"nextSince":9999,"hasMore":false}"""))

        runner.pullAll()

        assertEquals(1, env.server.requestCount) // recovery deferred
        assertTrue(env.db.syncStateDao().get().pendingRecovery) // flag preserved for a later clean cycle
    }
}
