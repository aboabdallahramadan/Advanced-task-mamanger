package net.qmindtech.tmap.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.qmindtech.tmap.data.local.AppDatabase
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
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class TaskDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: TaskDao

    private val now = Instant.parse("2026-06-18T08:00:00Z")

    private fun task(
        id: String,
        status: TaskStatus = TaskStatus.Inbox,
        plannedDate: LocalDate? = null,
        isTemplate: Boolean = false,
        labels: List<String> = emptyList(),
        rank: String? = null,
    ) = TaskEntity(
        id = id, title = "task-$id", notes = null, projectId = null, labels = labels,
        source = "android", status = status, plannedDate = plannedDate, scheduledStart = null,
        scheduledEnd = null, durationMinutes = null, actualTimeMinutes = 0, priority = null,
        reminderMinutes = null, rank = rank, dueDate = null, recurrenceRuleId = null,
        isRecurrenceTemplate = isTemplate, recurrenceDetached = false, recurrenceOriginalDate = null,
        completedAt = null, createdAt = now, updatedAt = now, changeSeq = 0,
    )

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.taskDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `upsertAll then getById round-trips all converted fields`() = runTest {
        val e = task("t1", status = TaskStatus.Scheduled, labels = listOf("a", "حجوزات"))
            .copy(plannedDate = LocalDate.of(2026, 6, 18), scheduledStart = now, priority = 3)
        dao.upsertAll(listOf(e))
        val read = dao.getById("t1")!!
        assertEquals(TaskStatus.Scheduled, read.status)
        assertEquals(listOf("a", "حجوزات"), read.labels)
        assertEquals(LocalDate.of(2026, 6, 18), read.plannedDate)
        assertEquals(now, read.scheduledStart)
        assertEquals(3, read.priority)
    }

    @Test
    fun `observeAll emits inserted rows and excludes recurrence templates`() = runTest {
        dao.upsertAll(listOf(task("a"), task("b"), task("tpl", isTemplate = true)))
        dao.observeAll().test {
            val rows = awaitItem()
            assertEquals(setOf("a", "b"), rows.map { it.id }.toSet())
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `observeByStatus filters by status and excludes templates`() = runTest {
        dao.upsertAll(
            listOf(
                task("i1", status = TaskStatus.Inbox),
                task("b1", status = TaskStatus.Backlog),
                task("tpl", status = TaskStatus.Inbox, isTemplate = true),
            ),
        )
        val inbox = dao.observeByStatus(TaskStatus.Inbox).first()
        assertEquals(listOf("i1"), inbox.map { it.id })
    }

    @Test
    fun `observeByPlannedDate returns only that date`() = runTest {
        dao.upsertAll(
            listOf(
                task("d1", plannedDate = LocalDate.of(2026, 6, 18)),
                task("d2", plannedDate = LocalDate.of(2026, 6, 19)),
            ),
        )
        val today = dao.observeByPlannedDate(LocalDate.of(2026, 6, 18)).first()
        assertEquals(listOf("d1"), today.map { it.id })
    }

    @Test
    fun `observeById emits updates then null after delete`() = runTest {
        dao.upsertAll(listOf(task("t1")))
        dao.observeById("t1").test {
            assertEquals("t1", awaitItem()?.id)
            dao.deleteById("t1")
            assertNull(awaitItem())
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `clear removes all rows`() = runTest {
        dao.upsertAll(listOf(task("a"), task("b")))
        dao.clear()
        assertEquals(emptyList<TaskEntity>(), dao.observeAll().first())
    }
}
