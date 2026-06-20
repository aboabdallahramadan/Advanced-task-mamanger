package net.qmindtech.tmap.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import net.qmindtech.tmap.data.local.AppDatabase
import net.qmindtech.tmap.data.local.EntityType
import net.qmindtech.tmap.data.local.OpType
import net.qmindtech.tmap.data.local.dao.SubtaskDao
import net.qmindtech.tmap.data.local.entities.SubtaskEntity
import net.qmindtech.tmap.data.remote.dto.UpdateSubtaskRequest
import net.qmindtech.tmap.data.sync.Mappers.toUpdateRequest
import net.qmindtech.tmap.data.sync.OutboxRepository
import net.qmindtech.tmap.data.sync.SyncScheduler
import net.qmindtech.tmap.util.Clock
import java.util.UUID
import javax.inject.Inject

interface SubtaskRepository {
    fun observeByTask(taskId: String): Flow<List<SubtaskEntity>>
    suspend fun create(taskId: String, title: String): String
    suspend fun update(id: String, title: String? = null, completed: Boolean? = null, sortOrder: Int? = null)
    suspend fun delete(id: String)
}

/**
 * Write-through SubtaskRepository. The CREATE payload is hand-built so it carries `taskId` ALONGSIDE
 * the CreateSubtaskRequest fields ({id,title}); PushRunner reads payloadJson["taskId"] to route the
 * call to POST /tasks/{taskId}/subtasks, and the extra key is ignored on deserialization to
 * CreateSubtaskRequest (ignoreUnknownKeys).
 */
class SubtaskRepositoryImpl @Inject constructor(
    private val subtaskDao: SubtaskDao,
    private val outbox: OutboxRepository,
    private val db: AppDatabase,
    private val syncScheduler: SyncScheduler,
    private val clock: Clock,
    private val json: Json,
) : SubtaskRepository {

    override fun observeByTask(taskId: String): Flow<List<SubtaskEntity>> = subtaskDao.observeByTask(taskId)

    override suspend fun create(taskId: String, title: String): String {
        val now = clock.now()
        val id = UUID.randomUUID().toString()
        val entity = SubtaskEntity(
            id = id, taskId = taskId, title = title, completed = false, sortOrder = 0,
            createdAt = now, updatedAt = now, changeSeq = 0L,
        )
        val payload: JsonObject = buildJsonObject {
            put("id", JsonPrimitive(id))
            put("title", JsonPrimitive(title))
            put("taskId", JsonPrimitive(taskId))
        }
        db.withTransaction {
            subtaskDao.upsertAll(listOf(entity))
            outbox.enqueueRaw(EntityType.SUBTASK, id, OpType.CREATE, json.encodeToString(JsonObject.serializer(), payload))
        }
        syncScheduler.requestExpeditedSync()
        return id
    }

    override suspend fun update(id: String, title: String?, completed: Boolean?, sortOrder: Int?) {
        val current = subtaskDao.getById(id) ?: return
        val updated = current.copy(
            title = title ?: current.title,
            completed = completed ?: current.completed,
            sortOrder = sortOrder ?: current.sortOrder,
            updatedAt = clock.now(),
        )
        db.withTransaction {
            subtaskDao.upsertAll(listOf(updated))
            outbox.enqueue(
                EntityType.SUBTASK, id, OpType.UPDATE,
                updated.toUpdateRequest(), UpdateSubtaskRequest.serializer(),
            )
        }
        syncScheduler.requestExpeditedSync()
    }

    override suspend fun delete(id: String) {
        db.withTransaction {
            subtaskDao.deleteById(id)
            outbox.enqueueRaw(EntityType.SUBTASK, id, OpType.DELETE, "{}")
        }
        syncScheduler.requestExpeditedSync()
    }
}
