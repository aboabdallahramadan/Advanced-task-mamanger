package net.qmindtech.tmap.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import net.qmindtech.tmap.data.local.AppDatabase
import net.qmindtech.tmap.data.local.EntityType
import net.qmindtech.tmap.data.local.OpType
import net.qmindtech.tmap.data.local.dao.FocusSessionDao
import net.qmindtech.tmap.data.local.entities.FocusSessionEntity
import net.qmindtech.tmap.data.remote.dto.CreateFocusSessionRequest
import net.qmindtech.tmap.data.sync.Mappers.toCreateRequest
import net.qmindtech.tmap.data.sync.OutboxRepository
import net.qmindtech.tmap.data.sync.SyncScheduler
import net.qmindtech.tmap.util.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject

interface FocusSessionRepository {
    suspend fun create(
        taskId: String?,
        project: String,
        startedAt: Instant,
        endedAt: Instant,
        minutes: Int,
        date: LocalDate,
    ): String
    fun observeForTask(taskId: String): Flow<List<FocusSessionEntity>>
    fun observeForDateRange(start: LocalDate, end: LocalDate): Flow<List<FocusSessionEntity>>
}

/**
 * Append-only FocusSessionRepository (spec §7.4): [create] is the ONLY mutation and enqueues exactly
 * one OpType.CREATE. There is no update/delete; PushRunner errors on any non-CREATE FOCUS_SESSION op.
 */
class FocusSessionRepositoryImpl @Inject constructor(
    private val focusSessionDao: FocusSessionDao,
    private val outbox: OutboxRepository,
    private val db: AppDatabase,
    private val syncScheduler: SyncScheduler,
    private val clock: Clock,
) : FocusSessionRepository {

    override suspend fun create(
        taskId: String?,
        project: String,
        startedAt: Instant,
        endedAt: Instant,
        minutes: Int,
        date: LocalDate,
    ): String {
        val now = clock.now()
        val id = UUID.randomUUID().toString()
        val entity = FocusSessionEntity(
            id = id, taskId = taskId, project = project, startedAt = startedAt, endedAt = endedAt,
            minutes = minutes, date = date, createdAt = now, updatedAt = now, changeSeq = 0L, deletedAt = null,
        )
        db.withTransaction {
            focusSessionDao.upsertAll(listOf(entity))
            outbox.enqueue(
                EntityType.FOCUS_SESSION, id, OpType.CREATE,
                entity.toCreateRequest(), CreateFocusSessionRequest.serializer(),
            )
        }
        syncScheduler.requestExpeditedSync()
        return id
    }

    override fun observeForTask(taskId: String): Flow<List<FocusSessionEntity>> =
        focusSessionDao.observeForTask(taskId)

    override fun observeForDateRange(start: LocalDate, end: LocalDate): Flow<List<FocusSessionEntity>> =
        focusSessionDao.observeForDateRange(start, end)
}
