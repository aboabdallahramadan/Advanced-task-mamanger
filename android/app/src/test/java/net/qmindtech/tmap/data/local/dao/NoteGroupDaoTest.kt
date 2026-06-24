package net.qmindtech.tmap.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.qmindtech.tmap.data.local.AppDatabase
import net.qmindtech.tmap.data.local.entities.NoteGroupEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class NoteGroupDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: NoteGroupDao
    private val now = Instant.parse("2026-06-18T08:00:00Z")

    private fun group(id: String, rank: String?) = NoteGroupEntity(
        id = id, name = "دفتر", emoji = "📓", projectId = null,
        rank = rank, createdAt = now, updatedAt = now, changeSeq = 0, deletedAt = null,
    )

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.noteGroupDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `observeAll orders by rank nulls-last and round-trips RTL name and emoji`() = runTest {
        dao.upsertAll(listOf(group("g2", "0002"), group("g1", "0001"), group("g3", null)))
        val rows = dao.observeAll().first()
        assertEquals(listOf("g1", "g2", "g3"), rows.map { it.id })
        assertEquals("دفتر", rows.first().name)
        assertEquals("📓", rows.first().emoji)
    }

    @Test
    fun `deleteById and clear remove rows`() = runTest {
        dao.upsertAll(listOf(group("g1", "0001"), group("g2", "0002")))
        dao.deleteById("g1")
        assertNull(dao.getById("g1"))
        dao.clear()
        assertEquals(emptyList<NoteGroupEntity>(), dao.observeAll().first())
    }
}
