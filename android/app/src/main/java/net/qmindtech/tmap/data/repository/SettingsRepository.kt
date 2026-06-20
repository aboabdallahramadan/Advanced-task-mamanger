package net.qmindtech.tmap.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import net.qmindtech.tmap.data.local.AppDatabase
import net.qmindtech.tmap.data.local.dao.SettingsDao
import net.qmindtech.tmap.data.local.entities.SettingEntity
import net.qmindtech.tmap.data.sync.SyncScheduler
import net.qmindtech.tmap.util.Clock

/** Reserved settings key under which the user's IANA timezone is persisted (spine §Deletes). */
const val TIME_ZONE_KEY = "__timeZoneId"

interface SettingsRepository {
    fun observe(): Flow<List<SettingEntity>>
    suspend fun save(settings: Map<String, String>, timeZoneId: String?)
}

/**
 * Write-through SettingsRepository. Settings are NOT replayed through the outbox (PushRunner errors
 * on EntityType.SETTINGS); they are pushed by the dedicated PUT /settings call the sync layer owns.
 * Here we apply the optimistic local write (changeSeq stays 0 until a pull rebases it) and nudge a
 * sync. The timezone, when provided, is stored under the reserved TIME_ZONE_KEY.
 */
class SettingsRepositoryImpl(
    private val settingsDao: SettingsDao,
    private val db: AppDatabase,
    private val syncScheduler: SyncScheduler,
    private val clock: Clock,
) : SettingsRepository {

    override fun observe(): Flow<List<SettingEntity>> = settingsDao.observeAll()

    override suspend fun save(settings: Map<String, String>, timeZoneId: String?) {
        val rows = buildList {
            settings.forEach { (k, v) -> add(SettingEntity(key = k, value = v, changeSeq = 0L)) }
            if (timeZoneId != null) add(SettingEntity(key = TIME_ZONE_KEY, value = timeZoneId, changeSeq = 0L))
        }
        db.withTransaction {
            settingsDao.upsertAll(rows)
        }
        syncScheduler.requestExpeditedSync()
    }
}
