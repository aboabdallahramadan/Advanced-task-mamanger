package net.qmindtech.tmap.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import net.qmindtech.tmap.data.local.entities.RecurrenceRuleEntity

@Dao
interface RecurrenceRuleDao {
    @Query("SELECT * FROM recurrence_rules WHERE deletedAt IS NULL")
    fun observeAll(): Flow<List<RecurrenceRuleEntity>>

    @Query("SELECT * FROM recurrence_rules WHERE id = :id")
    fun observeById(id: String): Flow<RecurrenceRuleEntity?>

    @Query("SELECT * FROM recurrence_rules WHERE id = :id")
    suspend fun getById(id: String): RecurrenceRuleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<RecurrenceRuleEntity>)

    @Query("DELETE FROM recurrence_rules WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM recurrence_rules")
    suspend fun clear()
}
