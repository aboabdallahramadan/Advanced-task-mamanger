package net.qmindtech.tmap.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.qmindtech.tmap.data.local.AppDatabase
import net.qmindtech.tmap.data.local.entities.SubtaskEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class SubtaskDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: SubtaskDao
    private val now = Instant.parse("2026-06-18T08:00:00Z")

    private fun sub(id: String, taskId: String, order: Int) = SubtaskEntity(
        id = id, taskId = taskId, title = "s-$id", completed = false,
        sortOrder = order, createdAt = now, updatedAt = now, changeSeq = 0,
    )

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.subtaskDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `observeByTask returns only that task's subtasks ordered by sortOrder`() = runTest {
        dao.upsertAll(listOf(sub("s2", "t1", 1), sub("s1", "t1", 0), sub("x", "t2", 0)))
        val rows = dao.observeByTask("t1").first()
        assertEquals(listOf("s1", "s2"), rows.map { it.id })
    }

    @Test
    fun `deleteByTask removes all subtasks of a task only`() = runTest {
        dao.upsertAll(listOf(sub("s1", "t1", 0), sub("s2", "t1", 1), sub("k", "t2", 0)))
        dao.deleteByTask("t1")
        assertEquals(emptyList<SubtaskEntity>(), dao.observeByTask("t1").first())
        assertEquals(listOf("k"), dao.observeByTask("t2").first().map { it.id })
    }

    @Test
    fun `deleteById and getById`() = runTest {
        dao.upsertAll(listOf(sub("s1", "t1", 0)))
        assertEquals("s1", dao.getById("s1")?.id)
        dao.deleteById("s1")
        assertNull(dao.getById("s1"))
    }
}
