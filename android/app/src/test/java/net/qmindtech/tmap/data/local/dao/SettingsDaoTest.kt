package net.qmindtech.tmap.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.qmindtech.tmap.data.local.AppDatabase
import net.qmindtech.tmap.data.local.entities.SettingEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: SettingsDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.settingsDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `upsert then getByKey round-trips including the timeZoneId setting key`() = runTest {
        dao.upsertAll(
            listOf(
                SettingEntity(key = "__timeZoneId", value = "Asia/Riyadh", changeSeq = 3),
                SettingEntity(key = "notificationsEnabled", value = "true", changeSeq = 4),
            ),
        )
        assertEquals("Asia/Riyadh", dao.getByKey("__timeZoneId")?.value)
        assertEquals("true", dao.getByKey("notificationsEnabled")?.value)
    }

    @Test
    fun `upsert replaces an existing key`() = runTest {
        dao.upsertAll(listOf(SettingEntity("k", "v1", 1)))
        dao.upsertAll(listOf(SettingEntity("k", "v2", 2)))
        assertEquals("v2", dao.getByKey("k")?.value)
        assertEquals(1, dao.observeAll().first().size)
    }

    @Test
    fun `deleteByKey removes only that setting`() = runTest {
        dao.upsertAll(listOf(SettingEntity("a", "1", 1), SettingEntity("b", "2", 2)))
        dao.deleteByKey("a")
        assertNull(dao.getByKey("a"))
        assertEquals("2", dao.getByKey("b")?.value)
    }
}
