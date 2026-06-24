package net.qmindtech.tmap.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import net.qmindtech.tmap.data.local.AppDatabase
import net.qmindtech.tmap.data.local.EntityType
import net.qmindtech.tmap.data.local.OpType
import net.qmindtech.tmap.data.local.dao.DailyPlanDao
import net.qmindtech.tmap.data.local.entities.DailyPlanEntity
import net.qmindtech.tmap.data.remote.dto.UpsertDailyPlanRequest
import net.qmindtech.tmap.data.sync.Mappers.toUpsertRequest
import net.qmindtech.tmap.data.sync.OutboxRepository
import net.qmindtech.tmap.data.sync.SyncScheduler
import net.qmindtech.tmap.util.Clock
import java.time.LocalDate
import javax.inject.Inject

interface DailyPlanRepository {
    fun observe(date: LocalDate): Flow<DailyPlanEntity?>
    fun observeRange(start: LocalDate, end: LocalDate): Flow<List<DailyPlanEntity>>
    suspend fun upsert(date: LocalDate, plannedTaskIds: List<String>, plannedMinutes: Int)
}

/**
 * Date-keyed DailyPlanRepository (spec §7.6). The natural key is the ISO date string; the only
 * outbox op is OpType.UPDATE with entityId = date.toString() (yyyy-MM-dd). PushRunner routes it
 * to PUT /daily-plans/{date} — no id remap/adopt needed (date is stable). Last-writer-wins: a
 * re-upsert replaces the local row and re-enqueues so the latest payload wins on push.
 *
 * committedAt is stamped locally (clock.now()) on the entity row only; it is NOT included in
 * UpsertDailyPlanRequest — the backend stamps committedAt server-side.
 */
class DailyPlanRepositoryImpl @Inject constructor(
    private val dailyPlanDao: DailyPlanDao,
    private val outbox: OutboxRepository,
    private val db: AppDatabase,
    private val syncScheduler: SyncScheduler,
    private val clock: Clock,
) : DailyPlanRepository {

    override fun observe(date: LocalDate): Flow<DailyPlanEntity?> = dailyPlanDao.observe(date)

    override fun observeRange(start: LocalDate, end: LocalDate): Flow<List<DailyPlanEntity>> =
        dailyPlanDao.observeRange(start, end)

    override suspend fun upsert(date: LocalDate, plannedTaskIds: List<String>, plannedMinutes: Int) {
        val entity = DailyPlanEntity(
            date = date,
            committedAt = clock.now(),
            plannedTaskIds = plannedTaskIds,
            plannedMinutes = plannedMinutes,
            changeSeq = 0L,
            deletedAt = null,
        )
        db.withTransaction {
            dailyPlanDao.upsertAll(listOf(entity))
            outbox.enqueue(
                EntityType.DAILY_PLAN,
                date.toString(),
                OpType.UPDATE,
                entity.toUpsertRequest(),
                UpsertDailyPlanRequest.serializer(),
            )
        }
        syncScheduler.requestExpeditedSync()
    }
}
