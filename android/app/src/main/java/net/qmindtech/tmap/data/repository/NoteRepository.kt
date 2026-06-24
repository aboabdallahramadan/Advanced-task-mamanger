package net.qmindtech.tmap.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.builtins.ListSerializer
import net.qmindtech.tmap.data.local.AppDatabase
import net.qmindtech.tmap.data.local.EntityType
import net.qmindtech.tmap.data.local.OpType
import net.qmindtech.tmap.data.local.dao.NoteDao
import net.qmindtech.tmap.data.local.entities.NoteEntity
import net.qmindtech.tmap.data.remote.dto.CreateNoteRequest
import net.qmindtech.tmap.data.remote.dto.ReorderItem
import net.qmindtech.tmap.data.remote.dto.UpdateNoteRequest
import net.qmindtech.tmap.data.sync.Mappers.toCreateRequest
import net.qmindtech.tmap.data.sync.Mappers.toUpdateRequest
import net.qmindtech.tmap.data.sync.OutboxRepository
import net.qmindtech.tmap.data.sync.SyncScheduler
import net.qmindtech.tmap.util.Clock
import java.util.UUID
import javax.inject.Inject

interface NoteRepository {
    fun observeAll(groupId: String?, projectId: String?): Flow<List<NoteEntity>>
    fun observe(id: String): Flow<NoteEntity?>
    suspend fun create(title: String, content: String, groupId: String? = null, projectId: String? = null): String
    suspend fun update(id: String, title: String? = null, content: String? = null, groupId: String? = null, projectId: String? = null)
    suspend fun delete(id: String)
    suspend fun setPinned(id: String, pinned: Boolean)
    suspend fun reorder(ids: List<String>)
}

/**
 * Write-through NoteRepository, mirroring ProjectRepositoryImpl. [setPinned] is the ONE exception
 * to write-through: pin is a LOCAL-ONLY column (spec §7.7) — it stamps pinnedAt directly, enqueues
 * no outbox op, and does not nudge sync.
 *
 * deletedAt model: hard-delete on tombstone (P3.19 will DELETE the row, not soft-keep it). Observe
 * queries therefore need no `WHERE deletedAt IS NULL` guard — deleted rows are simply absent.
 *
 * observeAll dispatch: groupId wins → observeByGroup; then projectId → observeByProject; else all.
 * Callers passing (null, null) get every note. Neither branch passes null to a parameterized
 * WHERE-equality query, so there is no SQLite NULL≠NULL hazard.
 */
class NoteRepositoryImpl @Inject constructor(
    private val noteDao: NoteDao,
    private val outbox: OutboxRepository,
    private val db: AppDatabase,
    private val syncScheduler: SyncScheduler,
    private val clock: Clock,
) : NoteRepository {

    override fun observeAll(groupId: String?, projectId: String?): Flow<List<NoteEntity>> = when {
        groupId != null -> noteDao.observeByGroup(groupId)
        projectId != null -> noteDao.observeByProject(projectId)
        else -> noteDao.observeAll()
    }

    override fun observe(id: String): Flow<NoteEntity?> = noteDao.observeById(id)

    override suspend fun create(
        title: String,
        content: String,
        groupId: String?,
        projectId: String?,
    ): String {
        val now = clock.now()
        val id = UUID.randomUUID().toString()
        val entity = NoteEntity(
            id = id,
            groupId = groupId,
            projectId = projectId,
            title = title,
            content = content,
            rank = null,
            createdAt = now,
            updatedAt = now,
            changeSeq = 0L,
            deletedAt = null,
            pinnedAt = null,
        )
        db.withTransaction {
            noteDao.upsertAll(listOf(entity))
            outbox.enqueue(
                EntityType.NOTE, id, OpType.CREATE,
                entity.toCreateRequest(), CreateNoteRequest.serializer(),
            )
        }
        syncScheduler.requestExpeditedSync()
        return id
    }

    override suspend fun update(
        id: String,
        title: String?,
        content: String?,
        groupId: String?,
        projectId: String?,
    ) {
        val current = noteDao.getById(id) ?: return
        val updated = current.copy(
            title = title ?: current.title,
            content = content ?: current.content,
            groupId = if (groupId != null) groupId else current.groupId,
            projectId = if (projectId != null) projectId else current.projectId,
            updatedAt = clock.now(),
        )
        db.withTransaction {
            noteDao.upsertAll(listOf(updated))
            outbox.enqueue(
                EntityType.NOTE, id, OpType.UPDATE,
                updated.toUpdateRequest(), UpdateNoteRequest.serializer(),
            )
        }
        syncScheduler.requestExpeditedSync()
    }

    override suspend fun delete(id: String) {
        db.withTransaction {
            noteDao.deleteById(id)
            outbox.enqueueRaw(EntityType.NOTE, id, OpType.DELETE, "{}")
        }
        syncScheduler.requestExpeditedSync()
    }

    /** LOCAL-ONLY pin: no outbox op, no sync nudge (spec §7.7). */
    override suspend fun setPinned(id: String, pinned: Boolean) {
        noteDao.setPinned(id, if (pinned) clock.now() else null)
    }

    override suspend fun reorder(ids: List<String>) {
        if (ids.isEmpty()) return
        val now = clock.now()
        val items = ids.mapIndexed { index, id -> ReorderItem(id = id, rank = rankFor(index)) }
        db.withTransaction {
            items.forEach { item ->
                noteDao.getById(item.id)?.let { row ->
                    noteDao.upsertAll(listOf(row.copy(rank = item.rank, updatedAt = now)))
                }
            }
            outbox.enqueue(
                EntityType.NOTE, "reorder", OpType.REORDER,
                items, ListSerializer(ReorderItem.serializer()),
            )
        }
        syncScheduler.requestExpeditedSync()
    }

    /** Zero-padded lexicographic rank so string ordering matches list order ("0000" < "0001" …). */
    private fun rankFor(index: Int): String = index.toString().padStart(4, '0')
}
