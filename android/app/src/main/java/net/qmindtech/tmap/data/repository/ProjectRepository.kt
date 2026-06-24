package net.qmindtech.tmap.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.builtins.ListSerializer
import net.qmindtech.tmap.data.local.AppDatabase
import net.qmindtech.tmap.data.local.EntityType
import net.qmindtech.tmap.data.local.OpType
import net.qmindtech.tmap.data.local.dao.ProjectDao
import net.qmindtech.tmap.data.local.dao.ProjectProgress
import net.qmindtech.tmap.data.local.entities.ProjectEntity
import net.qmindtech.tmap.data.remote.dto.CreateProjectRequest
import net.qmindtech.tmap.data.remote.dto.ReorderItem
import net.qmindtech.tmap.data.remote.dto.UpdateProjectRequest
import net.qmindtech.tmap.data.sync.Mappers.toCreateRequest
import net.qmindtech.tmap.data.sync.Mappers.toUpdateRequest
import net.qmindtech.tmap.data.sync.OutboxRepository
import net.qmindtech.tmap.data.sync.SyncScheduler
import net.qmindtech.tmap.util.Clock
import java.util.UUID
import javax.inject.Inject

interface ProjectRepository {
    fun observeAll(): Flow<List<ProjectEntity>>
    fun observeProgress(): Flow<List<ProjectProgress>>
    suspend fun create(name: String, color: String, emoji: String): String
    suspend fun update(id: String, name: String? = null, color: String? = null, emoji: String? = null)
    suspend fun delete(id: String)
    suspend fun reorder(orderedIds: List<String>)
}

/**
 * Write-through ProjectRepository. Reorder assigns evenly-spaced lexicographic ranks ("0001",
 * "0002", …) locally and enqueues ONE REORDER op whose payload is the List<ReorderItem> the
 * PATCH /projects/reorder endpoint accepts.
 */
class ProjectRepositoryImpl @Inject constructor(
    private val projectDao: ProjectDao,
    private val outbox: OutboxRepository,
    private val db: AppDatabase,
    private val syncScheduler: SyncScheduler,
    private val clock: Clock,
) : ProjectRepository {

    override fun observeAll(): Flow<List<ProjectEntity>> = projectDao.observeAll()

    override fun observeProgress(): Flow<List<ProjectProgress>> = projectDao.observeProgress()

    override suspend fun create(name: String, color: String, emoji: String): String {
        val now = clock.now()
        val id = UUID.randomUUID().toString()
        val entity = ProjectEntity(
            id = id, name = name, color = color, emoji = emoji, rank = null,
            actualTimeMinutes = 0, createdAt = now, updatedAt = now, changeSeq = 0L,
        )
        db.withTransaction {
            projectDao.upsertAll(listOf(entity))
            outbox.enqueue(
                EntityType.PROJECT, id, OpType.CREATE,
                entity.toCreateRequest(), CreateProjectRequest.serializer(),
            )
        }
        syncScheduler.requestExpeditedSync()
        return id
    }

    override suspend fun update(id: String, name: String?, color: String?, emoji: String?) {
        val current = projectDao.getById(id) ?: return
        val updated = current.copy(
            name = name ?: current.name,
            color = color ?: current.color,
            emoji = emoji ?: current.emoji,
            updatedAt = clock.now(),
        )
        db.withTransaction {
            projectDao.upsertAll(listOf(updated))
            outbox.enqueue(
                EntityType.PROJECT, id, OpType.UPDATE,
                updated.toUpdateRequest(), UpdateProjectRequest.serializer(),
            )
        }
        syncScheduler.requestExpeditedSync()
    }

    override suspend fun delete(id: String) {
        db.withTransaction {
            projectDao.deleteById(id)
            outbox.enqueueRaw(EntityType.PROJECT, id, OpType.DELETE, "{}")
        }
        syncScheduler.requestExpeditedSync()
    }

    override suspend fun reorder(orderedIds: List<String>) {
        val now = clock.now()
        val items = orderedIds.mapIndexed { index, id -> ReorderItem(id = id, rank = rankFor(index)) }
        db.withTransaction {
            items.forEach { item ->
                projectDao.getById(item.id)?.let { row ->
                    projectDao.upsertAll(listOf(row.copy(rank = item.rank, updatedAt = now)))
                }
            }
            outbox.enqueue(
                EntityType.PROJECT, "reorder", OpType.REORDER,
                items, ListSerializer(ReorderItem.serializer()),
            )
        }
        syncScheduler.requestExpeditedSync()
    }

    /** Zero-padded lexicographic rank so string ordering matches list order ("0000" < "0001" …). */
    private fun rankFor(index: Int): String = index.toString().padStart(4, '0')
}
