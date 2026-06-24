package net.qmindtech.tmap.data.repository

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import net.qmindtech.tmap.data.local.AppDatabase
import net.qmindtech.tmap.data.local.EntityType
import net.qmindtech.tmap.data.local.OpType
import net.qmindtech.tmap.data.remote.dto.CreateNoteRequest
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
class NoteRepositoryImplTest {

    private lateinit var db: AppDatabase
    private lateinit var outbox: OutboxRepository
    private lateinit var scheduler: FakeSyncScheduler
    private lateinit var repo: NoteRepositoryImpl
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val now = Instant.parse("2026-06-18T12:00:00Z")
    private val clock = object : Clock {
        override fun now() = now
        override fun today() = LocalDate.parse("2026-06-18")
    }

    @Before
    fun setUp() {
        db = repoTestDb()
        outbox = OutboxRepository(db.outboxDao(), json, clock)
        scheduler = FakeSyncScheduler()
        repo = NoteRepositoryImpl(db.noteDao(), outbox, db, scheduler, clock)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `create writes row, enqueues CREATE with client id, nudges sync`() = runTest {
        val id = repo.create(title = "ملاحظة", content = "body", groupId = "g1")
        val row = db.noteDao().getById(id)!!
        assertEquals("ملاحظة", row.title)
        assertEquals("g1", row.groupId)
        val op = outbox.peek()!!
        assertEquals(EntityType.NOTE, op.entityType)
        assertEquals(OpType.CREATE, op.opType)
        val sent = json.decodeFromString(CreateNoteRequest.serializer(), op.payloadJson)
        assertEquals(id, sent.id)
        assertEquals("body", sent.content)
        assertEquals(1, scheduler.expeditedCount)
    }

    @Test
    fun `update changes only provided fields and enqueues UPDATE`() = runTest {
        val id = repo.create(title = "old", content = "c")
        scheduler.expeditedCount = 0
        repo.update(id, title = "new")
        val row = db.noteDao().getById(id)!!
        assertEquals("new", row.title)
        assertEquals("c", row.content)
        assertEquals(OpType.UPDATE, db.outboxDao().allForTest().last { it.entityId == id }.opType)
        assertEquals(1, scheduler.expeditedCount)
    }

    @Test
    fun `delete hard-deletes and enqueues DELETE`() = runTest {
        val id = repo.create(title = "gone", content = "c")
        scheduler.expeditedCount = 0
        repo.delete(id)
        assertNull(db.noteDao().getById(id))
        assertEquals(OpType.DELETE, db.outboxDao().allForTest().last { it.entityId == id }.opType)
        assertEquals(1, scheduler.expeditedCount)
    }

    @Test
    fun `setPinned is local-only - stamps pinnedAt, enqueues NOTHING, does not nudge sync`() = runTest {
        val id = repo.create(title = "n", content = "c")
        val opsBefore = db.outboxDao().allForTest().size
        scheduler.expeditedCount = 0

        repo.setPinned(id, true)
        assertEquals(now, db.noteDao().getById(id)!!.pinnedAt)
        repo.setPinned(id, false)
        assertNull(db.noteDao().getById(id)!!.pinnedAt)

        assertEquals(opsBefore, db.outboxDao().allForTest().size) // no pin op enqueued
        assertEquals(0, scheduler.expeditedCount)                  // pin never nudges sync
    }

    @Test
    fun `reorder rewrites local ranks and enqueues a single REORDER`() = runTest {
        val a = repo.create(title = "A", content = "c")
        val b = repo.create(title = "B", content = "c")
        scheduler.expeditedCount = 0
        repo.reorder(listOf(b, a))
        assert(db.noteDao().getById(b)!!.rank!! < db.noteDao().getById(a)!!.rank!!)
        val op = db.outboxDao().allForTest().last { it.opType == OpType.REORDER }
        assertEquals(EntityType.NOTE, op.entityType)
        val items = json.decodeFromString(ListSerializer(ReorderItem.serializer()), op.payloadJson)
        assertEquals(listOf(b, a), items.map { it.id })
        assertEquals(1, scheduler.expeditedCount)
    }

    @Test
    fun `observeAll filters by group then project then all`() = runTest {
        val g = repo.create(title = "g", content = "c", groupId = "g1")
        val p = repo.create(title = "p", content = "c", projectId = "pr1")
        repo.create(title = "free", content = "c")
        assertEquals(listOf(g), repo.observeAll(groupId = "g1", projectId = null).first().map { it.id })
        assertEquals(listOf(p), repo.observeAll(groupId = null, projectId = "pr1").first().map { it.id })
        assertEquals(3, repo.observeAll(groupId = null, projectId = null).first().size)
    }
}
