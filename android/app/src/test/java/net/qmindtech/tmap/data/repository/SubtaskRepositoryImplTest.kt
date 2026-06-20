package net.qmindtech.tmap.data.repository

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.qmindtech.tmap.data.local.AppDatabase
import net.qmindtech.tmap.data.local.OpType
import net.qmindtech.tmap.data.remote.dto.CreateSubtaskRequest
import net.qmindtech.tmap.data.sync.OutboxRepository
import net.qmindtech.tmap.util.Clock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class SubtaskRepositoryImplTest {

    private lateinit var db: AppDatabase
    private lateinit var outbox: OutboxRepository
    private lateinit var scheduler: FakeSyncScheduler
    private lateinit var repo: SubtaskRepositoryImpl
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val fixedNow = Instant.parse("2026-06-18T12:00:00Z")
    private val clock = object : Clock {
        override fun now() = fixedNow
        override fun today() = LocalDate.parse("2026-06-18")
    }

    @Before
    fun setUp() {
        db = repoTestDb()
        outbox = OutboxRepository(db.outboxDao(), json, clock)
        scheduler = FakeSyncScheduler()
        repo = SubtaskRepositoryImpl(db.subtaskDao(), outbox, db, scheduler, clock, json)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `create returns id, row observable under its task, CREATE payload carries taskId`() = runTest {
        val id = repo.create(taskId = "t1", title = "step one")
        val row = repo.observeByTask("t1").first().single()
        assertEquals(id, row.id)
        assertEquals("step one", row.title)
        assertEquals("t1", row.taskId)

        // The enqueued CREATE payload MUST include taskId so PushRunner routes to POST /tasks/{taskId}/subtasks.
        val op = outbox.peek()!!
        assertEquals(OpType.CREATE, op.opType)
        val obj = json.parseToJsonElement(op.payloadJson).jsonObject
        assertEquals("t1", obj["taskId"]!!.jsonPrimitive.content)
        assertEquals(id, obj["id"]!!.jsonPrimitive.content)
        assertEquals("step one", obj["title"]!!.jsonPrimitive.content)
        // It still deserializes as a CreateSubtaskRequest (extra taskId key ignored).
        val sent = json.decodeFromString(CreateSubtaskRequest.serializer(), op.payloadJson)
        assertEquals(id, sent.id)
        assertEquals("step one", sent.title)
        assertEquals(1, scheduler.expeditedCount)
    }

    @Test
    fun `update toggles completed and enqueues UPDATE`() = runTest {
        val id = repo.create(taskId = "t1", title = "x")
        scheduler.expeditedCount = 0
        repo.update(id, completed = true)
        assertEquals(true, db.subtaskDao().getById(id)!!.completed)
        assertEquals(OpType.UPDATE, db.outboxDao().allForTest().last { it.entityId == id }.opType)
        assertEquals(1, scheduler.expeditedCount)
    }

    @Test
    fun `delete removes the row and enqueues DELETE`() = runTest {
        val id = repo.create(taskId = "t1", title = "x")
        scheduler.expeditedCount = 0
        repo.delete(id)
        assertNull(db.subtaskDao().getById(id))
        assertEquals(OpType.DELETE, db.outboxDao().allForTest().last { it.entityId == id }.opType)
        assertEquals(1, scheduler.expeditedCount)
    }
}
