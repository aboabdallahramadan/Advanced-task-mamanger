package net.qmindtech.tmap.data.sync

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
class PushRunner4xxDropTest {

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

    private fun create(id: String) =
        env.json.encodeToString(CreateTaskRequest.serializer(), CreateTaskRequest(id = id, title = id))

    private fun taskRow(id: String) =
        """{"id":"$id","title":"$id","notes":null,"projectId":null,"source":"android","status":"Inbox","plannedDate":null,"scheduledStart":null,"scheduledEnd":null,"durationMinutes":null,"actualTimeMinutes":0,"priority":null,"reminderMinutes":null,"rank":"0|0:","dueDate":null,"recurrenceRuleId":null,"isRecurrenceTemplate":false,"recurrenceDetached":false,"recurrenceOriginalDate":null,"completedAt":null,"createdAt":"2026-06-18T00:00:00Z","updatedAt":"2026-06-18T00:00:00Z","changeSeq":1}"""

    @Test
    fun `a definitive 400 is dropped and surfaced, then the next op still drains`() = runTest {
        outbox.enqueueRaw(EntityType.TASK, "bad", OpType.CREATE, create("bad"))
        outbox.enqueueRaw(EntityType.TASK, "good", OpType.CREATE, create("good"))
        // First op: 400 (validation) with ProblemDetails; second op: 201.
        env.server.enqueue(env.jsonResponse(400, """{"type":"about:blank","title":"priority must be 1-4","status":400}"""))
        env.server.enqueue(env.jsonResponse(201, taskRow("good")))

        val outcome = runner.drain()

        assertEquals(1, outcome.rejected)
        assertEquals(1, outcome.pushed) // "good" drained AFTER the bad op was dropped — no wedge
        assertEquals(0, outbox.countUnparked())
        assertEquals(1, outcome.rejections.size)
        val rej = outcome.rejections.single()
        assertEquals("bad", rej.entityId)
        assertEquals(OpType.CREATE, rej.opType)
        assertTrue(rej.reason.contains("400"))
        assertTrue(rej.reason.contains("priority must be 1-4")) // ProblemDetails.title surfaced
    }
}
