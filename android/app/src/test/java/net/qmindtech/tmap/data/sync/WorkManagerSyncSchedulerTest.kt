package net.qmindtech.tmap.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WorkManagerSyncSchedulerTest {

    private lateinit var ctx: Context
    private lateinit var wm: WorkManager
    private lateinit var scheduler: WorkManagerSyncScheduler

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        val config = Configuration.Builder()
            .setExecutor(androidx.work.testing.SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx, config)
        wm = WorkManager.getInstance(ctx)
        scheduler = WorkManagerSyncScheduler(ctx)
    }

    @Test
    fun `requestExpeditedSync enqueues a unique one-time work under the expedited name`() = runTest {
        scheduler.requestExpeditedSync()
        val infos = wm.getWorkInfosForUniqueWork(EXPEDITED_SYNC_WORK).await()
        assertEquals(1, infos.size)
        assertEquals(WorkInfo.State.ENQUEUED, infos.first().state)
    }

    @Test
    fun `requestExpeditedSync twice keeps a single work (REPLACE debounce)`() = runTest {
        scheduler.requestExpeditedSync()
        scheduler.requestExpeditedSync()
        val infos = wm.getWorkInfosForUniqueWork(EXPEDITED_SYNC_WORK).await()
        assertEquals(1, infos.size)
    }

    @Test
    fun `schedulePeriodic enqueues a unique periodic work constrained to CONNECTED`() = runTest {
        scheduler.schedulePeriodic()
        val infos = wm.getWorkInfosForUniqueWork(PERIODIC_SYNC_WORK).await()
        assertEquals(1, infos.size)
        val info = infos.first()
        assertEquals(WorkInfo.State.ENQUEUED, info.state)
        assertTrue(info.constraints.requiredNetworkType == NetworkType.CONNECTED)
    }

    @Test
    fun `schedulePeriodic twice keeps a single periodic work (KEEP)`() = runTest {
        scheduler.schedulePeriodic()
        scheduler.schedulePeriodic()
        val infos = wm.getWorkInfosForUniqueWork(PERIODIC_SYNC_WORK).await()
        assertEquals(1, infos.size)
    }

    @Test
    fun `cancelAll removes both works`() = runTest {
        scheduler.requestExpeditedSync()
        scheduler.schedulePeriodic()
        scheduler.cancelAll()
        assertTrue(wm.getWorkInfosForUniqueWork(EXPEDITED_SYNC_WORK).await().all { it.state == WorkInfo.State.CANCELLED })
        assertTrue(wm.getWorkInfosForUniqueWork(PERIODIC_SYNC_WORK).await().all { it.state == WorkInfo.State.CANCELLED })
    }
}
