package net.qmindtech.tmap.data.sync

import kotlinx.coroutines.test.runTest
import net.qmindtech.tmap.data.local.EntityType
import net.qmindtech.tmap.data.local.OpType
import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.local.entities.TaskEntity
import net.qmindtech.tmap.data.remote.dto.CreateTaskRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import net.qmindtech.tmap.util.Clock
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class PullRunnerFullResyncTest {

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

    private fun stale(id: String) = TaskEntity(
        id = id, title = "stale", notes = null, projectId = null, labels = emptyList(),
        source = null, status = TaskStatus.Inbox, plannedDate = null, scheduledStart = null,
        scheduledEnd = null, durationMinutes = null, actualTimeMinutes = 0, priority = null,
        reminderMinutes = null, rank = null, dueDate = null, recurrenceRuleId = null,
        isRecurrenceTemplate = false, recurrenceDetached = false, recurrenceOriginalDate = null,
        completedAt = null, createdAt = Instant.parse("2026-06-18T00:00:00Z"),
        updatedAt = Instant.parse("2026-06-18T00:00:00Z"), changeSeq = 1,
    )

    private fun freshRow(id: String) =
        """{"id":"$id","title":"fresh","notes":null,"projectId":null,"source":"web","status":"Inbox","plannedDate":null,"scheduledStart":null,"scheduledEnd":null,"durationMinutes":null,"actualTimeMinutes":0,"priority":null,"reminderMinutes":null,"rank":null,"dueDate":null,"recurrenceRuleId":null,"isRecurrenceTemplate":false,"recurrenceDetached":false,"recurrenceOriginalDate":null,"completedAt":null,"createdAt":"2026-06-18T00:00:00Z","updatedAt":"2026-06-18T00:00:00Z","changeSeq":100}"""

    @Test
    fun `outbox empty — fullResyncRequired clears all tables resets cursor and re-pulls from 0`() = runTest {
        env.db.taskDao().upsertAll(listOf(stale("old1")))
        env.db.syncStateDao().upsert(env.db.syncStateDao().get().copy(lastSeq = 9999))

        // Directive page, then the from-0 refill page.
        env.server.enqueue(env.jsonResponse(200, """{"changes":{},"nextSince":12000,"hasMore":false,"fullResyncRequired":true}"""))
        env.server.enqueue(env.jsonResponse(200, """{"changes":{"tasks":[${freshRow("new1")}]},"nextSince":12000,"hasMore":false}"""))

        val outcome = runner.pullAll()

        assertEquals(true, outcome.fullResynced)
        assertNull(env.db.taskDao().getById("old1")) // stale row wiped
        assertNotNull(env.db.taskDao().getById("new1")) // re-pulled
        // The refill request went out with since=0 (cursor=0 from-0 re-pull).
        env.server.takeRequest() // directive request
        val refillReq = env.server.takeRequest()
        assert(refillReq.path!!.contains("since=0"))
        // Cursor adopted at/above the echoed watermark so the directive does not re-trip forever.
        assert(env.db.syncStateDao().get().lastSeq >= 12000L)
    }

    @Test
    fun `outbox NOT empty — fullResyncRequired is deferred, tables untouched, cursor unchanged`() = runTest {
        env.db.taskDao().upsertAll(listOf(stale("keep1")))
        env.db.syncStateDao().upsert(env.db.syncStateDao().get().copy(lastSeq = 9999))
        // A pending op blocks the resync.
        outbox.enqueueRaw(EntityType.TASK, "pending", OpType.CREATE,
            env.json.encodeToString(CreateTaskRequest.serializer(), CreateTaskRequest(id = "pending", title = "p")))

        env.server.enqueue(env.jsonResponse(200, """{"changes":{},"nextSince":12000,"hasMore":false,"fullResyncRequired":true}"""))

        val outcome = runner.pullAll()

        assertEquals(false, outcome.fullResynced) // deferred
        assertNotNull(env.db.taskDao().getById("keep1")) // NOT wiped
        assertEquals(9999L, env.db.syncStateDao().get().lastSeq) // cursor unchanged
        assertEquals(1, env.server.requestCount) // no from-0 refill issued
    }
}
