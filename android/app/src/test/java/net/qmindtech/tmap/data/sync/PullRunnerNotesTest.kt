package net.qmindtech.tmap.data.sync

import kotlinx.coroutines.test.runTest
import net.qmindtech.tmap.data.local.EntityType
import net.qmindtech.tmap.data.local.OpType
import net.qmindtech.tmap.data.local.entities.NoteEntity
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
class PullRunnerNotesTest {

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
            env.db.noteDao(), env.db.noteGroupDao(), env.db.focusSessionDao(), env.db.dailyPlanDao(),
            env.db.settingsDao(), env.db.syncStateDao(), env.db.outboxDao(), rearmer,
        )
    }

    @After
    fun tearDown() = env.close()

    @Test
    fun `a pulled note upserts and preserves the local-only pinnedAt`() = runTest {
        val pinned = Instant.parse("2026-06-18T06:00:00Z")
        env.db.noteDao().upsertAll(listOf(NoteEntity(
            id = "n1", groupId = null, projectId = null, title = "local", content = "old", rank = null,
            createdAt = pinned, updatedAt = pinned, changeSeq = 0, deletedAt = null, pinnedAt = pinned,
        )))
        env.server.enqueue(env.jsonResponse(200,
            """{"changes":{"notes":[{"id":"n1","groupId":"g1","projectId":null,"title":"SERVER","content":"new","rank":"0001","createdAt":"2026-06-18T00:00:00Z","updatedAt":"2026-06-18T07:00:00Z","changeSeq":9,"deletedAt":null}]},"nextSince":50,"hasMore":false}"""))

        val outcome = runner.pullAll()

        assertEquals(true, outcome.applied)
        val row = env.db.noteDao().getById("n1")!!
        assertEquals("SERVER", row.title)        // server content applied
        assertEquals("g1", row.groupId)
        assertEquals(pinned, row.pinnedAt)        // local pin preserved across the pull
    }

    @Test
    fun `a pulled note tombstone deletes the local row`() = runTest {
        env.db.noteDao().upsertAll(listOf(NoteEntity(
            id = "doomed", groupId = null, projectId = null, title = "x", content = "x", rank = null,
            createdAt = Instant.parse("2026-06-18T00:00:00Z"), updatedAt = Instant.parse("2026-06-18T00:00:00Z"),
            changeSeq = 1, deletedAt = null, pinnedAt = null,
        )))
        env.server.enqueue(env.jsonResponse(200,
            """{"changes":{"notes":[{"id":"doomed","groupId":null,"projectId":null,"title":"x","content":"x","rank":null,"createdAt":"2026-06-18T00:00:00Z","updatedAt":"2026-06-18T00:00:00Z","changeSeq":9,"deletedAt":"2026-06-18T01:00:00Z"}]},"nextSince":50,"hasMore":false}"""))

        runner.pullAll()
        assertNull(env.db.noteDao().getById("doomed"))
    }

    @Test
    fun `a pulled note is shadow-skipped when an unparked outbox op owns its id`() = runTest {
        val localPin = Instant.parse("2026-06-18T05:00:00Z")
        env.db.noteDao().upsertAll(listOf(NoteEntity(
            id = "n2", groupId = null, projectId = null, title = "MINE", content = "local content", rank = null,
            createdAt = Instant.parse("2026-06-18T00:00:00Z"), updatedAt = Instant.parse("2026-06-18T00:30:00Z"),
            changeSeq = 0, deletedAt = null, pinnedAt = localPin,
        )))
        outbox.enqueueRaw(EntityType.NOTE, "n2", OpType.UPDATE,
            env.json.encodeToString(UpdateNoteRequest.serializer(), UpdateNoteRequest(title = "MINE")))

        // Server delivers a different value — must NOT clobber the local pending edit.
        env.server.enqueue(env.jsonResponse(200,
            """{"changes":{"notes":[{"id":"n2","groupId":null,"projectId":null,"title":"SERVER-OLD","content":"server content","rank":null,"createdAt":"2026-06-18T00:00:00Z","updatedAt":"2026-06-18T00:10:00Z","changeSeq":3,"deletedAt":null}]},"nextSince":50,"hasMore":false}"""))

        runner.pullAll()

        val row = env.db.noteDao().getById("n2")!!
        // Shadow rule: the local optimistic title and pin survive.
        assertEquals("MINE", row.title)
        assertEquals(localPin, row.pinnedAt)
    }

    @Test
    fun `a pulled note-group upserts`() = runTest {
        env.server.enqueue(env.jsonResponse(200,
            """{"changes":{"noteGroups":[{"id":"g1","name":"دفتر","emoji":"📓","projectId":null,"rank":"0001","createdAt":"2026-06-18T00:00:00Z","updatedAt":"2026-06-18T00:00:00Z","changeSeq":3,"deletedAt":null}]},"nextSince":50,"hasMore":false}"""))
        runner.pullAll()
        assertEquals("دفتر", env.db.noteGroupDao().getById("g1")!!.name)
    }

    @Test
    fun `a pulled note-group tombstone deletes the local row`() = runTest {
        env.db.noteGroupDao().upsertAll(listOf(
            net.qmindtech.tmap.data.local.entities.NoteGroupEntity(
                id = "doomed-ng", name = "old", emoji = "📝", projectId = null, rank = null,
                createdAt = Instant.parse("2026-06-18T00:00:00Z"),
                updatedAt = Instant.parse("2026-06-18T00:00:00Z"),
                changeSeq = 1, deletedAt = null,
            ),
        ))
        env.server.enqueue(env.jsonResponse(200,
            """{"changes":{"noteGroups":[{"id":"doomed-ng","name":"old","emoji":"📝","projectId":null,"rank":null,"createdAt":"2026-06-18T00:00:00Z","updatedAt":"2026-06-18T00:00:00Z","changeSeq":9,"deletedAt":"2026-06-18T01:00:00Z"}]},"nextSince":50,"hasMore":false}"""))

        runner.pullAll()
        assertNull(env.db.noteGroupDao().getById("doomed-ng"))
    }
}
