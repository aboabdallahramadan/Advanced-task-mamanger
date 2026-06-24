package net.qmindtech.tmap.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import net.qmindtech.tmap.data.local.entities.DailyPlanEntity
import java.time.LocalDate

@Dao
interface DailyPlanDao {
    @Query("SELECT * FROM daily_plans WHERE date = :date")
    fun observe(date: LocalDate): Flow<DailyPlanEntity?>

    @Query("SELECT * FROM daily_plans WHERE date = :date")
    suspend fun getByDate(date: LocalDate): DailyPlanEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<DailyPlanEntity>)

    @Query("SELECT * FROM daily_plans WHERE date BETWEEN :start AND :end ORDER BY date")
    fun observeRange(start: LocalDate, end: LocalDate): Flow<List<DailyPlanEntity>>

    @Query("DELETE FROM daily_plans WHERE date = :date")
    suspend fun deleteByDate(date: LocalDate)

    @Query("DELETE FROM daily_plans")
    suspend fun clear()
}
