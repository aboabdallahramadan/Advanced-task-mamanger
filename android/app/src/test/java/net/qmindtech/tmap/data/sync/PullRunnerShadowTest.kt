package net.qmindtech.tmap.data.sync

import kotlinx.coroutines.test.runTest
import net.qmindtech.tmap.data.local.EntityType
import net.qmindtech.tmap.data.local.OpType
import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.local.entities.TaskEntity
import net.qmindtech.tmap.data.remote.dto.UpdateTaskRequest
import net.qmindtech.tmap.util.Clock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class PullRunnerShadowTest {

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
            env.db.settingsDao(), env.db.syncStateDao(), env.db.outboxDao(), rearmer,
        )
    }

    @After
    fun tearDown() = env.close()

    private fun local(id: String, title: String) = TaskEntity(
        id = id, title = title, notes = null, projectId = null, labels = emptyList(),
        source = "android", status = TaskStatus.Inbox, plannedDate = null, scheduledStart = null,
        scheduledEnd = null, durationMinutes = null, actualTimeMinutes = 0, priority = null,
        reminderMinutes = null, rank = null, dueDate = null, recurrenceRuleId = null,
        isRecurrenceTemplate = false, recurrenceDetached = false, recurrenceOriginalDate = null,
        completedAt = null, createdAt = Instant.parse("2026-06-18T00:00:00Z"),
        updatedAt = Instant.parse("2026-06-18T00:30:00Z"), changeSeq = 0,
    )

    @Test
    fun `a pulled row is skipped when an unparked outbox op owns its id`() = runTest {
        // Local optimistic edit: title "MINE", with a pending UPDATE op.
        env.db.taskDao().upsertAll(listOf(local("t1", "MINE")))
        outbox.enqueueRaw(EntityType.TASK, "t1", OpType.UPDATE,
            env.json.encodeToString(UpdateTaskRequest.serializer(), UpdateTaskRequest(title = "MINE")))

        // Server delivers an older value for the same id — must NOT clobber the local pending edit.
        env.server.enqueue(env.jsonResponse(200,
            """{"changes":{"tasks":[{"id":"t1","title":"SERVER-OLD","notes":null,"projectId":null,"source":"web","status":"Inbox","plannedDate":null,"scheduledStart":null,"scheduledEnd":null,"durationMinutes":null,"actualTimeMinutes":0,"priority":null,"reminderMinutes":null,"rank":null,"dueDate":null,"recurrenceRuleId":null,"isRecurrenceTemplate":false,"recurrenceDetached":false,"recurrenceOriginalDate":null,"completedAt":null,"createdAt":"2026-06-18T00:00:00Z","updatedAt":"2026-06-18T00:10:00Z","changeSeq":3,"deletedAt":null}]},"nextSince":50,"hasMore":false}"""))

        runner.pullAll()

        // Shadow rule: the local optimistic title survives.
        assertEquals("MINE", env.db.taskDao().getById("t1")!!.title)
    }

    @Test
    fun `the shadow only protects UNPARKED ops — a parked op does not shield the row`() = runTest {
        env.db.taskDao().upsertAll(listOf(local("t2", "MINE")))
        val seq = outbox.enqueueRaw(EntityType.TASK, "t2", OpType.UPDATE,
            env.json.encodeToString(UpdateTaskRequest.serializer(), UpdateTaskRequest(title = "MINE")))
        outbox.bumpAttempts(seq, parkedAt = Instant.parse("2026-06-18T00:00:00Z")) // park it

        env.server.enqueue(env.jsonResponse(200,
            """{"changes":{"tasks":[{"id":"t2","title":"SERVER-WINS","notes":null,"projectId":null,"source":"web","status":"Inbox","plannedDate":null,"scheduledStart":null,"scheduledEnd":null,"durationMinutes":null,"actualTimeMinutes":0,"priority":null,"reminderMinutes":null,"rank":null,"dueDate":null,"recurrenceRuleId":null,"isRecurrenceTemplate":false,"recurrenceDetached":false,"recurrenceOriginalDate":null,"completedAt":null,"createdAt":"2026-06-18T00:00:00Z","updatedAt":"2026-06-18T00:10:00Z","changeSeq":3,"deletedAt":null}]},"nextSince":50,"hasMore":false}"""))

        runner.pullAll()

        // The op is parked (poison) → no longer shields the row; the server value applies.
        assertEquals("SERVER-WINS", env.db.taskDao().getById("t2")!!.title)
    }
}
