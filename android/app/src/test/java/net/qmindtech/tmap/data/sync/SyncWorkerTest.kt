package net.qmindtech.tmap.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncWorkerTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    /** A SyncWorker built with an explicit factory injecting the fake engine (bypassing Hilt). */
    private fun buildWorker(engine: SyncEngine): SyncWorker =
        TestListenableWorkerBuilder<SyncWorker>(ctx)
            .setWorkerFactory(object : androidx.work.WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker = SyncWorker(appContext, workerParameters, engine)
            })
            .build()

    @Test
    fun `doWork calls SyncEngine syncNow and returns success on a clean cycle`() = runTest {
        val engine = RecordingSyncEngine(SyncResult(pushed = 2, pulled = 1))
        val worker = buildWorker(engine)

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(1, engine.calls)
    }

    @Test
    fun `doWork returns retry when the engine reports a network abort (nothing pushed, queue intact)`() = runTest {
        // pushed==0 with a pending intent to push is modeled by the engine throwing-free Offline path;
        // the worker treats a thrown exception OR an explicit retry signal as Result.retry().
        val engine = ThrowingSyncEngine()
        val worker = buildWorker(engine)

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
    }
}

/** A SyncEngine test double returning a canned SyncResult and counting calls. */
class RecordingSyncEngine(private val canned: SyncResult) :
    SyncEngine(
        push = throwingPush(),
        pull = throwingPull(),
        statusHolder = SyncStatusHolder(),
        isOnline = { true },
        api = throwingApi(),
        clock = FixedClock(),
    ) {
    var calls = 0
    override suspend fun syncNow(reason: String): SyncResult { calls++; return canned }
}

class ThrowingSyncEngine :
    SyncEngine(
        push = throwingPush(),
        pull = throwingPull(),
        statusHolder = SyncStatusHolder(),
        isOnline = { true },
        api = throwingApi(),
        clock = FixedClock(),
    ) {
    override suspend fun syncNow(reason: String): SyncResult = throw RuntimeException("network down")
}
