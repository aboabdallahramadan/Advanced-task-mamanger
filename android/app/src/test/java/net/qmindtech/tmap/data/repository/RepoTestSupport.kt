package net.qmindtech.tmap.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import net.qmindtech.tmap.data.local.AppDatabase
import net.qmindtech.tmap.data.sync.SyncScheduler

/** Builds an in-memory AppDatabase for repository tests. */
fun repoTestDb(): AppDatabase = Room.inMemoryDatabaseBuilder(
    ApplicationProvider.getApplicationContext<Context>(),
    AppDatabase::class.java,
).allowMainThreadQueries().build()

/** Records that a repository write nudged the sync engine, without touching WorkManager. */
class FakeSyncScheduler : SyncScheduler {
    var expeditedCount = 0
    var periodicCount = 0
    var cancelCount = 0
    override fun requestExpeditedSync() { expeditedCount++ }
    override fun schedulePeriodic() { periodicCount++ }
    override fun cancelAll() { cancelCount++ }
}
