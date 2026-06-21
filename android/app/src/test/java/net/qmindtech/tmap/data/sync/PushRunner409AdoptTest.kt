package net.qmindtech.tmap.data.sync

import kotlinx.coroutines.test.runTest
import net.qmindtech.tmap.data.local.EntityType
import net.qmindtech.tmap.data.local.OpType
import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.local.entities.TaskEntity
import net.qmindtech.tmap.data.remote.dto.CreateTaskRequest
import net.qmindtech.tmap.data.remote.dto.UpdateTaskRequest
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
class PushRunner409AdoptTest {

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

    private fun ghost(id: String) = TaskEntity(
        id = id, title = "ghost", notes = null, projectId = null, labels = emptyList(),
        source = "android", status = TaskStatus.Inbox, plannedDate = null, scheduledStart = null,
        scheduledEnd = null, durationMinutes = null, actualTimeMinutes = 0, priority = null,
        reminderMinutes = null, rank = null, dueDate = null, recurrenceRuleId = null,
        isRecurrenceTemplate = false, recurrenceDetached = false, recurrenceOriginalDate = null,
        completedAt = null, createdAt = Instant.parse("2026-06-18T00:00:00Z"),
        updatedAt = Instant.parse("2026-06-18T00:00:00Z"), changeSeq = 0,
    )

    @Test
    fun `409 with existingId remaps the ghost row and the following update op then continues`() = runTest {
        env.db.taskDao().upsertAll(listOf(ghost("ghost1")))
        // CREATE then a dependent UPDATE, both keyed by the ghost id.
        outbox.enqueueRaw(EntityType.TASK, "ghost1", OpType.CREATE,
            env.json.encodeToString(CreateTaskRequest.serializer(), CreateTaskRequest(id = "ghost1", title = "ghost")))
        outbox.enqueueRaw(EntityType.TASK, "ghost1", OpType.UPDATE,
            env.json.encodeToString(UpdateTaskRequest.serializer(), UpdateTaskRequest(title = "edited")))

        // Server rejects the create with 409 + ProblemDetails.extensions.existingId, then accepts the remapped UPDATE.
        env.server.enqueue(env.jsonResponse(409, """{"type":"about:blank","title":"Conflict","status":409,"extensions":{"existingId":"server1"}}"""))
        env.server.enqueue(env.jsonResponse(200, """{"id":"server1","title":"edited","notes":null,"projectId":null,"source":"android","status":"Inbox","plannedDate":null,"scheduledStart":null,"scheduledEnd":null,"durationMinutes":null,"actualTimeMinutes":0,"priority":null,"reminderMinutes":null,"rank":"0|0:","dueDate":null,"recurrenceRuleId":null,"isRecurrenceTemplate":false,"recurrenceDetached":false,"recurrenceOriginalDate":null,"completedAt":null,"createdAt":"2026-06-18T00:00:00Z","updatedAt":"2026-06-18T00:00:00Z","changeSeq":5}"""))

        val outcome = runner.drain()

        assertEquals(1, outcome.adopted)
        assertEquals(1, outcome.pushed) // the remapped UPDATE drained
        assertEquals(0, outbox.countUnparked())
        // Local row was remapped: ghost gone, server id present.
        assertNull(env.db.taskDao().getById("ghost1"))
        assertNotNull(env.db.taskDao().getById("server1"))
        // The UPDATE went to the remapped path.
        env.server.takeRequest() // the 409 POST
        val patch = env.server.takeRequest()
        assertEquals("/api/v1/tasks/server1", patch.path)
    }
}
