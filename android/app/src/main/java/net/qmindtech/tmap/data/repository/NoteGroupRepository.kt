package net.qmindtech.tmap.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.builtins.ListSerializer
import net.qmindtech.tmap.data.local.AppDatabase
import net.qmindtech.tmap.data.local.EntityType
import net.qmindtech.tmap.data.local.OpType
import net.qmindtech.tmap.data.local.dao.NoteGroupDao
import net.qmindtech.tmap.data.local.entities.NoteGroupEntity
import net.qmindtech.tmap.data.remote.dto.CreateNoteGroupRequest
import net.qmindtech.tmap.data.remote.dto.ReorderItem
import net.qmindtech.tmap.data.remote.dto.UpdateNoteGroupRequest
import net.qmindtech.tmap.data.sync.Mappers.toCreateRequest
import net.qmindtech.tmap.data.sync.Mappers.toUpdateRequest
import net.qmindtech.tmap.data.sync.OutboxRepository
import net.qmindtech.tmap.data.sync.SyncScheduler
import net.qmindtech.tmap.util.Clock
import java.util.UUID
import javax.inject.Inject

interface NoteGroupRepository {
    fun observeAll(): Flow<List<NoteGroupEntity>>
    suspend fun create(name: String, emoji: String, projectId: String? = null): String
    suspend fun update(id: String, name: String? = null, emoji: String? = null, projectId: String? = null)
    suspend fun delete(id: String)
    suspend fun reorder(ids: List<String>)
}

/** Write-through NoteGroupRepository, mirroring NoteRepositoryImpl. No local-only state (no pin). */
class NoteGroupRepositoryImpl @Inject constructor(
    private val noteGroupDao: NoteGroupDao,
    private val outbox: OutboxRepository,
    private val db: AppDatabase,
    private val syncScheduler: SyncScheduler,
    private val clock: Clock,
) : NoteGroupRepository {

    override fun observeAll(): Flow<List<NoteGroupEntity>> = noteGroupDao.observeAll()

    override suspend fun create(name: String, emoji: String, projectId: String?): String {
        val now = clock.now()
        val id = UUID.randomUUID().toString()
        val entity = NoteGroupEntity(
            id = id, name = name, emoji = emoji, projectId = projectId, rank = null,
            createdAt = now, updatedAt = now, changeSeq = 0L, deletedAt = null,
        )
        db.withTransaction {
            noteGroupDao.upsertAll(listOf(entity))
            outbox.enqueue(
                EntityType.NOTE_GROUP, id, OpType.CREATE,
                entity.toCreateRequest(), CreateNoteGroupRequest.serializer(),
            )
        }
        syncScheduler.requestExpeditedSync()
        return id
    }

    override suspend fun update(id: String, name: String?, emoji: String?, projectId: String?) {
        val current = noteGroupDao.getById(id) ?: return
        val updated = current.copy(
            name = name ?: current.name,
            emoji = emoji ?: current.emoji,
            projectId = if (projectId != null) projectId else current.projectId,
            updatedAt = clock.now(),
        )
        db.withTransaction {
            noteGroupDao.upsertAll(listOf(updated))
            outbox.enqueue(
                EntityType.NOTE_GROUP, id, OpType.UPDATE,
                updated.toUpdateRequest(), UpdateNoteGroupRequest.serializer(),
            )
        }
        syncScheduler.requestExpeditedSync()
    }

    override suspend fun delete(id: String) {
        db.withTransaction {
            noteGroupDao.deleteById(id)
            outbox.enqueueRaw(EntityType.NOTE_GROUP, id, OpType.DELETE, "{}")
        }
        syncScheduler.requestExpeditedSync()
    }

    override suspend fun reorder(ids: List<String>) {
        if (ids.isEmpty()) return
        val now = clock.now()
        val items = ids.mapIndexed { index, id -> ReorderItem(id = id, rank = index.toString().padStart(4, '0')) }
        db.withTransaction {
            items.forEach { item ->
                noteGroupDao.getById(item.id)?.let { row ->
                    noteGroupDao.upsertAll(listOf(row.copy(rank = item.rank, updatedAt = now)))
                }
            }
            outbox.enqueue(
                EntityType.NOTE_GROUP, "reorder", OpType.REORDER,
                items, ListSerializer(ReorderItem.serializer()),
            )
        }
        syncScheduler.requestExpeditedSync()
    }
}
