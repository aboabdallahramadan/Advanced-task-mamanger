package net.qmindtech.tmap.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import net.qmindtech.tmap.data.local.entities.SyncStateEntity

@Dao
interface SyncStateDao {
    @Query("SELECT * FROM sync_state WHERE id = 1")
    suspend fun getOrNull(): SyncStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(s: SyncStateEntity)

    @Query("SELECT * FROM sync_state WHERE id = 1")
    fun observe(): Flow<SyncStateEntity?>

    /**
     * Targeted pendingRecovery setter (BUG 0). UPDATE-only so it never resurrects a deleted row and
     * never races a concurrent cursor write into a stale snapshot. Callers ensure the (1) row exists
     * (get() runs first in both push and pull paths).
     */
    @Query("UPDATE sync_state SET pendingRecovery = :flag WHERE id = 1")
    suspend fun setPendingRecovery(flag: Boolean)

    /** Returns the (1) row, inserting the default if absent. */
    @Transaction
    suspend fun get(): SyncStateEntity {
        val existing = getOrNull()
        if (existing != null) return existing
        val default = SyncStateEntity()
        upsert(default)
        return default
    }
}
