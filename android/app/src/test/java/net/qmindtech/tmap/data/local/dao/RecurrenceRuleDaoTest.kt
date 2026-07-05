package net.qmindtech.tmap.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.qmindtech.tmap.data.local.AppDatabase
import net.qmindtech.tmap.data.local.entities.RecurrenceRuleEntity
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
class RecurrenceRuleDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: RecurrenceRuleDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.recurrenceRuleDao()
    }

    @After
    fun tearDown() = db.close()

    private fun rule(id: String = "r1") = RecurrenceRuleEntity(
        id = id,
        frequency = "Weekly",
        interval = 2,
        daysOfWeek = listOf(1, 3, 5),
        endType = "Count",
        endCount = 10,
        endDate = LocalDate.parse("2026-12-31"),
        generatedUntil = LocalDate.parse("2026-07-19"),
        createdAt = Instant.parse("2026-07-05T10:00:00Z"),
        updatedAt = Instant.parse("2026-07-05T10:00:00Z"),
        changeSeq = 7L,
        deletedAt = null,
    )

    @Test
    fun `upsert then getById round-trips converted fields`() = runTest {
        dao.upsertAll(listOf(rule()))
        val read = dao.getById("r1")!!
        assertEquals(listOf(1, 3, 5), read.daysOfWeek)
        assertEquals("Weekly", read.frequency)
        assertEquals(10, read.endCount)
        assertEquals(LocalDate.parse("2026-12-31"), read.endDate)
        assertEquals(7L, read.changeSeq)
    }

    @Test
    fun `deleteById removes the row`() = runTest {
        dao.upsertAll(listOf(rule()))
        dao.deleteById("r1")
        assertNull(dao.getById("r1"))
    }

    @Test
    fun `observeAll emits inserted rows`() = runTest {
        dao.upsertAll(listOf(rule("a"), rule("b")))
        assertEquals(2, dao.observeAll().first().size)
    }
}
