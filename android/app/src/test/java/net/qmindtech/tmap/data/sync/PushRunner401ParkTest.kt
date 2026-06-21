package net.qmindtech.tmap.data.sync

import kotlinx.coroutines.test.runTest
import net.qmindtech.tmap.data.local.EntityType
import net.qmindtech.tmap.data.local.OpType
import net.qmindtech.tmap.data.remote.dto.CreateTaskRequest
import net.qmindtech.tmap.util.Clock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Bug-2(b) defense-in-depth regression: a worker can race a logout / definitive-refresh teardown
 * (cancelAll) and reach PushRunner with cleared tokens, so a write 401s. A 401 MUST be PARKED
 * (treated as transient), never Dropped — a dropped op would silently lose a pending write (violates
 * §5.3, which keeps the queue so re-login + full resync reconciles).
 */
@RunWith(RobolectricTestRunner::class)
class PushRunner401ParkTest {

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

    private fun create(id: String) =
        env.json.encodeToString(CreateTaskRequest.serializer(), CreateTaskRequest(id = id, title = id))

    @Test
    fun `a 401 parks the op (not dropped) and the op stays in the outbox`() = runTest {
        outbox.enqueueRaw(EntityType.TASK, "racy", OpType.CREATE, create("racy"))
        env.server.enqueue(env.jsonResponse(401, """{"title":"unauthorized","status":401}"""))

        val outcome = runner.drain()

        assertEquals("401 must PARK, not reject/drop", 1, outcome.parked)
        assertEquals("401 must NOT be dropped", 0, outcome.rejected)
        // The op survives in the outbox (parked, so not counted as unparked, but still present).
        assertEquals("op must NOT be deleted from the outbox", 1, env.db.outboxDao().allForTest().size)
        assertEquals(0, outbox.countUnparked())     // parked -> excluded from the unparked queue
        assertNull(outbox.peek())                   // peek skips the parked op
        // It is surfaced as parked, not as a definitive rejection.
        assertEquals(1, outcome.rejections.size)
        assertTrue(outcome.rejections.single().reason.contains("parked"))
        assertTrue(outcome.rejections.single().reason.contains("401"))
    }
}
