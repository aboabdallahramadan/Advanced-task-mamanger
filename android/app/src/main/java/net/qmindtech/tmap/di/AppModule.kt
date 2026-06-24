package net.qmindtech.tmap.di

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.getSystemService
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import net.qmindtech.tmap.data.auth.AuthRepository
import net.qmindtech.tmap.data.auth.AuthRepositoryImpl
import net.qmindtech.tmap.data.auth.KeystoreTokenStore
import net.qmindtech.tmap.data.auth.TokenStore
import net.qmindtech.tmap.data.local.AppDatabase
import net.qmindtech.tmap.data.local.dao.DailyPlanDao
import net.qmindtech.tmap.data.local.dao.FocusSessionDao
import net.qmindtech.tmap.data.local.dao.NoteDao
import net.qmindtech.tmap.data.local.dao.NoteGroupDao
import net.qmindtech.tmap.data.local.dao.OutboxDao
import net.qmindtech.tmap.data.local.dao.ProjectDao
import net.qmindtech.tmap.data.local.dao.SettingsDao
import net.qmindtech.tmap.data.local.dao.SubtaskDao
import net.qmindtech.tmap.data.local.dao.SyncStateDao
import net.qmindtech.tmap.data.local.dao.TaskDao
import net.qmindtech.tmap.data.remote.TmapApiService
import net.qmindtech.tmap.data.repository.ProjectRepository
import net.qmindtech.tmap.data.repository.ProjectRepositoryImpl
import net.qmindtech.tmap.data.repository.SettingsRepository
import net.qmindtech.tmap.data.repository.SettingsRepositoryImpl
import net.qmindtech.tmap.data.repository.SubtaskRepository
import net.qmindtech.tmap.data.repository.SubtaskRepositoryImpl
import net.qmindtech.tmap.data.repository.TaskRepository
import net.qmindtech.tmap.data.repository.TaskRepositoryImpl
import net.qmindtech.tmap.data.sync.OutboxRepository
import net.qmindtech.tmap.data.sync.PullRunner
import net.qmindtech.tmap.data.sync.PushRunner
import net.qmindtech.tmap.data.sync.SyncReminderRearmer
import net.qmindtech.tmap.data.sync.SyncScheduler
import net.qmindtech.tmap.data.sync.WorkManagerSyncScheduler
import net.qmindtech.tmap.ui.capture.QuickCaptureParser
import net.qmindtech.tmap.util.Clock
import net.qmindtech.tmap.util.CoroutineDispatchers
import net.qmindtech.tmap.util.SystemClock
import javax.inject.Singleton

/**
 * The app-wide Hilt module: binds the repository/auth/scheduler interfaces to their impls and
 * provides the sync-engine graph. NetworkModule provides TmapApiService + Json; DatabaseModule
 * provides AppDatabase + the DAOs. util.Clock (SystemClock) is the ONE clock used everywhere —
 * OutboxRepository, SyncStatusHolder and SyncEngine resolve via their own @Inject constructors
 * (no @Provides for them here); this module only supplies what @Inject cannot, e.g. the isOnline probe.
 *
 * TokenStore -> KeystoreTokenStore is bound here (the only TokenStore binding path). The reminder
 * bindings (ReminderScheduler + SyncReminderRearmer) live in ReminderModule (P7.6b), which binds the
 * real AlarmManager-backed AlarmReminderScheduler and ReminderRearmer.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds @Singleton
    abstract fun bindTaskRepository(impl: TaskRepositoryImpl): TaskRepository

    @Binds @Singleton
    abstract fun bindProjectRepository(impl: ProjectRepositoryImpl): ProjectRepository

    @Binds @Singleton
    abstract fun bindSubtaskRepository(impl: SubtaskRepositoryImpl): SubtaskRepository

    @Binds @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository

    @Binds @Singleton
    abstract fun bindAuthRepository(impl: AuthRepositoryImpl): AuthRepository

    @Binds @Singleton
    abstract fun bindTokenStore(impl: KeystoreTokenStore): TokenStore

    @Binds @Singleton
    abstract fun bindSyncScheduler(impl: WorkManagerSyncScheduler): SyncScheduler

    companion object {

        @Provides @Singleton
        fun provideClock(): Clock = SystemClock()

        @Provides @Singleton
        fun provideDispatchers(): CoroutineDispatchers = CoroutineDispatchers()

        @Provides @Singleton
        fun providePushRunner(
            api: TmapApiService,
            outbox: OutboxRepository,
            taskDao: TaskDao,
            subtaskDao: SubtaskDao,
            projectDao: ProjectDao,
            noteDao: NoteDao,
            noteGroupDao: NoteGroupDao,
            focusSessionDao: FocusSessionDao,
            dailyPlanDao: DailyPlanDao,
            syncStateDao: SyncStateDao,
            json: Json,
        ): PushRunner = PushRunner(
            api, outbox, taskDao, subtaskDao, projectDao,
            noteDao, noteGroupDao, focusSessionDao, dailyPlanDao,
            syncStateDao, json, ::syncBackoff,
        )

        @Provides @Singleton
        fun providePullRunner(
            api: TmapApiService,
            db: AppDatabase,
            taskDao: TaskDao,
            subtaskDao: SubtaskDao,
            projectDao: ProjectDao,
            noteDao: NoteDao,
            noteGroupDao: NoteGroupDao,
            focusSessionDao: FocusSessionDao,
            dailyPlanDao: DailyPlanDao,
            settingsDao: SettingsDao,
            syncStateDao: SyncStateDao,
            outboxDao: OutboxDao,
            rearmer: SyncReminderRearmer,
        ): PullRunner = PullRunner(
            api, db, taskDao, subtaskDao, projectDao,
            noteDao, noteGroupDao, focusSessionDao, dailyPlanDao,
            settingsDao, syncStateDao, outboxDao, rearmer,
        )

        /**
         * The connectivity probe the SyncEngine @Inject constructor (P3) takes as its `isOnline`
         * parameter. SyncEngine itself is bound via its own @Inject ctor — no @Provides for it here.
         */
        @Provides @Singleton
        fun provideIsOnline(@ApplicationContext context: Context): () -> Boolean = { isOnline(context) }

        /** Pinned SP3 push backoff: 1 s / 2 s / 4 s by attempt index. */
        private suspend fun syncBackoff(attempt: Int) {
            val millis = 1000L shl attempt.coerceIn(0, 2) // 1000, 2000, 4000
            kotlinx.coroutines.delay(millis)
        }

        @Provides
        fun provideQuickCaptureParser(clock: Clock): QuickCaptureParser = QuickCaptureParser(clock)

        /** ConnectivityManager probe: true when a validated internet-capable network is active. */
        private fun isOnline(context: Context): Boolean {
            val cm = context.getSystemService<ConnectivityManager>() ?: return false
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
    }
}
