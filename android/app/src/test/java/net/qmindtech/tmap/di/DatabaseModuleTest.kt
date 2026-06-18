package net.qmindtech.tmap.di

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import net.qmindtech.tmap.data.local.AppDatabase
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DatabaseModuleTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `dao providers delegate to the database instance`() {
        assertSame(db.taskDao(), DatabaseModule.provideTaskDao(db))
        assertSame(db.subtaskDao(), DatabaseModule.provideSubtaskDao(db))
        assertSame(db.projectDao(), DatabaseModule.provideProjectDao(db))
        assertSame(db.settingsDao(), DatabaseModule.provideSettingsDao(db))
        assertSame(db.outboxDao(), DatabaseModule.provideOutboxDao(db))
        assertSame(db.syncStateDao(), DatabaseModule.provideSyncStateDao(db))
    }

    @Test
    fun `provideDatabase builds a usable file-backed database`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val built = DatabaseModule.provideDatabase(ctx)
        assertNotNull(built.taskDao())
        built.close()
        ctx.deleteDatabase("tmap.db")
    }
}
