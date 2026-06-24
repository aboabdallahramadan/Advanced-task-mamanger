package net.qmindtech.tmap.data.repository

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import net.qmindtech.tmap.data.local.AppDatabase
import net.qmindtech.tmap.data.local.EntityType
import net.qmindtech.tmap.data.local.OpType
import net.qmindtech.tmap.data.remote.dto.CreateNoteGroupRequest
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
class NoteGroupRepositoryImplTest {

    private lateinit var db: AppDatabase
    private lateinit var outbox: OutboxRepository
    private lateinit var scheduler: FakeSyncScheduler
    private lateinit var repo: NoteGroupRepositoryImpl
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val clock = object : Clock {
        override fun now() = Instant.parse("2026-06-18T12:00:00Z")
        override fun today() = LocalDate.parse("2026-06-18")
    }

    @Before
    fun setUp() {
        db = repoTestDb()
        outbox = OutboxRepository(db.outboxDao(), json, clock)
        scheduler = FakeSyncScheduler()
        repo = NoteGroupRepositoryImpl(db.noteGroupDao(), outbox, db, scheduler, clock)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `create enqueues NOTE_GROUP CREATE with client id`() = runTest {
        val id = repo.create(name = "دفتر", emoji = "📓")
        assertEquals("دفتر", db.noteGroupDao().getById(id)!!.name)
        val op = outbox.peek()!!
        assertEquals(EntityType.NOTE_GROUP, op.entityType)
        assertEquals(OpType.CREATE, op.opType)
        assertEquals(id, json.decodeFromString(CreateNoteGroupRequest.serializer(), op.payloadJson).id)
        assertEquals(1, scheduler.expeditedCount)
    }

    @Test
    fun `update and delete enqueue the right ops`() = runTest {
        val id = repo.create(name = "old", emoji = "📁")
        scheduler.expeditedCount = 0
        repo.update(id, name = "new")
        assertEquals("new", db.noteGroupDao().getById(id)!!.name)
        assertEquals(OpType.UPDATE, db.outboxDao().allForTest().last { it.entityId == id }.opType)
        repo.delete(id)
        assertNull(db.noteGroupDao().getById(id))
        assertEquals(OpType.DELETE, db.outboxDao().allForTest().last { it.entityId == id }.opType)
    }

    @Test
    fun `reorder enqueues a single NOTE_GROUP REORDER`() = runTest {
        val a = repo.create(name = "A", emoji = "1")
        val b = repo.create(name = "B", emoji = "2")
        scheduler.expeditedCount = 0
        repo.reorder(listOf(b, a))
        assert(db.noteGroupDao().getById(b)!!.rank!! < db.noteGroupDao().getById(a)!!.rank!!)
        val op = db.outboxDao().allForTest().last { it.opType == OpType.REORDER }
        assertEquals(EntityType.NOTE_GROUP, op.entityType)
        assertEquals(1, scheduler.expeditedCount)
    }
}
