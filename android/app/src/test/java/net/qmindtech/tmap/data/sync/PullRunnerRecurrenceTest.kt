package net.qmindtech.tmap.data.sync

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PullRunnerRecurrenceTest {
    private lateinit var env: SyncTestEnv
    private lateinit var runner: PullRunner
    private val rearmer = FakeRearmer()

    @Before
    fun setUp() {
        env = SyncTestEnv()
        runner = PullRunner(
            env.api, env.db, env.db.taskDao(), env.db.subtaskDao(), env.db.projectDao(),
            env.db.noteDao(), env.db.noteGroupDao(), env.db.focusSessionDao(), env.db.dailyPlanDao(),
            env.db.settingsDao(), env.db.syncStateDao(), env.db.outboxDao(), rearmer,
        )
    }

    @After
    fun tearDown() = env.close()

    private fun ruleJson(id: String, seq: Int, deleted: Boolean = false) = """
        {"id":"$id","frequency":"Weekly","interval":1,"daysOfWeek":[1,3],"endType":"Never",
         "endCount":null,"endDate":null,"generatedUntil":null,
         "createdAt":"2026-07-05T10:00:00+00:00","updatedAt":"2026-07-05T10:00:00+00:00",
         "changeSeq":$seq${if (deleted) ",\"deletedAt\":\"2026-07-05T11:00:00+00:00\"" else ""}}
    """.trimIndent()

    @Test
    fun `pulled recurrence rule is ingested then tombstoned`() = runTest {
        env.server.enqueue(env.jsonResponse(200, """{"changes":{"recurrenceRules":[${ruleJson("r1", 5)}]},"nextSince":5,"hasMore":false}"""))
        runner.pullAll()
        assertNotNull(env.db.recurrenceRuleDao().getById("r1"))

        env.server.enqueue(env.jsonResponse(200, """{"changes":{"recurrenceRules":[${ruleJson("r1", 6, deleted = true)}]},"nextSince":6,"hasMore":false}"""))
        runner.pullAll()
        assertNull(env.db.recurrenceRuleDao().getById("r1"))
    }
}
