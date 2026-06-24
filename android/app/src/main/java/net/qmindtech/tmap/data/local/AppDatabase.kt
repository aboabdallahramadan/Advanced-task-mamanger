package net.qmindtech.tmap.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import net.qmindtech.tmap.data.local.dao.NoteDao
import net.qmindtech.tmap.data.local.dao.OutboxDao
import net.qmindtech.tmap.data.local.dao.ProjectDao
import net.qmindtech.tmap.data.local.dao.SettingsDao
import net.qmindtech.tmap.data.local.dao.SubtaskDao
import net.qmindtech.tmap.data.local.dao.SyncStateDao
import net.qmindtech.tmap.data.local.dao.TaskDao
import net.qmindtech.tmap.data.local.entities.NoteEntity
import net.qmindtech.tmap.data.local.entities.OutboxOp
import net.qmindtech.tmap.data.local.entities.ProjectEntity
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
    ],
    // v3 adds the four SP4 new-domain tables (notes, note_groups, focus_sessions, daily_plans).
    // fallbackToDestructiveMigration() (DatabaseModule) wipes + full-resyncs an older install on first
    // open — acceptable per spec §3/§7.1 (a schema bump deliberately triggers a full resync; the only
    // local-only datum lost is note.pinnedAt, which is cosmetic).
    version = 3,
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
}
