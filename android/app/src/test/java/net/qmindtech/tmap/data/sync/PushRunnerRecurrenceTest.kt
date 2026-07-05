package net.qmindtech.tmap.data.sync

import kotlinx.coroutines.test.runTest
import net.qmindtech.tmap.data.local.EntityType
import net.qmindtech.tmap.data.local.OpType
import net.qmindtech.tmap.util.Clock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PushRunnerRecurrenceTest {
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

    @Test
    fun `recurrence create posts to recurrence endpoint`() = runTest {
        val body = """{"task":{"title":"Standup","notes":"","projectId":null,"labels":[],
            "source":"android","plannedDate":"2026-07-06","durationMinutes":30,"priority":null,
            "reminderMinutes":0,"id":"t1"},"rule":{"frequency":"Daily","interval":1,"daysOfWeek":[],
            "endType":"Never","endCount":null,"endDate":null,"id":"r1"}}""".trimIndent()
        outbox.enqueueRaw(EntityType.RECURRENCE, "r1", OpType.CREATE, body)
        env.server.enqueue(env.jsonResponse(201, "[]"))

        val outcome = runner.drain()

        assertEquals(1, outcome.pushed)
        val req = env.server.takeRequest()
        assertEquals("POST", req.method)
        assertTrue(req.path!!, req.path!!.endsWith("/api/v1/recurrence"))
    }

    @Test
    fun `recurrence delete-all sends DELETE and 404 counts as success`() = runTest {
        outbox.enqueueRaw(EntityType.RECURRENCE, "r1", OpType.DELETE, """{"scope":"all"}""")
        env.server.enqueue(env.jsonResponse(404, ""))   // idempotent tombstone

        val outcome = runner.drain()

        assertEquals(1, outcome.pushed)
        val req = env.server.takeRequest()
        // The path is shared with the PATCH updateRule endpoint, so the method must be pinned.
        assertEquals("DELETE", req.method)
        assertTrue(req.path!!.endsWith("/api/v1/recurrence/rules/r1"))
    }

    @Test
    fun `recurrence update-rule sends PATCH to rules path`() = runTest {
        val body = """{"frequency":"Weekly","interval":2,"daysOfWeek":[1,3],
            "endType":"Never","endCount":null,"endDate":null}""".trimIndent()
        outbox.enqueueRaw(EntityType.RECURRENCE, "r1", OpType.UPDATE, body)
        env.server.enqueue(env.jsonResponse(200, ""))

        val outcome = runner.drain()

        assertEquals(1, outcome.pushed)
        val req = env.server.takeRequest()
        assertEquals("PATCH", req.method)
        assertTrue(req.path!!, req.path!!.endsWith("/api/v1/recurrence/rules/r1"))
    }

    @Test
    fun `recurrence delete-future posts to delete-future path`() = runTest {
        outbox.enqueueRaw(EntityType.RECURRENCE, "r1", OpType.DELETE, """{"scope":"future","fromDate":"2026-07-10"}""")
        env.server.enqueue(env.jsonResponse(200, ""))

        runner.drain()

        val req = env.server.takeRequest()
        assertEquals("POST", req.method)
        assertTrue(req.path!!, req.path!!.endsWith("/api/v1/recurrence/rules/r1/delete-future"))
    }
}
