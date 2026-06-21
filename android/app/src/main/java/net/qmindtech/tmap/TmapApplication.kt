package net.qmindtech.tmap

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import net.qmindtech.tmap.data.sync.SyncScheduler
import net.qmindtech.tmap.notifications.NotificationChannels
import javax.inject.Inject

@HiltAndroidApp
class TmapApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var syncScheduler: SyncScheduler

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.ensureCreated(this)             // P7.2 — high-importance reminders channel
        // Schedule the 15-min periodic safety-net sync once at boot (unique KEEP — idempotent).
        syncScheduler.schedulePeriodic()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
