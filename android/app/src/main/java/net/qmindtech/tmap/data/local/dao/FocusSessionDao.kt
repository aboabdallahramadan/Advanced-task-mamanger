package net.qmindtech.tmap.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import net.qmindtech.tmap.data.local.entities.FocusSessionEntity
import java.time.LocalDate

@Dao
interface FocusSessionDao {
    @Query("SELECT * FROM focus_sessions WHERE taskId = :taskId ORDER BY startedAt")
    fun observeForTask(taskId: String): Flow<List<FocusSessionEntity>>

    @Query("SELECT * FROM focus_sessions WHERE date BETWEEN :start AND :end ORDER BY startedAt")
    fun observeForDateRange(start: LocalDate, end: LocalDate): Flow<List<FocusSessionEntity>>

    @Query("SELECT * FROM focus_sessions WHERE id = :id")
    suspend fun getById(id: String): FocusSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<FocusSessionEntity>)

    @Query("DELETE FROM focus_sessions WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM focus_sessions")
    suspend fun clear()
}
