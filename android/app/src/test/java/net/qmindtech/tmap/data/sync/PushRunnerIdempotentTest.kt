package net.qmindtech.tmap.data.sync

import kotlinx.coroutines.test.runTest
import net.qmindtech.tmap.data.local.EntityType
import net.qmindtech.tmap.data.local.OpType
import net.qmindtech.tmap.data.remote.dto.CreateTaskRequest
import net.qmindtech.tmap.util.Clock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PushRunnerIdempotentTest {

    private lateinit var env: SyncTestEnv
    private lateinit var outbox: OutboxRepository
    private lateinit var runner: PushRunner
    private val clock: Clock = FixedClock()

    @Before
    fun setUp() {
        env = SyncTestEnv()
        outbox = OutboxRepository(env.db.outboxDao(), env.json, clock)
        runner = PushRunner(env.api, outbox, env.db.taskDao(), env.db.subtaskDao(), env.db.projectDao(), env.db.syncStateDao(), env.json, { })
    }

    @After
    fun tearDown() = env.close()

    private fun taskJson(id: String, seq: Int) =
        """{"id":"$id","title":"t","notes":null,"projectId":null,"source":"android","status":"Inbox","plannedDate":null,"scheduledStart":null,"scheduledEnd":null,"durationMinutes":null,"actualTimeMinutes":0,"priority":null,"reminderMinutes":null,"rank":"0|0:","dueDate":null,"recurrenceRuleId":null,"isRecurrenceTemplate":false,"recurrenceDetached":false,"recurrenceOriginalDate":null,"completedAt":null,"createdAt":"2026-06-18T00:00:00Z","updatedAt":"2026-06-18T00:00:00Z","changeSeq":$seq}"""

    @Test
    fun `replaying a create for an existing id returns 200 with no duplicate and drains the op`() = runTest {
        val body = env.json.encodeToString(CreateTaskRequest.serializer(), CreateTaskRequest(id = "t1", title = "t"))
        // First push: server creates and returns 201.
        env.server.enqueue(env.jsonResponse(201, taskJson("t1", 1)))
        outbox.enqueueRaw(EntityType.TASK, "t1", OpType.CREATE, body)
        assertEquals(1, runner.drain().pushed)

        // Simulate a crash-then-replay: the SAME create op is enqueued again; the idempotent
        // server returns 200 with the same row (no dupe). The runner treats it as Done.
        env.server.enqueue(env.jsonResponse(200, taskJson("t1", 1)))
        outbox.enqueueRaw(EntityType.TASK, "t1", OpType.CREATE, body)
        val outcome = runner.drain()

        assertEquals(1, outcome.pushed)
        assertEquals(0, outcome.rejected)
        assertEquals(0, outbox.countUnparked())
        // Exactly one create op remained queued for the replay; it was consumed (no wedge, no dupe row added locally).
        assertEquals(2, env.server.requestCount) // two POSTs total across the two drains
    }
}
