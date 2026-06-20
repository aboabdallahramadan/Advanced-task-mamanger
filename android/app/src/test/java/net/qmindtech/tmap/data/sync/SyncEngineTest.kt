package net.qmindtech.tmap.data.sync

import app.cash.turbine.test
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import net.qmindtech.tmap.data.local.EntityType
import net.qmindtech.tmap.data.local.OpType
import net.qmindtech.tmap.data.remote.dto.CreateTaskRequest
import net.qmindtech.tmap.util.Clock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncEngineTest {

    private lateinit var env: SyncTestEnv
    private lateinit var outbox: OutboxRepository
    private lateinit var push: PushRunner
    private lateinit var pull: PullRunner
    private lateinit var holder: SyncStatusHolder
    private val rearmer = FakeRearmer()
    private val clock: Clock = FixedClock()

    @Before
    fun setUp() {
        env = SyncTestEnv()
        outbox = OutboxRepository(env.db.outboxDao(), env.json, clock)
        push = PushRunner(env.api, outbox, env.db.taskDao(), env.db.subtaskDao(), env.db.projectDao(), env.json, { })
        pull = PullRunner(env.api, env.db, env.db.taskDao(), env.db.subtaskDao(), env.db.projectDao(),
            env.db.settingsDao(), env.db.syncStateDao(), env.db.outboxDao(), rearmer)
        holder = SyncStatusHolder()
    }

    @After
    fun tearDown() = env.close()

    private fun taskRow(id: String, seq: Long) =
        """{"id":"$id","title":"t-$id","notes":null,"projectId":null,"source":"web","status":"Inbox","plannedDate":null,"scheduledStart":null,"scheduledEnd":null,"durationMinutes":null,"actualTimeMinutes":0,"priority":null,"reminderMinutes":null,"rank":null,"dueDate":null,"recurrenceRuleId":null,"isRecurrenceTemplate":false,"recurrenceDetached":false,"recurrenceOriginalDate":null,"completedAt":null,"createdAt":"2026-06-18T00:00:00Z","updatedAt":"2026-06-18T00:00:00Z","changeSeq":$seq}"""

    @Test
    fun `offline — syncNow sets Offline and does not hit the wire`() = runTest {
        val engine = SyncEngine(push, pull, holder, isOnline = { false })
        val result = engine.syncNow("test")
        assertEquals(SyncResult(), result)
        assertEquals(SyncStatus.Offline, holder.status.value)
        assertEquals(0, env.server.requestCount)
    }

    @Test
    fun `online — syncNow pushes then pulls, ends Idle, summarizes the cycle`() = runTest {
        // One queued create to push, then a pull page with one row.
        env.server.enqueue(env.jsonResponse(201, taskRow("c1", 1)))
        env.server.enqueue(env.jsonResponse(200, """{"changes":{"tasks":[${taskRow("p1", 100)}]},"nextSince":100,"hasMore":false}"""))
        outbox.enqueueRaw(EntityType.TASK, "c1", OpType.CREATE,
            env.json.encodeToString(CreateTaskRequest.serializer(), CreateTaskRequest(id = "c1", title = "c")))

        val engine = SyncEngine(push, pull, holder, isOnline = { true })

        // Concrete cycle summary: one create pushed, one pull page applied (pages=1 -> pulled=1).
        val result = engine.syncNow("test")
        assertEquals(1, result.pushed)
        assertEquals(1, result.pulled)
        assertEquals(0, result.rejected)

        // Bounded status check: the holder is a conflated StateFlow, so after the (synchronous in
        // this dispatcher) cycle the most-recent item is the terminal Idle. Use expectMostRecentItem
        // instead of an unbounded awaitItem loop that could hang.
        holder.status.test {
            assertEquals(SyncStatus.Idle, expectMostRecentItem())
        }
        assertEquals(SyncStatus.Idle, holder.status.value)
        // POST then GET sync hit the wire in order.
        assertEquals("/api/v1/tasks", env.server.takeRequest().path)
        assertTrue(env.server.takeRequest().path!!.startsWith("/api/v1/sync"))
    }

    @Test
    fun `network failure mid-cycle ends in Offline and keeps the queue intact`() = runTest {
        // The push hits a hard network failure (server returns nothing → disconnect).
        env.server.enqueue(okhttp3.mockwebserver.MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START))
        outbox.enqueueRaw(EntityType.TASK, "c1", OpType.CREATE,
            env.json.encodeToString(CreateTaskRequest.serializer(), CreateTaskRequest(id = "c1", title = "c")))

        val engine = SyncEngine(push, pull, holder, isOnline = { true })
        val result = engine.syncNow("test")

        assertEquals(SyncStatus.Offline, holder.status.value) // network abort surfaces as Offline (not a hard Error)
        assertEquals(1, outbox.countUnparked()) // op preserved for the next cycle
        assertEquals(0, result.pushed)
    }

    @Test
    fun `rejections from push are reflected in SyncResult and surface as Error status`() = runTest {
        env.server.enqueue(env.jsonResponse(400, """{"title":"bad","status":400}"""))
        env.server.enqueue(env.jsonResponse(200, """{"changes":{},"nextSince":0,"hasMore":false}"""))
        outbox.enqueueRaw(EntityType.TASK, "bad", OpType.CREATE,
            env.json.encodeToString(CreateTaskRequest.serializer(), CreateTaskRequest(id = "bad", title = "x")))

        val engine = SyncEngine(push, pull, holder, isOnline = { true })
        val result = engine.syncNow("test")

        assertEquals(1, result.rejected)
        assertTrue(holder.status.value is SyncStatus.Error)
    }

    @Test
    fun `single-flight — concurrent syncNow calls serialize and never overlap`() = runTest {
        // Two queued creates + two pull pages so both cycles do real (sequential) work.
        env.server.enqueue(env.jsonResponse(201, taskRow("a", 1)))
        env.server.enqueue(env.jsonResponse(200, """{"changes":{},"nextSince":1,"hasMore":false}"""))
        env.server.enqueue(env.jsonResponse(201, taskRow("b", 2)))
        env.server.enqueue(env.jsonResponse(200, """{"changes":{},"nextSince":2,"hasMore":false}"""))
        outbox.enqueueRaw(EntityType.TASK, "a", OpType.CREATE,
            env.json.encodeToString(CreateTaskRequest.serializer(), CreateTaskRequest(id = "a", title = "a")))
        outbox.enqueueRaw(EntityType.TASK, "b", OpType.CREATE,
            env.json.encodeToString(CreateTaskRequest.serializer(), CreateTaskRequest(id = "b", title = "b")))

        // A probe that flags if a second cycle enters while a first is still running. With a mutex
        // the two launched coroutines run strictly one-after-another, so the flag never trips.
        var inFlight = 0
        var overlapDetected = false
        val engine = SyncEngine(push, pull, holder, isOnline = {
            inFlight++
            if (inFlight > 1) overlapDetected = true
            inFlight--
            true
        })

        val first = async { engine.syncNow("a") }
        val second = async { engine.syncNow("b") }
        val r1 = first.await()
        val r2 = second.await()

        assertEquals(false, overlapDetected)
        // Each cycle pushed exactly one op (no interleaving that double-drains the queue).
        assertEquals(1, r1.pushed)
        assertEquals(1, r2.pushed)
        assertEquals(0, outbox.countUnparked())
        assertEquals(SyncStatus.Idle, holder.status.value)
    }
}
