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

    /** Total queued ops (parked + unparked). The full-resync/recovery gate must block on poison ops too. */
    @Query("SELECT COUNT(*) FROM outbox")
    suspend fun countAll(): Int

    /** Parked (poison) ops only — drives the sticky "N changes need attention" SyncStatus.Error. */
    @Query("SELECT COUNT(*) FROM outbox WHERE parkedAt IS NOT NULL")
    suspend fun countParked(): Int

    @Query("DELETE FROM outbox WHERE localSeq = :localSeq")
    suspend fun delete(localSeq: Long)

    /** Drop every queued op for an id — used when a rejected CREATE makes its later ops unreplayable. */
    @Query("DELETE FROM outbox WHERE entityId = :entityId")
    suspend fun deleteByEntityId(entityId: String)

    @Query("UPDATE outbox SET attempts = attempts + 1, parkedAt = :parkedAt WHERE localSeq = :localSeq")
    suspend fun bumpAttempts(localSeq: Long, parkedAt: Instant?)

    @Query("SELECT COUNT(*) FROM outbox WHERE parkedAt IS NULL")
    fun observeUnparkedCount(): Flow<Int>

    @Query("UPDATE outbox SET entityId = :newId WHERE entityId = :oldId")
    suspend fun remapEntityId(oldId: String, newId: String)

    @Query("DELETE FROM outbox")
    suspend fun clear()

    @Query("SELECT DISTINCT entityId FROM outbox WHERE parkedAt IS NULL")
    suspend fun unparkedEntityIds(): List<String>

    /** Test-only: every op (parked + unparked) in FIFO order. P4 repository tests read this. */
    @Query("SELECT * FROM outbox ORDER BY localSeq ASC")
    suspend fun allForTest(): List<OutboxOp>
}
