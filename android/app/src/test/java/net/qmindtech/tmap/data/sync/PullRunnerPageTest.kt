package net.qmindtech.tmap.data.sync

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PullRunnerPageTest {

    private lateinit var env: SyncTestEnv
    private lateinit var runner: PullRunner
    private val rearmer = FakeRearmer()

    @Before
    fun setUp() {
        env = SyncTestEnv()
        runner = PullRunner(
            env.api, env.db, env.db.taskDao(), env.db.subtaskDao(), env.db.projectDao(),
            env.db.settingsDao(), env.db.syncStateDao(), env.db.outboxDao(), rearmer,
        )
    }

    @After
    fun tearDown() = env.close()

    private fun taskRow(id: String, seq: Long) =
        """{"id":"$id","title":"t-$id","notes":null,"projectId":null,"source":"web","status":"Inbox","plannedDate":null,"scheduledStart":null,"scheduledEnd":null,"durationMinutes":null,"actualTimeMinutes":0,"priority":null,"reminderMinutes":null,"rank":"0|0:","dueDate":null,"recurrenceRuleId":null,"isRecurrenceTemplate":false,"recurrenceDetached":false,"recurrenceOriginalDate":null,"completedAt":null,"createdAt":"2026-06-18T00:00:00Z","updatedAt":"2026-06-18T00:00:00Z","changeSeq":$seq}"""

    @Test
    fun `pull upserts rows advances cursor and paginates across hasMore`() = runTest {
        // Page 1: hasMore=true, nextSince=100; Page 2: hasMore=false, nextSince=200.
        env.server.enqueue(env.jsonResponse(200,
            """{"changes":{"tasks":[${taskRow("a", 50)}]},"nextSince":100,"hasMore":true}"""))
        env.server.enqueue(env.jsonResponse(200,
            """{"changes":{"tasks":[${taskRow("b", 150)}]},"nextSince":200,"hasMore":false}"""))

        val outcome = runner.pullAll()

        assertEquals(2, outcome.pages)
        assertTrue(outcome.applied)
        // Both rows upserted — collect the first emission of the observeAll() Flow and assert size.
        assertEquals(2, env.db.taskDao().observeAll().first().size)
        assertEquals("t-a", env.db.taskDao().getById("a")!!.title)
        assertEquals("t-b", env.db.taskDao().getById("b")!!.title)
        // Cursor advanced to the last nextSince; initialSyncComplete set after a full pass.
        val state = env.db.syncStateDao().get()
        assertEquals(200L, state.lastSeq)
        assertTrue(state.initialSyncComplete)
        // First request floored at 0 (since = lastSeq - overlap, floored). cursor = lastSeq (0).
        val req1 = env.server.takeRequest()
        assertTrue(req1.path!!.contains("since=0"))
        assertTrue(req1.path!!.contains("limit=500"))
        // Exactly two /sync requests were issued (one per page); the queue is now empty.
        val req2 = env.server.takeRequest()
        assertTrue(req2.path!!.contains("/api/v1/sync"))
        assertEquals(0, env.server.requestCount - 2)
        // ReminderRearmer.reconcile called once after the pull.
        assertEquals(1, rearmer.reconcileCalls)
    }
}
