package net.qmindtech.tmap.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.local.entities.TaskEntity
import java.time.LocalDate

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks WHERE isRecurrenceTemplate = 0 ORDER BY rank IS NULL, rank")
    fun observeAll(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE status = :status AND isRecurrenceTemplate = 0 ORDER BY rank IS NULL, rank")
    fun observeByStatus(status: TaskStatus): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE plannedDate = :date AND isRecurrenceTemplate = 0 ORDER BY rank IS NULL, rank")
    fun observeByPlannedDate(date: LocalDate): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE plannedDate = :date AND isRecurrenceTemplate = 0 ORDER BY rank IS NULL, rank")
    suspend fun getByPlannedDate(date: LocalDate): List<TaskEntity>

    @Query(
        "SELECT plannedDate AS date, " +
            "MAX(CASE WHEN status = 'Done' THEN 1 ELSE 0 END) AS anyDone " +
            "FROM tasks WHERE plannedDate IS NOT NULL AND isRecurrenceTemplate = 0 " +
            "GROUP BY plannedDate",
    )
    suspend fun completionByDate(): List<DateCompletion>

    @Query("SELECT * FROM tasks WHERE id = :id")
    fun observeById(id: String): Flow<TaskEntity?>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun getById(id: String): TaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<TaskEntity>)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM tasks")
    suspend fun clear()
}

/** Room projection for [TaskDao.completionByDate]. */
data class DateCompletion(val date: LocalDate, val anyDone: Int)
