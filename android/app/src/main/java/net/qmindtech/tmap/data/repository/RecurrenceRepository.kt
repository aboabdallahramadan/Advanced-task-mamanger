package net.qmindtech.tmap.data.repository

import androidx.room.withTransaction
import net.qmindtech.tmap.data.local.AppDatabase
import net.qmindtech.tmap.data.local.EntityType
import net.qmindtech.tmap.data.local.OpType
import net.qmindtech.tmap.data.local.dao.RecurrenceRuleDao
import net.qmindtech.tmap.data.local.dao.TaskDao
import net.qmindtech.tmap.data.local.entities.RecurrenceRuleEntity
import net.qmindtech.tmap.data.local.entities.TaskEntity
import net.qmindtech.tmap.data.recurrence.RecurrenceDraft
import net.qmindtech.tmap.data.recurrence.RecurrenceMapper
import net.qmindtech.tmap.data.remote.dto.CreateRecurringTaskRequest
import net.qmindtech.tmap.data.remote.dto.RecurrenceDeletePayload
import net.qmindtech.tmap.data.remote.dto.RecurringTaskInput
import net.qmindtech.tmap.data.remote.dto.UpdateRuleRequest
import net.qmindtech.tmap.data.sync.OutboxRepository
import net.qmindtech.tmap.data.sync.SyncScheduler
import net.qmindtech.tmap.util.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject

interface RecurrenceRepository {
    /** Create a recurring series (rule + hidden template). Returns the template task id. */
    suspend fun createRecurring(task: TaskDraft, rule: RecurrenceDraft): String
    suspend fun updateRule(ruleId: String, rule: RecurrenceDraft)
    suspend fun deleteAll(ruleId: String)
    suspend fun deleteFuture(ruleId: String, fromDate: LocalDate)
}

/**
 * Write-through RecurrenceRepository, mirroring [TaskRepositoryImpl]'s create-transaction idiom:
 * one Room transaction upserts the local rows (rule + hidden template task, or the delete/update
 * mutation) and appends the wire-shaped op to the outbox, then an expedited sync is nudged outside
 * the transaction. `createRecurring` mints both the rule id and the template task id client-side so
 * the queued CREATE op is idempotent-by-id, same as [TaskRepositoryImpl.create].
 */
class RecurrenceRepositoryImpl @Inject constructor(
    private val recurrenceRuleDao: RecurrenceRuleDao,
    private val taskDao: TaskDao,
    private val outbox: OutboxRepository,
    private val db: AppDatabase,
    private val syncScheduler: SyncScheduler,
    private val clock: Clock,
) : RecurrenceRepository {

    private val dateFmt = DateTimeFormatter.ISO_LOCAL_DATE

    override suspend fun createRecurring(task: TaskDraft, rule: RecurrenceDraft): String {
        val now = clock.now()
        val ruleId = UUID.randomUUID().toString()
        val templateId = UUID.randomUUID().toString()
        val start = task.plannedDate ?: clock.today()

        val ruleInput = RecurrenceMapper.ruleInput(rule, ruleId) // normalized (daily=>[], gated ends)

        val ruleEntity = RecurrenceRuleEntity(
            id = ruleId,
            frequency = ruleInput.frequency,
            interval = ruleInput.interval,
            daysOfWeek = ruleInput.daysOfWeek,
            endType = ruleInput.endType,
            endCount = ruleInput.endCount,
            endDate = ruleInput.endDate?.let { LocalDate.parse(it) },
            generatedUntil = null,
            createdAt = now,
            updatedAt = now,
            changeSeq = 0L,
            deletedAt = null,
        )
        val template = TaskEntity(
            id = templateId,
            title = task.title,
            notes = task.notes,
            projectId = task.projectId,
            labels = task.labels,
            source = "android",
            status = task.status,
            plannedDate = start,
            scheduledStart = task.scheduledStart,
            scheduledEnd = task.scheduledEnd,
            durationMinutes = task.durationMinutes,
            actualTimeMinutes = 0,
            priority = task.priority,
            reminderMinutes = task.reminderMinutes,
            rank = null,
            dueDate = task.dueDate,
            recurrenceRuleId = ruleId,
            isRecurrenceTemplate = true,
            recurrenceDetached = false,
            recurrenceOriginalDate = start,
            completedAt = null,
            createdAt = now,
            updatedAt = now,
            changeSeq = 0L,
        )
        val request = CreateRecurringTaskRequest(
            task = RecurringTaskInput(
                title = task.title,
                notes = task.notes ?: "",
                projectId = task.projectId,
                labels = task.labels,
                source = "android",
                plannedDate = start.format(dateFmt),
                durationMinutes = task.durationMinutes ?: 30,
                priority = task.priority,
                reminderMinutes = task.reminderMinutes ?: 0,
                id = templateId,
            ),
            rule = ruleInput,
        )
        db.withTransaction {
            recurrenceRuleDao.upsertAll(listOf(ruleEntity))
            taskDao.upsertAll(listOf(template))
            outbox.enqueue(
                EntityType.RECURRENCE, ruleId, OpType.CREATE,
                request, CreateRecurringTaskRequest.serializer(),
            )
        }
        syncScheduler.requestExpeditedSync()
        return templateId
    }

    override suspend fun updateRule(ruleId: String, rule: RecurrenceDraft) {
        val existing = recurrenceRuleDao.getById(ruleId) ?: return
        val req = RecurrenceMapper.updateRule(rule)
        val updated = existing.copy(
            frequency = req.frequency,
            interval = req.interval,
            daysOfWeek = req.daysOfWeek,
            endType = req.endType,
            endCount = req.endCount,
            endDate = req.endDate?.let { LocalDate.parse(it) },
            updatedAt = clock.now(),
        )
        db.withTransaction {
            recurrenceRuleDao.upsertAll(listOf(updated))
            outbox.enqueue(EntityType.RECURRENCE, ruleId, OpType.UPDATE, req, UpdateRuleRequest.serializer())
        }
        syncScheduler.requestExpeditedSync()
    }

    override suspend fun deleteAll(ruleId: String) {
        db.withTransaction {
            taskDao.deleteByRecurrenceRule(ruleId)
            recurrenceRuleDao.deleteById(ruleId)
            outbox.enqueue(
                EntityType.RECURRENCE, ruleId, OpType.DELETE,
                RecurrenceDeletePayload(scope = "all"), RecurrenceDeletePayload.serializer(),
            )
        }
        syncScheduler.requestExpeditedSync()
    }

    override suspend fun deleteFuture(ruleId: String, fromDate: LocalDate) {
        db.withTransaction {
            taskDao.deleteFutureInstances(ruleId, fromDate.format(dateFmt))
            outbox.enqueue(
                EntityType.RECURRENCE, ruleId, OpType.DELETE,
                RecurrenceDeletePayload(scope = "future", fromDate = fromDate.format(dateFmt)),
                RecurrenceDeletePayload.serializer(),
            )
        }
        syncScheduler.requestExpeditedSync()
    }
}
