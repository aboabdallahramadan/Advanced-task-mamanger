package net.qmindtech.tmap.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import net.qmindtech.tmap.data.local.entities.NoteGroupEntity

@Dao
interface NoteGroupDao {
    @Query("SELECT * FROM note_groups ORDER BY rank IS NULL, rank")
    fun observeAll(): Flow<List<NoteGroupEntity>>

    @Query("SELECT * FROM note_groups WHERE id = :id")
    suspend fun getById(id: String): NoteGroupEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<NoteGroupEntity>)

    @Query("DELETE FROM note_groups WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM note_groups")
    suspend fun clear()
}
