package net.qmindtech.tmap.notifications

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.test.runTest
import net.qmindtech.tmap.data.local.AppDatabase
import net.qmindtech.tmap.data.local.entities.TaskEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RearmWorkerTest {

    private lateinit var db: AppDatabase
    private val arming = object : ReminderScheduler {
        val armed = mutableListOf<String>()
        override fun arm(task: TaskEntity) { armed += task.id }
        override fun cancel(taskId: String) {}
        override fun canScheduleExact(): Boolean = true
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `RearmWorker calls rearmAll and returns success`() = runTest {
        val rearmer = ReminderRearmer(arming, db.taskDao())
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val worker = TestListenableWorkerBuilder<RearmWorker>(ctx)
            .setWorkerFactory(rearmWorkerFactory(rearmer))
            .build()
        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.success(), result)
        // empty store -> nothing armed, but rearmAll ran without throwing.
        assertEquals(0, arming.armed.size)
    }

    /** Minimal WorkerFactory injecting the test's ReminderRearmer (mirrors the HiltWorkerFactory at runtime). */
    private fun rearmWorkerFactory(rearmer: ReminderRearmer) =
        object : androidx.work.WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: androidx.work.WorkerParameters,
            ): ListenableWorker = RearmWorker(appContext, workerParameters, rearmer)
        }
}
