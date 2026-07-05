package net.qmindtech.tmap.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.qmindtech.tmap.data.local.AppDatabase
import net.qmindtech.tmap.data.local.dao.DailyPlanDao
import net.qmindtech.tmap.data.local.dao.FocusSessionDao
import net.qmindtech.tmap.data.local.dao.NoteDao
import net.qmindtech.tmap.data.local.dao.NoteGroupDao
import net.qmindtech.tmap.data.local.dao.OutboxDao
import net.qmindtech.tmap.data.local.dao.ProjectDao
import net.qmindtech.tmap.data.local.dao.RecurrenceRuleDao
import net.qmindtech.tmap.data.local.dao.SettingsDao
import net.qmindtech.tmap.data.local.dao.SubtaskDao
import net.qmindtech.tmap.data.local.dao.SyncStateDao
import net.qmindtech.tmap.data.local.dao.TaskDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "tmap.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideTaskDao(db: AppDatabase): TaskDao = db.taskDao()

    @Provides
    fun provideSubtaskDao(db: AppDatabase): SubtaskDao = db.subtaskDao()

    @Provides
    fun provideProjectDao(db: AppDatabase): ProjectDao = db.projectDao()

    @Provides
    fun provideSettingsDao(db: AppDatabase): SettingsDao = db.settingsDao()

    @Provides
    fun provideOutboxDao(db: AppDatabase): OutboxDao = db.outboxDao()

    @Provides
    fun provideSyncStateDao(db: AppDatabase): SyncStateDao = db.syncStateDao()

    @Provides
    fun provideNoteDao(db: AppDatabase): NoteDao = db.noteDao()

    @Provides
    fun provideNoteGroupDao(db: AppDatabase): NoteGroupDao = db.noteGroupDao()

    @Provides
    fun provideFocusSessionDao(db: AppDatabase): FocusSessionDao = db.focusSessionDao()

    @Provides
    fun provideDailyPlanDao(db: AppDatabase): DailyPlanDao = db.dailyPlanDao()

    @Provides
    fun provideRecurrenceRuleDao(db: AppDatabase): RecurrenceRuleDao = db.recurrenceRuleDao()
}
