package net.qmindtech.tmap.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import net.qmindtech.tmap.data.local.AppDatabase
import net.qmindtech.tmap.data.local.EntityType
import net.qmindtech.tmap.data.local.OpType
import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.local.dao.SubtaskDao
import net.qmindtech.tmap.data.local.dao.TaskDao
import net.qmindtech.tmap.data.local.entities.TaskEntity
import net.qmindtech.tmap.data.remote.dto.CreateTaskRequest
import net.qmindtech.tmap.data.remote.dto.UpdateTaskRequest
import net.qmindtech.tmap.data.sync.Mappers.toCreateRequest
import net.qmindtech.tmap.data.sync.Mappers.toUpdateRequest
import net.qmindtech.tmap.data.sync.OutboxRepository
import net.qmindtech.tmap.data.sync.SyncScheduler
import net.qmindtech.tmap.notifications.ReminderScheduler
import net.qmindtech.tmap.util.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject

/**
 * Create/edit shapes consumed by the TaskEditor (P6) and TaskRepository.create/update.
 * Pinned verbatim from the spine CONTRACTS — do NOT alter field names/types/defaults.
 */
data class TaskDraft(
    val title: String,
    val notes: String? = null,
    val projectId: String? = null,
    val labels: List<String> = emptyList(),
    val status: TaskStatus = TaskStatus.Inbox,
    val plannedDate: LocalDate? = null,
    val scheduledStart: Instant? = null,
    val scheduledEnd: Instant? = null,
    val durationMinutes: Int? = null,
    val priority: Int? = null,
    val reminderMinutes: Int? = null,
    val dueDate: LocalDate? = null,
)

data class TaskEdit(
    val title: String? = null,
    val notes: String? = null,
    val projectId: String? = null,
    val labels: List<String>? = null,
    val status: TaskStatus? = null,
    val plannedDate: LocalDate? = null,
    val scheduledStart: Instant? = null,
    val scheduledEnd: Instant? = null,
    val durationMinutes: Int? = null,
    val priority: Int? = null,
    val reminderMinutes: Int? = null,
    val dueDate: LocalDate? = null,
    val actualTimeMinutes: Int? = null,
)

interface TaskRepository {
    fun observeAll(): Flow<List<TaskEntity>>
    fun observeToday(today: LocalDate): Flow<List<TaskEntity>>
    fun observeByStatus(s: TaskStatus): Flow<List<TaskEntity>>
    fun observe(id: String): Flow<TaskEntity?>
    suspend fun create(draft: TaskDraft): String
    suspend fun update(id: String, edit: TaskEdit)
    suspend fun markDone(id: String)
    suspend fun delete(id: String)
    suspend fun defer(id: String, toDate: LocalDate)
    suspend fun moveToDay(id: String, date: LocalDate)
    suspend fun reorder(orderedIds: List<String>)
    suspend fun addActualTime(id: String, minutes: Int)
}

/**
 * Write-through TaskRepository. Reads are Room Flows (source of truth for the UI). Each mutation
 * runs ONE Room transaction that upserts the entity table and appends the wire-shaped op to the
 * outbox, then arms/cancels the reminder and requests an expedited (debounced) sync. Creates use a
 * client UUID so the queued op is idempotent-by-id.
 */
class TaskRepositoryImpl constructor(
    private val taskDao: TaskDao,
    private val subtaskDao: SubtaskDao,
    private val outbox: OutboxRepository,
    private val db: AppDatabase,
    private val syncScheduler: SyncScheduler,
    private val clock: Clock,
    private val reminder: ReminderScheduler,
    /**
     * Called after each today-affecting mutation to refresh all Glance widgets optimistically.
     * Defaults to a no-op so unit tests can construct [TaskRepositoryImpl] without an Android
     * [android.content.Context] or Glance. The real Hilt binding (via [AppModule]) supplies a
     * lambda that calls [net.qmindtech.tmap.widget.WidgetUpdater.updateAll].
     */
    private val onWidgetRefresh: suspend () -> Unit = {},
) : TaskRepository {

    override fun observeAll(): Flow<List<TaskEntity>> = taskDao.observeAll()
    override fun observeToday(today: LocalDate): Flow<List<TaskEntity>> = taskDao.observeByPlannedDate(today)
    override fun observeByStatus(s: TaskStatus): Flow<List<TaskEntity>> = taskDao.observeByStatus(s)
    override fun observe(id: String): Flow<TaskEntity?> = taskDao.observeById(id)

    override suspend fun create(draft: TaskDraft): String {
        val now = clock.now()
        val id = UUID.randomUUID().toString()
        val entity = TaskEntity(
            id = id,
            title = draft.title,
            notes = draft.notes,
            projectId = draft.projectId,
            labels = draft.labels,
            source = "android",
            status = draft.status,
            plannedDate = draft.plannedDate,
            scheduledStart = draft.scheduledStart,
            scheduledEnd = draft.scheduledEnd,
            durationMinutes = draft.durationMinutes,
            actualTimeMinutes = 0,
            priority = draft.priority,
            reminderMinutes = draft.reminderMinutes,
            rank = null,
            dueDate = draft.dueDate,
            recurrenceRuleId = null,
            isRecurrenceTemplate = false,
            recurrenceDetached = false,
            recurrenceOriginalDate = null,
            completedAt = null,
            createdAt = now,
            updatedAt = now,
            changeSeq = 0L,
        )
        db.withTransaction {
            taskDao.upsertAll(listOf(entity))
            outbox.enqueue(
                EntityType.TASK, id, OpType.CREATE,
                entity.toCreateRequest(), CreateTaskRequest.serializer(),
            )
        }
        reminder.arm(entity)
        syncScheduler.requestExpeditedSync()
        onWidgetRefresh()
        return id
    }

    override suspend fun update(id: String, edit: TaskEdit) {
        val current = taskDao.getById(id) ?: return
        val updated = current.copy(
            title = edit.title ?: current.title,
            notes = if (edit.notes != null) edit.notes else current.notes,
            projectId = if (edit.projectId != null) edit.projectId else current.projectId,
            labels = edit.labels ?: current.labels,
            status = edit.status ?: current.status,
            plannedDate = if (edit.plannedDate != null) edit.plannedDate else current.plannedDate,
            scheduledStart = if (edit.scheduledStart != null) edit.scheduledStart else current.scheduledStart,
            scheduledEnd = if (edit.scheduledEnd != null) edit.scheduledEnd else current.scheduledEnd,
            durationMinutes = if (edit.durationMinutes != null) edit.durationMinutes else current.durationMinutes,
            priority = if (edit.priority != null) edit.priority else current.priority,
            reminderMinutes = if (edit.reminderMinutes != null) edit.reminderMinutes else current.reminderMinutes,
            dueDate = if (edit.dueDate != null) edit.dueDate else current.dueDate,
            actualTimeMinutes = edit.actualTimeMinutes ?: current.actualTimeMinutes,
            updatedAt = clock.now(),
        )
        db.withTransaction {
            taskDao.upsertAll(listOf(updated))
            outbox.enqueue(
                EntityType.TASK, id, OpType.UPDATE,
                updated.toUpdateRequest(), UpdateTaskRequest.serializer(),
            )
        }
        reminder.arm(updated)
        syncScheduler.requestExpeditedSync()
        onWidgetRefresh()
    }

    override suspend fun markDone(id: String) {
        val current = taskDao.getById(id) ?: return
        val now = clock.now()
        val done = current.copy(status = TaskStatus.Done, completedAt = now, updatedAt = now)
        db.withTransaction {
            taskDao.upsertAll(listOf(done))
            outbox.enqueue(
                EntityType.TASK, id, OpType.UPDATE,
                done.toUpdateRequest(), UpdateTaskRequest.serializer(),
            )
        }
        reminder.cancel(id)
        syncScheduler.requestExpeditedSync()
        onWidgetRefresh()
    }

    override suspend fun delete(id: String) {
        db.withTransaction {
            taskDao.deleteById(id)
            subtaskDao.deleteByTask(id)
            outbox.enqueueRaw(EntityType.TASK, id, OpType.DELETE, "{}")
        }
        reminder.cancel(id)
        syncScheduler.requestExpeditedSync()
        onWidgetRefresh()
    }

    override suspend fun defer(id: String, toDate: LocalDate) = moveToDay(id, toDate)

    override suspend fun moveToDay(id: String, date: LocalDate) {
        val current = taskDao.getById(id) ?: return
        val newStatus = when (current.status) {
            TaskStatus.Inbox, TaskStatus.Backlog -> TaskStatus.Planned
            else -> current.status
        }
        val updated = current.copy(
            plannedDate = date,
            scheduledStart = null,
            scheduledEnd = null,
            status = newStatus,
            updatedAt = clock.now(),
        )
        db.withTransaction {
            taskDao.upsertAll(listOf(updated))
            outbox.enqueue(
                EntityType.TASK, id, OpType.UPDATE,
                updated.toUpdateRequest(), UpdateTaskRequest.serializer(),
            )
        }
        reminder.arm(updated)
        syncScheduler.requestExpeditedSync()
    }

    override suspend fun reorder(orderedIds: List<String>) {
        if (orderedIds.isEmpty()) return
        val now = clock.now()
        val byId = orderedIds.mapNotNull { taskDao.getById(it) }.associateBy { it.id }
        val updates = orderedIds.mapIndexedNotNull { i, taskId ->
            byId[taskId]?.copy(rank = "%06d".format(i), updatedAt = now)
        }
        if (updates.isEmpty()) return
        db.withTransaction {
            taskDao.upsertAll(updates)
            updates.forEach { u ->
                outbox.enqueue(
                    EntityType.TASK, u.id, OpType.UPDATE,
                    u.toUpdateRequest(), UpdateTaskRequest.serializer(),
                )
            }
        }
        syncScheduler.requestExpeditedSync()
    }

    override suspend fun addActualTime(id: String, minutes: Int) {
        val current = taskDao.getById(id) ?: return
        val updated = current.copy(
            actualTimeMinutes = current.actualTimeMinutes + minutes,
            updatedAt = clock.now(),
        )
        db.withTransaction {
            taskDao.upsertAll(listOf(updated))
            outbox.enqueue(
                EntityType.TASK, id, OpType.UPDATE,
                updated.toUpdateRequest(), UpdateTaskRequest.serializer(),
            )
        }
        syncScheduler.requestExpeditedSync()
    }
}
