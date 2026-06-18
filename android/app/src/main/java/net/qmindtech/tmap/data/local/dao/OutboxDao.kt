package net.qmindtech.tmap.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import net.qmindtech.tmap.data.local.entities.OutboxOp
import java.time.Instant

@Dao
interface OutboxDao {
    @Insert
    suspend fun enqueue(op: OutboxOp): Long

    @Query("SELECT * FROM outbox WHERE parkedAt IS NULL ORDER BY localSeq LIMIT 1")
    suspend fun peekNextUnparked(): OutboxOp?

    @Query("SELECT COUNT(*) FROM outbox WHERE parkedAt IS NULL")
    suspend fun countUnparked(): Int

    @Query("DELETE FROM outbox WHERE localSeq = :localSeq")
    suspend fun delete(localSeq: Long)

    @Query("UPDATE outbox SET attempts = attempts + 1, parkedAt = :parkedAt WHERE localSeq = :localSeq")
    suspend fun bumpAttempts(localSeq: Long, parkedAt: Instant?)

    @Query("SELECT COUNT(*) FROM outbox WHERE parkedAt IS NULL")
    fun observeUnparkedCount(): Flow<Int>

    @Query("UPDATE outbox SET entityId = :new WHERE entityId = :old")
    suspend fun remapEntityId(old: String, new: String)

    @Query("DELETE FROM outbox")
    suspend fun clear()

    @Query("SELECT DISTINCT entityId FROM outbox WHERE parkedAt IS NULL")
    suspend fun unparkedEntityIds(): List<String>

    /** Test-only: every op (parked + unparked) in FIFO order. P4 repository tests read this. */
    @Query("SELECT * FROM outbox ORDER BY localSeq ASC")
    suspend fun allForTest(): List<OutboxOp>
}
