package net.qmindtech.tmap.data.repository

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import net.qmindtech.tmap.data.local.AppDatabase
import net.qmindtech.tmap.data.local.OpType
import net.qmindtech.tmap.data.remote.dto.CreateProjectRequest
import net.qmindtech.tmap.data.remote.dto.ReorderItem
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
class ProjectRepositoryImplTest {

    private lateinit var db: AppDatabase
    private lateinit var outbox: OutboxRepository
    private lateinit var scheduler: FakeSyncScheduler
    private lateinit var repo: ProjectRepositoryImpl
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
        repo = ProjectRepositoryImpl(db.projectDao(), outbox, db, scheduler, clock)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `create returns id, row observable, enqueues CREATE, nudges sync`() = runTest {
        val id = repo.create(name = "حجوزات عيادات", color = "#22c55e", emoji = "🩺")
        val row = db.projectDao().getById(id)!!
        assertEquals("حجوزات عيادات", row.name)
        assertEquals("#22c55e", row.color)
        assertEquals(0L, row.changeSeq)
        val op = outbox.peek()!!
        assertEquals(OpType.CREATE, op.opType)
        val sent = json.decodeFromString(CreateProjectRequest.serializer(), op.payloadJson)
        assertEquals("حجوزات عيادات", sent.name)
        assertEquals(id, sent.id)
        assertEquals(1, scheduler.expeditedCount)
    }

    @Test
    fun `update changes only provided fields and enqueues UPDATE`() = runTest {
        val id = repo.create(name = "old", color = "#000", emoji = "📁")
        scheduler.expeditedCount = 0
        repo.update(id, name = "new")
        val row = db.projectDao().getById(id)!!
        assertEquals("new", row.name)
        assertEquals("#000", row.color)
        assertEquals(OpType.UPDATE, db.outboxDao().allForTest().last { it.entityId == id }.opType)
        assertEquals(1, scheduler.expeditedCount)
    }

    @Test
    fun `delete hard-deletes the row and enqueues DELETE`() = runTest {
        val id = repo.create(name = "gone", color = "#000", emoji = "📁")
        scheduler.expeditedCount = 0
        repo.delete(id)
        assertNull(db.projectDao().getById(id))
        assertEquals(OpType.DELETE, db.outboxDao().allForTest().last { it.entityId == id }.opType)
        assertEquals(1, scheduler.expeditedCount)
    }

    @Test
    fun `reorder rewrites local ranks and enqueues a single REORDER carrying ReorderItem list`() = runTest {
        val a = repo.create(name = "A", color = "#000", emoji = "1")
        val b = repo.create(name = "B", color = "#000", emoji = "2")
        scheduler.expeditedCount = 0

        repo.reorder(listOf(b, a)) // b first now

        // Local ranks are lexicographically ordered b < a.
        val rankB = db.projectDao().getById(b)!!.rank!!
        val rankA = db.projectDao().getById(a)!!.rank!!
        assert(rankB < rankA)

        val op = db.outboxDao().allForTest().last { it.opType == OpType.REORDER }
        val items = json.decodeFromString(ListSerializer(ReorderItem.serializer()), op.payloadJson)
        assertEquals(listOf(b, a), items.map { it.id })
        assertEquals(rankB, items.first().rank)
        assertEquals(1, scheduler.expeditedCount)
    }
}
