package net.qmindtech.tmap.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.qmindtech.tmap.data.local.AppDatabase
import net.qmindtech.tmap.data.local.entities.FocusSessionEntity
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
class FocusSessionDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: FocusSessionDao

    private fun session(id: String, taskId: String?, date: LocalDate, minutes: Int = 25) =
        FocusSessionEntity(
            id = id, taskId = taskId, project = "العمل",
            startedAt = date.atStartOfDay(java.time.ZoneOffset.UTC).toInstant(),
            endedAt = date.atStartOfDay(java.time.ZoneOffset.UTC).plusMinutes(minutes.toLong()).toInstant(),
            minutes = minutes, date = date,
            createdAt = Instant.parse("2026-06-18T08:00:00Z"),
            updatedAt = Instant.parse("2026-06-18T08:00:00Z"), changeSeq = 0, deletedAt = null,
        )

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.focusSessionDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `observeForTask returns only that task's sessions`() = runTest {
        dao.upsertAll(listOf(
            session("s1", "t1", LocalDate.parse("2026-06-18")),
            session("s2", "t2", LocalDate.parse("2026-06-18")),
        ))
        assertEquals(listOf("s1"), dao.observeForTask("t1").first().map { it.id })
    }

    @Test
    fun `observeForDateRange returns sessions within an inclusive date window`() = runTest {
        dao.upsertAll(listOf(
            session("a", null, LocalDate.parse("2026-06-15")),
            session("b", null, LocalDate.parse("2026-06-18")),
            session("c", null, LocalDate.parse("2026-06-25")),
        ))
        val ids = dao.observeForDateRange(LocalDate.parse("2026-06-16"), LocalDate.parse("2026-06-20"))
            .first().map { it.id }
        assertEquals(listOf("b"), ids)
    }

    @Test
    fun `upsert round-trips fields and deleteById removes`() = runTest {
        dao.upsertAll(listOf(session("s1", "t1", LocalDate.parse("2026-06-18"), minutes = 50)))
        val row = dao.getById("s1")!!
        assertEquals(50, row.minutes)
        assertEquals("العمل", row.project)
        assertEquals(LocalDate.parse("2026-06-18"), row.date)
        dao.deleteById("s1")
        assertNull(dao.getById("s1"))
    }
}
