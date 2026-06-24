package net.qmindtech.tmap.data.repository

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import net.qmindtech.tmap.data.local.AppDatabase
import net.qmindtech.tmap.data.local.EntityType
import net.qmindtech.tmap.data.local.OpType
import net.qmindtech.tmap.data.remote.dto.UpsertDailyPlanRequest
import net.qmindtech.tmap.data.sync.OutboxRepository
import net.qmindtech.tmap.util.Clock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDate

// DEVIATION from brief: brief's test asserts sent.committedAt on UpsertDailyPlanRequest, but that
// field does not exist (UpsertDailyPlanRequest has only plannedTaskIds + plannedMinutes — the
// backend stamps committedAt server-side). That assertion is dropped here. committedAt is verified
// on the local entity row instead (row.committedAt == now).

@RunWith(RobolectricTestRunner::class)
class DailyPlanRepositoryImplTest {

    private lateinit var db: AppDatabase
    private lateinit var outbox: OutboxRepository
    private lateinit var scheduler: FakeSyncScheduler
    private lateinit var repo: DailyPlanRepositoryImpl
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val now = Instant.parse("2026-06-18T07:00:00Z")
    private val date = LocalDate.parse("2026-06-18")
    private val clock = object : Clock {
        override fun now() = now
        override fun today() = date
    }

    @Before
    fun setUp() {
        db = repoTestDb()
        outbox = OutboxRepository(db.outboxDao(), json, clock)
        scheduler = FakeSyncScheduler()
        repo = DailyPlanRepositoryImpl(db.dailyPlanDao(), outbox, db, scheduler, clock)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `upsert writes the date-keyed plan and enqueues an UPDATE keyed by ISO date`() = runTest {
        repo.upsert(date, plannedTaskIds = listOf("a", "b"), plannedMinutes = 120)

        // Verify the local Room row has the correct data and committedAt is stamped locally
        val row = db.dailyPlanDao().getByDate(date)!!
        assertEquals(listOf("a", "b"), row.plannedTaskIds)
        assertEquals(120, row.plannedMinutes)
        assertEquals(now, row.committedAt)  // committedAt stamped locally, NOT sent in request

        // Verify outbox op: entityType=DAILY_PLAN, opType=UPDATE, entityId=date-string
        val op = outbox.peek()!!
        assertEquals(EntityType.DAILY_PLAN, op.entityType)
        assertEquals(OpType.UPDATE, op.opType)
        assertEquals("2026-06-18", op.entityId) // date string (yyyy-MM-dd), NOT a Guid

        // Verify payload has only the 2-field request body (no committedAt — backend stamps it)
        val sent = json.decodeFromString(UpsertDailyPlanRequest.serializer(), op.payloadJson)
        assertEquals(listOf("a", "b"), sent.plannedTaskIds)
        assertEquals(120, sent.plannedMinutes)

        assertEquals(1, scheduler.expeditedCount)
    }

    @Test
    fun `a second upsert for the same date replaces the row last-writer-wins`() = runTest {
        repo.upsert(date, listOf("a"), 30)
        repo.upsert(date, listOf("c", "d"), 90)
        assertEquals(listOf("c", "d"), repo.observe(date).first()!!.plannedTaskIds)
        assertEquals(90, db.dailyPlanDao().getByDate(date)!!.plannedMinutes)
    }

    @Test
    fun `observe returns null for a date with no plan`() = runTest {
        val result = repo.observe(LocalDate.parse("2026-01-01")).first()
        assertEquals(null, result)
    }

    @Test
    fun `observeRange returns only plans within the inclusive date window`() = runTest {
        val before = LocalDate.parse("2026-06-15")
        val start = LocalDate.parse("2026-06-16")
        val mid = LocalDate.parse("2026-06-18")
        val end = LocalDate.parse("2026-06-20")
        val after = LocalDate.parse("2026-06-25")

        // Insert plans at various dates using DailyPlanDao directly (bypassing clock for date variety)
        db.dailyPlanDao().upsertAll(
            listOf(
                net.qmindtech.tmap.data.local.entities.DailyPlanEntity(
                    date = before, committedAt = now, plannedTaskIds = listOf("before"),
                    plannedMinutes = 10, changeSeq = 0L,
                ),
                net.qmindtech.tmap.data.local.entities.DailyPlanEntity(
                    date = mid, committedAt = now, plannedTaskIds = listOf("mid"),
                    plannedMinutes = 60, changeSeq = 0L,
                ),
                net.qmindtech.tmap.data.local.entities.DailyPlanEntity(
                    date = after, committedAt = now, plannedTaskIds = listOf("after"),
                    plannedMinutes = 20, changeSeq = 0L,
                ),
            ),
        )

        val results = repo.observeRange(start, end).first()
        assertEquals(1, results.size)
        assertEquals(mid, results.single().date)
        assertEquals(listOf("mid"), results.single().plannedTaskIds)
    }

    @Test
    fun `observeRange returns plans on the boundary dates (inclusive)`() = runTest {
        val startDate = LocalDate.parse("2026-06-16")
        val endDate = LocalDate.parse("2026-06-20")

        db.dailyPlanDao().upsertAll(
            listOf(
                net.qmindtech.tmap.data.local.entities.DailyPlanEntity(
                    date = startDate, committedAt = now, plannedTaskIds = listOf("start"),
                    plannedMinutes = 30, changeSeq = 0L,
                ),
                net.qmindtech.tmap.data.local.entities.DailyPlanEntity(
                    date = endDate, committedAt = now, plannedTaskIds = listOf("end"),
                    plannedMinutes = 45, changeSeq = 0L,
                ),
            ),
        )

        val results = repo.observeRange(startDate, endDate).first()
        assertEquals(2, results.size)
        assertEquals(startDate, results[0].date)
        assertEquals(endDate, results[1].date)
    }
}
