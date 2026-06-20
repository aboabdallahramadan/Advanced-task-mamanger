package net.qmindtech.tmap.data.sync

import kotlinx.coroutines.test.runTest
import net.qmindtech.tmap.data.local.EntityType
import net.qmindtech.tmap.data.local.OpType
import net.qmindtech.tmap.data.remote.dto.CreateTaskRequest
import net.qmindtech.tmap.util.Clock
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PushRunner5xxParkTest {

    private lateinit var env: SyncTestEnv
    private lateinit var outbox: OutboxRepository
    private val backoff = RecordingBackoff()
    private lateinit var runner: PushRunner
    private val clock: Clock = FixedClock()

    @Before
    fun setUp() {
        env = SyncTestEnv()
        outbox = OutboxRepository(env.db.outboxDao(), env.json, clock)
        runner = PushRunner(env.api, outbox, env.db.taskDao(), env.db.subtaskDao(), env.db.projectDao(), env.json, backoff.fn)
    }

    @After
    fun tearDown() = env.close()

    private fun create(id: String) =
        env.json.encodeToString(CreateTaskRequest.serializer(), CreateTaskRequest(id = id, title = id))

    private fun taskRow(id: String) =
        """{"id":"$id","title":"$id","notes":null,"projectId":null,"source":"android","status":"Inbox","plannedDate":null,"scheduledStart":null,"scheduledEnd":null,"durationMinutes":null,"actualTimeMinutes":0,"priority":null,"reminderMinutes":null,"rank":"0|0:","dueDate":null,"recurrenceRuleId":null,"isRecurrenceTemplate":false,"recurrenceDetached":false,"recurrenceOriginalDate":null,"completedAt":null,"createdAt":"2026-06-18T00:00:00Z","updatedAt":"2026-06-18T00:00:00Z","changeSeq":1}"""

    @Test
    fun `repeated 5xx across cycles parks the poison op at the threshold without sleeping`() = runTest {
        outbox.enqueueRaw(EntityType.TASK, "poison", OpType.CREATE, create("poison"))
        // Always 500 for this op. Each cycle does up to 3 in-cycle retry bumps (CYCLE_5XX_RETRIES);
        // the abort branch persists one more bump. Attempts accrue 0 -> 4 -> 8 across cycles, so:
        //   drain 1: bumps 1,2,3 (loop), +1 abort = 4 attempts, NOT parked.
        //   drain 2: bumps 5,6,7 (loop), +1 abort = 8 attempts, NOT parked.
        //   drain 3: bump 9 (backoff), bump 10 -> reaches PARK_THRESHOLD=10 -> parks.
        repeat(50) { env.server.enqueue(env.emptyResponse(500)) }

        // Drains 1 and 2 do not park.
        assertEquals(0, runner.drain().parked)
        assertEquals(0, runner.drain().parked)
        // Drain 3 parks EXACTLY one op at the threshold.
        assertEquals(1, runner.drain().parked)

        // Backoff was invoked, never real-slept, and exactly the expected number of times:
        // drain1 = 3, drain2 = 3, drain3 = 1 (the 10th bump trips the threshold BEFORE its backoff).
        assertEquals(listOf(0, 1, 2, 0, 1, 2, 0), backoff.waited)
        assertEquals(0, outbox.countUnparked()) // parked -> not unparked
        assertNull(outbox.peek()) // peek skips the parked poison op
    }

    @Test
    fun `a parked poison op does not block a later queued op`() = runTest {
        outbox.enqueueRaw(EntityType.TASK, "poison", OpType.CREATE, create("poison"))
        outbox.enqueueRaw(EntityType.TASK, "good", OpType.CREATE, create("good"))
        // Route by request body id (not strict-FIFO enqueue): the "poison" create ALWAYS 500s so it
        // parks; the "good" create gets a 201 whenever it is finally attempted. A strict-FIFO enqueue
        // of 500s ahead of a single 201 cannot deliver that 201 to "good" — the leftover 500s from the
        // poison ladder sit in front of it — so we dispatch by id instead.
        env.server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.clone().readUtf8()
                return when {
                    body.contains("\"id\":\"good\"") -> jsonResponseFor(201, taskRow("good"))
                    else -> MockResponse().setResponseCode(500) // "poison" always 5xx
                }
            }
        }
        // Exactly 3 drains park the poison head (attempts 4 -> 8 -> 10); "good" stays behind it
        // untouched (0 attempts) because parking ends each drain cycle and never reaches "good".
        repeat(3) { runner.drain() } // park ONLY the poison op; do not over-drain into "good"

        // Drain once more: poison is parked, so peekNextUnparked yields "good", which gets its 201.
        val outcome = runner.drain()

        assertEquals(1, outcome.pushed) // "good" drained even though "poison" is parked ahead conceptually
        assertEquals(0, outbox.countUnparked()) // only the parked poison remains, and it is parked
    }

    private fun jsonResponseFor(code: Int, body: String) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
}
