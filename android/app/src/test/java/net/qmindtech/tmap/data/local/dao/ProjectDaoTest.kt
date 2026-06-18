package net.qmindtech.tmap.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.qmindtech.tmap.data.local.AppDatabase
import net.qmindtech.tmap.data.local.entities.ProjectEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class ProjectDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: ProjectDao
    private val now = Instant.parse("2026-06-18T08:00:00Z")

    private fun project(id: String, rank: String?) = ProjectEntity(
        id = id, name = "حجوزات عيادات", color = "#4F8DF7", emoji = "📋",
        rank = rank, actualTimeMinutes = 0, createdAt = now, updatedAt = now, changeSeq = 0,
    )

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.projectDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `observeAll orders by rank with nulls last and round-trips RTL name`() = runTest {
        dao.upsertAll(listOf(project("p2", "0|hzzzzz:"), project("p1", "0|haaaaa:"), project("p3", null)))
        val rows = dao.observeAll().first()
        assertEquals(listOf("p1", "p2", "p3"), rows.map { it.id })
        assertEquals("حجوزات عيادات", rows.first().name)
    }

    @Test
    fun `deleteById removes one project`() = runTest {
        dao.upsertAll(listOf(project("p1", "0|a:"), project("p2", "0|b:")))
        dao.deleteById("p1")
        assertNull(dao.getById("p1"))
        assertEquals("p2", dao.getById("p2")?.id)
    }

    @Test
    fun `clear removes all projects`() = runTest {
        dao.upsertAll(listOf(project("p1", "0|a:")))
        dao.clear()
        assertEquals(emptyList<ProjectEntity>(), dao.observeAll().first())
    }
}
