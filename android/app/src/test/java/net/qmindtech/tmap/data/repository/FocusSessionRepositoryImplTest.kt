package net.qmindtech.tmap.data.repository

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import net.qmindtech.tmap.data.local.AppDatabase
import net.qmindtech.tmap.data.local.EntityType
import net.qmindtech.tmap.data.local.OpType
import net.qmindtech.tmap.data.remote.dto.CreateFocusSessionRequest
import net.qmindtech.tmap.data.sync.OutboxRepository
import net.qmindtech.tmap.util.Clock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class FocusSessionRepositoryImplTest {

    private lateinit var db: AppDatabase
    private lateinit var outbox: OutboxRepository
    private lateinit var scheduler: FakeSyncScheduler
    private lateinit var repo: FocusSessionRepositoryImpl
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val clock = object : Clock {
        override fun now() = Instant.parse("2026-06-18T09:25:00Z")
        override fun today() = LocalDate.parse("2026-06-18")
    }

    @Before
    fun setUp() {
        db = repoTestDb()
        outbox = OutboxRepository(db.outboxDao(), json, clock)
        scheduler = FakeSyncScheduler()
        repo = FocusSessionRepositoryImpl(db.focusSessionDao(), outbox, db, scheduler, clock)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `create writes the session and enqueues exactly one FOCUS_SESSION CREATE`() = runTest {
        val id = repo.create(
            taskId = "t1", project = "العمل",
            startedAt = Instant.parse("2026-06-18T09:00:00Z"),
            endedAt = Instant.parse("2026-06-18T09:25:00Z"),
            minutes = 25, date = LocalDate.parse("2026-06-18"),
        )
        val row = db.focusSessionDao().getById(id)!!
        assertEquals(25, row.minutes)
        assertEquals("العمل", row.project)
        val ops = db.outboxDao().allForTest().filter { it.entityId == id }
        assertEquals(1, ops.size)
        assertEquals(EntityType.FOCUS_SESSION, ops.single().entityType)
        assertEquals(OpType.CREATE, ops.single().opType)
        val sent = json.decodeFromString(CreateFocusSessionRequest.serializer(), ops.single().payloadJson)
        assertEquals(id, sent.id)
        assertEquals("2026-06-18", sent.date)
        assertEquals(1, scheduler.expeditedCount)
        assertEquals(listOf(id), repo.observeForTask("t1").first().map { it.id })
    }

    @Test
    fun `observeForTask returns only sessions for the given task`() = runTest {
        val id1 = repo.create(
            taskId = "t1", project = "work",
            startedAt = Instant.parse("2026-06-18T09:00:00Z"),
            endedAt = Instant.parse("2026-06-18T09:25:00Z"),
            minutes = 25, date = LocalDate.parse("2026-06-18"),
        )
        repo.create(
            taskId = "t2", project = "personal",
            startedAt = Instant.parse("2026-06-18T10:00:00Z"),
            endedAt = Instant.parse("2026-06-18T10:25:00Z"),
            minutes = 25, date = LocalDate.parse("2026-06-18"),
        )
        val ids = repo.observeForTask("t1").first().map { it.id }
        assertEquals(listOf(id1), ids)
    }

    @Test
    fun `observeForDateRange returns sessions within the inclusive date window`() = runTest {
        repo.create(
            taskId = null, project = "work",
            startedAt = Instant.parse("2026-06-15T09:00:00Z"),
            endedAt = Instant.parse("2026-06-15T09:25:00Z"),
            minutes = 25, date = LocalDate.parse("2026-06-15"),
        )
        val inRange = repo.create(
            taskId = null, project = "work",
            startedAt = Instant.parse("2026-06-18T09:00:00Z"),
            endedAt = Instant.parse("2026-06-18T09:25:00Z"),
            minutes = 25, date = LocalDate.parse("2026-06-18"),
        )
        repo.create(
            taskId = null, project = "work",
            startedAt = Instant.parse("2026-06-25T09:00:00Z"),
            endedAt = Instant.parse("2026-06-25T09:25:00Z"),
            minutes = 25, date = LocalDate.parse("2026-06-25"),
        )
        val ids = repo.observeForDateRange(
            LocalDate.parse("2026-06-16"), LocalDate.parse("2026-06-20"),
        ).first().map { it.id }
        assertEquals(listOf(inRange), ids)
    }
}
