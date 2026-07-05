package net.qmindtech.tmap.data.repository

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import net.qmindtech.tmap.data.local.OpType
import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.recurrence.RecurrenceDraft
import net.qmindtech.tmap.data.recurrence.RecurrenceEndType
import net.qmindtech.tmap.data.recurrence.RecurrenceFrequency
import net.qmindtech.tmap.data.remote.dto.CreateRecurringTaskRequest
import net.qmindtech.tmap.data.sync.OutboxRepository
import net.qmindtech.tmap.util.Clock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class RecurrenceRepositoryImplTest {
    private lateinit var db: net.qmindtech.tmap.data.local.AppDatabase
    private lateinit var outbox: OutboxRepository
    private lateinit var scheduler: FakeSyncScheduler
    private lateinit var repo: RecurrenceRepositoryImpl
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
    private val clock = object : Clock {
        override fun now() = Instant.parse("2026-07-05T09:00:00Z")
        override fun today() = LocalDate.parse("2026-07-05")
    }

    @Before
    fun setUp() {
        db = repoTestDb()
        outbox = OutboxRepository(db.outboxDao(), json, clock)
        scheduler = FakeSyncScheduler()
        repo = RecurrenceRepositoryImpl(db.recurrenceRuleDao(), db.taskDao(), outbox, db, scheduler, clock)
    }

    @After
    fun tearDown() = db.close()

    private fun taskDraft() = TaskDraft(title = "Standup", status = TaskStatus.Planned, plannedDate = LocalDate.parse("2026-07-06"), durationMinutes = 30)
    private fun weekly() = RecurrenceDraft(RecurrenceFrequency.Weekly, 1, listOf(1, 3, 5), RecurrenceEndType.Never, null, null)

    @Test
    fun `createRecurring writes rule + template and queues RECURRENCE CREATE`() = runTest {
        val templateId = repo.createRecurring(taskDraft(), weekly())

        // rule row exists
        assertEquals(1, db.recurrenceRuleDao().observeAll().first().size)
        // template task exists, flagged, anchored, linked
        val template = db.taskDao().getById(templateId)!!
        assertTrue(template.isRecurrenceTemplate)
        assertEquals(LocalDate.parse("2026-07-06"), template.recurrenceOriginalDate)
        val ruleId = template.recurrenceRuleId!!
        // outbox op: RECURRENCE / CREATE keyed by ruleId, payload decodes
        val op = outbox.peek()!!
        assertEquals(OpType.CREATE, op.opType)
        assertEquals(ruleId, op.entityId)
        val req = json.decodeFromString(CreateRecurringTaskRequest.serializer(), op.payloadJson)
        assertEquals("Weekly", req.rule.frequency)
        assertEquals(listOf(1, 3, 5), req.rule.daysOfWeek)
        assertEquals(templateId, req.task.id)
        assertEquals(ruleId, req.rule.id)
        assertEquals(1, scheduler.expeditedCount)
    }

    @Test
    fun `deleteAll removes local rows and queues DELETE scope=all`() = runTest {
        val templateId = repo.createRecurring(taskDraft(), weekly())
        val ruleId = db.taskDao().getById(templateId)!!.recurrenceRuleId!!

        repo.deleteAll(ruleId)

        assertEquals(0, db.recurrenceRuleDao().observeAll().first().size)
        assertTrue(db.taskDao().observeAll().first().none { it.recurrenceRuleId == ruleId })
        val ops = drainToList(outbox)
        val del = ops.last { it.entityId == ruleId && it.opType == OpType.DELETE }
        assertTrue(del.payloadJson, del.payloadJson.contains("\"scope\":\"all\""))
    }

    /** Reads every queued op (parked or not) via the dao, mirroring TaskRepositoryImplTest's helper. */
    private suspend fun drainToList(outbox: OutboxRepository): List<net.qmindtech.tmap.data.local.entities.OutboxOp> {
        return db.outboxDao().allForTest()
    }
}
