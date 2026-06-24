package net.qmindtech.tmap.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.qmindtech.tmap.data.local.AppDatabase
import net.qmindtech.tmap.data.local.entities.NoteEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class NoteDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: NoteDao
    private val now = Instant.parse("2026-06-18T08:00:00Z")

    private fun note(id: String, groupId: String? = null, projectId: String? = null, rank: String?) =
        NoteEntity(
            id = id, groupId = groupId, projectId = projectId, title = "ملاحظة", content = "body",
            rank = rank, createdAt = now, updatedAt = now, changeSeq = 0, deletedAt = null, pinnedAt = null,
        )

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.noteDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `observeAll orders by rank nulls-last and round-trips RTL title`() = runTest {
        dao.upsertAll(listOf(note("n2", rank = "0002"), note("n1", rank = "0001"), note("n3", rank = null)))
        val rows = dao.observeAll().first()
        assertEquals(listOf("n1", "n2", "n3"), rows.map { it.id })
        assertEquals("ملاحظة", rows.first().title)
    }

    @Test
    fun `observeByGroup filters to one notebook`() = runTest {
        dao.upsertAll(listOf(note("a", groupId = "g1", rank = "0001"), note("b", groupId = "g2", rank = "0002")))
        assertEquals(listOf("a"), dao.observeByGroup("g1").first().map { it.id })
    }

    @Test
    fun `setPinned stamps and clears the local-only pinnedAt without touching changeSeq`() = runTest {
        dao.upsertAll(listOf(note("n1", rank = "0001")))
        dao.setPinned("n1", now)
        assertEquals(now, dao.getById("n1")!!.pinnedAt)
        assertEquals(0L, dao.getById("n1")!!.changeSeq) // pin is not a synced mutation
        dao.setPinned("n1", null)
        assertNull(dao.getById("n1")!!.pinnedAt)
    }

    @Test
    fun `deleteById and clear remove rows`() = runTest {
        dao.upsertAll(listOf(note("n1", rank = "0001"), note("n2", rank = "0002")))
        dao.deleteById("n1")
        assertNull(dao.getById("n1"))
        dao.clear()
        assertEquals(emptyList<NoteEntity>(), dao.observeAll().first())
    }
}
