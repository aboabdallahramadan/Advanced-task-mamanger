package net.qmindtech.tmap.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import net.qmindtech.tmap.data.local.entities.NoteEntity
import java.time.Instant

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes ORDER BY rank IS NULL, rank")
    fun observeAll(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE groupId = :groupId ORDER BY rank IS NULL, rank")
    fun observeByGroup(groupId: String?): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE projectId = :projectId ORDER BY rank IS NULL, rank")
    fun observeByProject(projectId: String?): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE id = :id")
    fun observeById(id: String): Flow<NoteEntity?>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun getById(id: String): NoteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<NoteEntity>)

    /** Local-only pin toggle — does NOT bump changeSeq and is never enqueued to the outbox. */
    @Query("UPDATE notes SET pinnedAt = :pinnedAt WHERE id = :id")
    suspend fun setPinned(id: String, pinnedAt: Instant?)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM notes")
    suspend fun clear()
}
