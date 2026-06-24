package net.qmindtech.tmap.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import net.qmindtech.tmap.data.local.entities.ProjectEntity

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY rank IS NULL, rank")
    fun observeAll(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun getById(id: String): ProjectEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<ProjectEntity>)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM projects")
    suspend fun clear()

    @Query(
        """
        SELECT projectId AS projectId,
               COUNT(*) AS total,
               SUM(CASE WHEN status = 'Done' THEN 1 ELSE 0 END) AS done
        FROM tasks
        WHERE projectId IS NOT NULL
          AND isRecurrenceTemplate = 0
          AND status != 'Archived'
        GROUP BY projectId
        """
    )
    fun observeProgress(): Flow<List<ProjectProgress>>
}
