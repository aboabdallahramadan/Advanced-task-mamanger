package net.qmindtech.tmap.data.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import net.qmindtech.tmap.data.local.EntityType
import net.qmindtech.tmap.data.local.OpType
import net.qmindtech.tmap.data.local.dao.OutboxDao
import net.qmindtech.tmap.data.local.entities.OutboxOp
import net.qmindtech.tmap.util.Clock
import java.time.Instant
import javax.inject.Inject

/**
 * Thin durable-outbox wrapper over OutboxDao. enqueue() serializes the wire-shaped request body
 * to JSON; the payload is replayed verbatim by PushRunner. FIFO ordering is the dao's localSeq.
 */
class OutboxRepository @Inject constructor(
    private val dao: OutboxDao,
    private val json: Json,
    private val clock: Clock,
) {
    suspend fun <T> enqueue(
        entityType: EntityType,
        entityId: String,
        opType: OpType,
        payload: T,
        serializer: KSerializer<T>,
    ): Long = enqueueRaw(entityType, entityId, opType, json.encodeToString(serializer, payload))

    suspend fun enqueueRaw(
        entityType: EntityType,
        entityId: String,
        opType: OpType,
        payloadJson: String,
    ): Long = dao.enqueue(
        OutboxOp(
            entityType = entityType,
            entityId = entityId,
            opType = opType,
            payloadJson = payloadJson,
            createdAt = clock.now(),
        ),
    )

    suspend fun peek(): OutboxOp? = dao.peekNextUnparked()
    suspend fun countUnparked(): Int = dao.countUnparked()
    suspend fun delete(localSeq: Long) = dao.delete(localSeq)
    suspend fun bumpAttempts(localSeq: Long, parkedAt: Instant?) = dao.bumpAttempts(localSeq, parkedAt)
    suspend fun remapEntityId(old: String, new: String) = dao.remapEntityId(old, new)
    suspend fun clear() = dao.clear()
    fun observeUnparkedCount(): Flow<Int> = dao.observeUnparkedCount()
}
