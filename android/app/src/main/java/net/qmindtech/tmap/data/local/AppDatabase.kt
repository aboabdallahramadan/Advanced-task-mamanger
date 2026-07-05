package net.qmindtech.tmap.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
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
import net.qmindtech.tmap.data.local.entities.DailyPlanEntity
import net.qmindtech.tmap.data.local.entities.FocusSessionEntity
import net.qmindtech.tmap.data.local.entities.NoteEntity
import net.qmindtech.tmap.data.local.entities.NoteGroupEntity
import net.qmindtech.tmap.data.local.entities.OutboxOp
import net.qmindtech.tmap.data.local.entities.ProjectEntity
import net.qmindtech.tmap.data.local.entities.RecurrenceRuleEntity
import net.qmindtech.tmap.data.local.entities.SettingEntity
import net.qmindtech.tmap.data.local.entities.SubtaskEntity
import net.qmindtech.tmap.data.local.entities.SyncStateEntity
import net.qmindtech.tmap.data.local.entities.TaskEntity

@Database(
    entities = [
        TaskEntity::class,
        SubtaskEntity::class,
        ProjectEntity::class,
        SettingEntity::class,
        OutboxOp::class,
        SyncStateEntity::class,
        NoteEntity::class,
        NoteGroupEntity::class,
        FocusSessionEntity::class,
        DailyPlanEntity::class,
        RecurrenceRuleEntity::class,
    ],
    // v4 adds the recurrence_rules table (recurring-task creation + series management).
    // fallbackToDestructiveMigration() (DatabaseModule) wipes + full-resyncs an older install on first
    // open — acceptable per spec §3/§7.1 (a schema bump deliberately triggers a full resync; the only
    // local-only datum lost is note.pinnedAt, which is cosmetic).
    version = 4,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun subtaskDao(): SubtaskDao
    abstract fun projectDao(): ProjectDao
    abstract fun settingsDao(): SettingsDao
    abstract fun outboxDao(): OutboxDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun noteDao(): NoteDao
    abstract fun noteGroupDao(): NoteGroupDao
    abstract fun focusSessionDao(): FocusSessionDao
    abstract fun dailyPlanDao(): DailyPlanDao
    abstract fun recurrenceRuleDao(): RecurrenceRuleDao
}
