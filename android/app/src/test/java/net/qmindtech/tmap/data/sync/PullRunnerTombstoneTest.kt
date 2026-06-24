package net.qmindtech.tmap.data.sync

import kotlinx.coroutines.test.runTest
import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.local.entities.TaskEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class PullRunnerTombstoneTest {

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

    private fun seed(id: String) = TaskEntity(
        id = id, title = "live", notes = null, projectId = null, labels = emptyList(),
        source = null, status = TaskStatus.Inbox, plannedDate = null, scheduledStart = null,
        scheduledEnd = null, durationMinutes = null, actualTimeMinutes = 0, priority = null,
        reminderMinutes = null, rank = null, dueDate = null, recurrenceRuleId = null,
        isRecurrenceTemplate = false, recurrenceDetached = false, recurrenceOriginalDate = null,
        completedAt = null, createdAt = Instant.parse("2026-06-18T00:00:00Z"),
        updatedAt = Instant.parse("2026-06-18T00:00:00Z"), changeSeq = 1,
    )

    @Test
    fun `a pulled tombstone deletes the local row and is reported in deletedIds via reconcile`() = runTest {
        env.db.taskDao().upsertAll(listOf(seed("doomed")))
        env.server.enqueue(env.jsonResponse(200,
            """{"changes":{"tasks":[{"id":"doomed","title":"live","notes":null,"projectId":null,"source":null,"status":"Inbox","plannedDate":null,"scheduledStart":null,"scheduledEnd":null,"durationMinutes":null,"actualTimeMinutes":0,"priority":null,"reminderMinutes":null,"rank":null,"dueDate":null,"recurrenceRuleId":null,"isRecurrenceTemplate":false,"recurrenceDetached":false,"recurrenceOriginalDate":null,"completedAt":null,"createdAt":"2026-06-18T00:00:00Z","updatedAt":"2026-06-18T00:00:00Z","changeSeq":9,"deletedAt":"2026-06-18T01:00:00Z"}]},"nextSince":50,"hasMore":false}"""))

        val outcome = runner.pullAll()

        assertEquals(true, outcome.applied)
        assertNull(env.db.taskDao().getById("doomed")) // hard-deleted by the tombstone
        assertEquals(listOf("doomed"), rearmer.deletedSeen) // reconcile told to cancel its alarm
    }
}
