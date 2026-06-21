package net.qmindtech.tmap.di

import android.app.AlarmManager
import android.content.Context
import androidx.core.content.getSystemService
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.qmindtech.tmap.data.sync.SyncReminderRearmer
import net.qmindtech.tmap.notifications.AlarmReminderScheduler
import net.qmindtech.tmap.notifications.ReminderRearmer
import net.qmindtech.tmap.notifications.ReminderScheduler
import net.qmindtech.tmap.util.Clock
import javax.inject.Singleton

/**
 * Real reminder bindings (P7.6b, AC7). Replaces P4's Noop placeholders:
 *  - @Provides the concrete AlarmManager-backed [AlarmReminderScheduler] (P7.3) — its ctor is
 *    non-@Inject (context, alarmManager, clock), so it cannot be @Binds-bound directly and is
 *    constructed here, then @Binds-bound to the [ReminderScheduler] seam. That seam is what
 *    TaskRepositoryImpl and ReminderRearmer inject, so creates/updates/deletes and every sync delta
 *    now arm/cancel REAL exact alarms.
 *  - @Binds the concrete [ReminderRearmer] (P7.5) to the main-source [SyncReminderRearmer] (P3) the
 *    PullRunner calls, so every sync delta re-arms/cancels alarms via ReminderRearmer.reconcile().
 *
 * The corresponding P4 AppModule Noop bindings (NoopReminderRearmer + NoopReminderScheduler) are
 * REMOVED in this task — two bindings for the same type is a Hilt duplicate-binding error.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ReminderModule {

    /** PullRunner (P3) calls SyncReminderRearmer.reconcile() after each pull — bind the real impl. */
    @Binds
    @Singleton
    abstract fun bindSyncReminderRearmer(impl: ReminderRearmer): SyncReminderRearmer

    /**
     * The seam TaskRepositoryImpl (P4) and ReminderRearmer (P7.5) inject. Routed to the one provided
     * AlarmReminderScheduler singleton below.
     */
    @Binds
    @Singleton
    abstract fun bindReminderScheduler(impl: AlarmReminderScheduler): ReminderScheduler

    companion object {

        /**
         * The concrete AlarmManager-backed ReminderScheduler (P7.3) has a non-@Inject ctor
         * (context, alarmManager, clock), so it is constructed here from @ApplicationContext, the
         * AlarmManager system service, and the app-wide util.Clock.
         */
        @Provides
        @Singleton
        fun provideAlarmReminderScheduler(
            @ApplicationContext context: Context,
            clock: Clock,
        ): AlarmReminderScheduler {
            val alarmManager = context.getSystemService<AlarmManager>()
                ?: error("AlarmManager system service unavailable")
            return AlarmReminderScheduler(context, alarmManager, clock)
        }
    }
}
