package net.qmindtech.tmap.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.qmindtech.tmap.data.local.AppDatabase
import net.qmindtech.tmap.data.local.entities.SyncStateEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class SyncStateDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: SyncStateDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.syncStateDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `get inserts and returns the default row when absent`() = runTest {
        val s = dao.get()
        assertEquals(1, s.id)
        assertEquals(0L, s.lastSeq)
        assertFalse(s.initialSyncComplete)
        assertEquals(1, s.localSchemaVersion)
    }

    @Test
    fun `get is idempotent and does not create duplicate rows`() = runTest {
        dao.get()
        dao.get()
        // upsert with REPLACE on the same PK keeps a single row; observe confirms.
        val observed = dao.observe().filterNotNull().first()
        assertEquals(1, observed.id)
    }

    @Test
    fun `upsert persists an advanced cursor and get reads it back`() = runTest {
        dao.get() // ensure baseline
        dao.upsert(
            SyncStateEntity(
                id = 1, lastSeq = 500, initialSyncComplete = true,
                localSchemaVersion = 1, lastSyncAt = Instant.parse("2026-06-18T09:00:00Z"),
                lastError = null,
            ),
        )
        val s = dao.get()
        assertEquals(500L, s.lastSeq)
        assertEquals(true, s.initialSyncComplete)
        assertEquals(Instant.parse("2026-06-18T09:00:00Z"), s.lastSyncAt)
    }
}
