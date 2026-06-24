package net.qmindtech.tmap.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.qmindtech.tmap.data.local.AppDatabase
import net.qmindtech.tmap.data.local.entities.DailyPlanEntity
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
class DailyPlanDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: DailyPlanDao
    private val date = LocalDate.parse("2026-06-18")

    private fun plan(d: LocalDate, ids: List<String>, minutes: Int) = DailyPlanEntity(
        date = d, committedAt = Instant.parse("2026-06-18T07:00:00Z"),
        plannedTaskIds = ids, plannedMinutes = minutes, changeSeq = 0, deletedAt = null,
    )

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.dailyPlanDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `upsert by date replaces the prior plan for that day and round-trips ordered ids`() = runTest {
        dao.upsertAll(listOf(plan(date, listOf("a", "b"), 120)))
        dao.upsertAll(listOf(plan(date, listOf("c", "b", "a"), 240))) // same PK -> REPLACE
        val row = dao.getByDate(date)!!
        assertEquals(listOf("c", "b", "a"), row.plannedTaskIds)
        assertEquals(240, row.plannedMinutes)
        // only one row exists for the date
        assertEquals(listOf("c", "b", "a"), dao.observe(date).first()!!.plannedTaskIds)
    }

    @Test
    fun `observe is null for an unplanned day and deleteByDate removes the plan`() = runTest {
        assertNull(dao.observe(LocalDate.parse("2026-06-19")).first())
        dao.upsertAll(listOf(plan(date, listOf("a"), 30)))
        dao.deleteByDate(date)
        assertNull(dao.getByDate(date))
    }
}
