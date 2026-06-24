package net.qmindtech.tmap.data.sync

import kotlinx.coroutines.test.runTest
import net.qmindtech.tmap.data.local.EntityType
import net.qmindtech.tmap.data.local.OpType
import net.qmindtech.tmap.data.remote.dto.CreateTaskRequest
import net.qmindtech.tmap.util.Clock
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PushRunnerFifoTest {

    private lateinit var env: SyncTestEnv
    private lateinit var outbox: OutboxRepository
    private lateinit var runner: PushRunner
    private val backoff = RecordingBackoff()
    private val clock: Clock = FixedClock()

    @Before
    fun setUp() {
        env = SyncTestEnv()
        outbox = OutboxRepository(env.db.outboxDao(), env.json, clock)
        runner = PushRunner(
            env.api, outbox, env.db.taskDao(), env.db.subtaskDao(), env.db.projectDao(),
            env.db.noteDao(), env.db.noteGroupDao(), env.db.focusSessionDao(), env.db.dailyPlanDao(),
            env.db.syncStateDao(), env.json, backoff.fn,
        )
    }

    @After
    fun tearDown() = env.close()

    private fun createBody(id: String) =
        env.json.encodeToString(CreateTaskRequest.serializer(), CreateTaskRequest(id = id, title = "t-$id"))

    @Test
    fun `drains queued creates FIFO and deletes each op on 2xx`() = runTest {
        repeat(3) { i ->
            val id = "t$i"
            env.server.enqueue(env.jsonResponse(201, """{"id":"$id","title":"t-$id","notes":null,"projectId":null,"source":"android","status":"Inbox","plannedDate":null,"scheduledStart":null,"scheduledEnd":null,"durationMinutes":null,"actualTimeMinutes":0,"priority":null,"reminderMinutes":null,"rank":"0|$i:","dueDate":null,"recurrenceRuleId":null,"isRecurrenceTemplate":false,"recurrenceDetached":false,"recurrenceOriginalDate":null,"completedAt":null,"createdAt":"2026-06-18T00:00:00Z","updatedAt":"2026-06-18T00:00:00Z","changeSeq":${i + 1}}"""))
            outbox.enqueueRaw(EntityType.TASK, id, OpType.CREATE, createBody(id))
        }

        val outcome = runner.drain()

        assertEquals(3, outcome.pushed)
        assertEquals(0, outcome.rejected)
        assertEquals(0, outbox.countUnparked())
        // FIFO: the three POSTs hit the wire in t0, t1, t2 order — assert BOTH the path and the
        // decoded body id of each request, in order, so the head-first dequeue is actually proven.
        val recorded: List<RecordedRequest> = (0 until 3).map { env.server.takeRequest() }
        assertEquals(listOf("/api/v1/tasks", "/api/v1/tasks", "/api/v1/tasks"), recorded.map { it.path })
        val sentIds = recorded.map {
            env.json.decodeFromString(CreateTaskRequest.serializer(), it.body.readUtf8()).id
        }
        assertEquals(listOf("t0", "t1", "t2"), sentIds)
    }
}
