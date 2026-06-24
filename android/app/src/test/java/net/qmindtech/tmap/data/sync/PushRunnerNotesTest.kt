package net.qmindtech.tmap.data.sync

import kotlinx.coroutines.test.runTest
import net.qmindtech.tmap.data.local.EntityType
import net.qmindtech.tmap.data.local.OpType
import net.qmindtech.tmap.data.local.entities.NoteEntity
import net.qmindtech.tmap.data.remote.dto.CreateNoteRequest
import net.qmindtech.tmap.data.remote.dto.UpdateNoteRequest
import net.qmindtech.tmap.util.Clock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class PushRunnerNotesTest {

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

    private fun ghostNote(id: String) = NoteEntity(
        id = id, groupId = null, projectId = null, title = "ghost", content = "c", rank = null,
        createdAt = Instant.parse("2026-06-18T00:00:00Z"), updatedAt = Instant.parse("2026-06-18T00:00:00Z"),
        changeSeq = 0, deletedAt = null, pinnedAt = null,
    )

    private fun noteRow(id: String) =
        """{"id":"$id","groupId":null,"projectId":null,"title":"t","content":"c","rank":null,"createdAt":"2026-06-18T00:00:00Z","updatedAt":"2026-06-18T00:00:00Z"}"""

    @Test
    fun `a note CREATE then DELETE drain through the notes endpoints`() = runTest {
        outbox.enqueueRaw(EntityType.NOTE, "n1", OpType.CREATE,
            env.json.encodeToString(CreateNoteRequest.serializer(), CreateNoteRequest(id = "n1", title = "t", content = "c")))
        outbox.enqueueRaw(EntityType.NOTE, "n1", OpType.DELETE, "{}")
        env.server.enqueue(env.jsonResponse(201, noteRow("n1")))
        env.server.enqueue(env.emptyResponse(204))

        val outcome = runner.drain()

        assertEquals(2, outcome.pushed)
        assertEquals(0, outbox.countUnparked())
        assertEquals("/api/v1/notes", env.server.takeRequest().path)
        assertEquals("/api/v1/notes/n1", env.server.takeRequest().path)
    }

    @Test
    fun `a note CREATE 409 remaps the ghost row and the following UPDATE`() = runTest {
        env.db.noteDao().upsertAll(listOf(ghostNote("ghost")))
        outbox.enqueueRaw(EntityType.NOTE, "ghost", OpType.CREATE,
            env.json.encodeToString(CreateNoteRequest.serializer(), CreateNoteRequest(id = "ghost", title = "t", content = "c")))
        outbox.enqueueRaw(EntityType.NOTE, "ghost", OpType.UPDATE,
            env.json.encodeToString(UpdateNoteRequest.serializer(), UpdateNoteRequest(title = "edited")))
        env.server.enqueue(env.jsonResponse(409, """{"title":"Conflict","status":409,"extensions":{"existingId":"server1"}}"""))
        env.server.enqueue(env.jsonResponse(200, noteRow("server1")))

        val outcome = runner.drain()

        assertEquals(1, outcome.adopted)
        assertEquals(1, outcome.pushed)
        assertNull(env.db.noteDao().getById("ghost"))
        assertNotNull(env.db.noteDao().getById("server1"))
        env.server.takeRequest() // the 409 POST
        assertEquals("/api/v1/notes/server1", env.server.takeRequest().path)
    }

    @Test
    fun `a note CREATE 400 drops the op and deletes the orphan local row`() = runTest {
        env.db.noteDao().upsertAll(listOf(ghostNote("bad")))
        outbox.enqueueRaw(EntityType.NOTE, "bad", OpType.CREATE,
            env.json.encodeToString(CreateNoteRequest.serializer(), CreateNoteRequest(id = "bad", title = "t", content = "c")))
        env.server.enqueue(env.jsonResponse(400, """{"title":"bad note","status":400}"""))

        val outcome = runner.drain()

        assertEquals(1, outcome.rejected)
        assertNull(env.db.noteDao().getById("bad")) // orphan CREATE row cleaned up
        assertEquals(0, outbox.countUnparked())
    }
}
