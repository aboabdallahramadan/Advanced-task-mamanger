package net.qmindtech.tmap.testutil

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.local.entities.ProjectEntity
import net.qmindtech.tmap.data.local.entities.SubtaskEntity
import net.qmindtech.tmap.data.local.entities.TaskEntity
import net.qmindtech.tmap.data.repository.ProjectRepository
import net.qmindtech.tmap.data.repository.SubtaskRepository
import net.qmindtech.tmap.data.repository.TaskDraft
import net.qmindtech.tmap.data.repository.TaskEdit
import net.qmindtech.tmap.data.repository.TaskRepository
import net.qmindtech.tmap.util.Clock
import java.time.Instant
import java.time.LocalDate

private val EPOCH = Instant.parse("2026-01-01T00:00:00Z")

fun fakeTask(
  id: String,
  title: String = "Task $id",
  notes: String? = null,
  projectId: String? = null,
  labels: List<String> = emptyList(),
  status: TaskStatus = TaskStatus.Inbox,
  plannedDate: LocalDate? = null,
  scheduledStart: Instant? = null,
  scheduledEnd: Instant? = null,
  durationMinutes: Int? = null,
  actualTimeMinutes: Int = 0,
  priority: Int? = null,
  reminderMinutes: Int? = null,
  rank: String? = null,
  dueDate: LocalDate? = null,
  recurrenceRuleId: String? = null,
  completedAt: Instant? = null,
  createdAt: Instant = EPOCH,
  updatedAt: Instant = EPOCH,
  changeSeq: Long = 0,
): TaskEntity = TaskEntity(
  id = id, title = title, notes = notes, projectId = projectId, labels = labels,
  source = "android", status = status, plannedDate = plannedDate, scheduledStart = scheduledStart,
  scheduledEnd = scheduledEnd, durationMinutes = durationMinutes, actualTimeMinutes = actualTimeMinutes,
  priority = priority, reminderMinutes = reminderMinutes, rank = rank, dueDate = dueDate,
  recurrenceRuleId = recurrenceRuleId, isRecurrenceTemplate = false, recurrenceDetached = false,
  recurrenceOriginalDate = null, completedAt = completedAt, createdAt = createdAt,
  updatedAt = updatedAt, changeSeq = changeSeq,
)

fun fakeProject(
  id: String,
  name: String = "Project $id",
  color: String = "#6366f1",
  emoji: String = "📁",
  rank: String? = null,
  createdAt: Instant = EPOCH,
): ProjectEntity = ProjectEntity(
  id = id, name = name, color = color, emoji = emoji, rank = rank,
  actualTimeMinutes = 0, createdAt = createdAt, updatedAt = createdAt, changeSeq = 0,
)

fun fakeSubtask(
  id: String,
  taskId: String,
  title: String = "Sub $id",
  completed: Boolean = false,
  sortOrder: Int = 0,
  createdAt: Instant = EPOCH,
): SubtaskEntity = SubtaskEntity(
  id = id, taskId = taskId, title = title, completed = completed, sortOrder = sortOrder,
  createdAt = createdAt, updatedAt = createdAt, changeSeq = 0,
)

class FixedClock(private val now: Instant) : Clock {
  override fun now(): Instant = now
  override fun today(): LocalDate = now.atZone(java.time.ZoneOffset.UTC).toLocalDate()
}

class FakeTaskRepo(
  private val all: MutableStateFlow<List<TaskEntity>> = MutableStateFlow(emptyList()),
  private val today: MutableStateFlow<List<TaskEntity>> = MutableStateFlow(emptyList()),
  private val byStatus: MutableStateFlow<List<TaskEntity>> = MutableStateFlow(emptyList()),
  private val single: MutableStateFlow<TaskEntity?> = MutableStateFlow(null),
) : TaskRepository {
  val created = mutableListOf<TaskDraft>()
  val updated = mutableListOf<Pair<String, TaskEdit>>()
  val markedDone = mutableListOf<String>()
  val deleted = mutableListOf<String>()
  val deferred = mutableListOf<Pair<String, LocalDate>>()
  val movedToDay = mutableListOf<Pair<String, LocalDate>>()
  val reordered = mutableListOf<List<String>>()
  val actualTimeAdded = mutableListOf<Pair<String, Int>>()
  var nextId = "new-id"

  override fun observeAll(): Flow<List<TaskEntity>> = all
  override fun observeToday(today: LocalDate): Flow<List<TaskEntity>> = this.today
  override fun observeByStatus(s: TaskStatus): Flow<List<TaskEntity>> = byStatus
  override fun observe(id: String): Flow<TaskEntity?> = single
  override suspend fun create(draft: TaskDraft): String { created += draft; return nextId }
  override suspend fun update(id: String, edit: TaskEdit) { updated += id to edit }
  override suspend fun markDone(id: String) { markedDone += id }
  override suspend fun delete(id: String) { deleted += id }
  override suspend fun defer(id: String, toDate: LocalDate) { deferred += id to toDate }
  override suspend fun moveToDay(id: String, date: LocalDate) { movedToDay += id to date }
  override suspend fun reorder(orderedIds: List<String>) { reordered += orderedIds }
  override suspend fun addActualTime(id: String, minutes: Int) { actualTimeAdded += id to minutes }

  fun setAll(v: List<TaskEntity>) = all.let { it.value = v }
  fun setByStatus(v: List<TaskEntity>) = byStatus.let { it.value = v }
  fun setSingle(v: TaskEntity?) = single.let { it.value = v }
}

class FakeProjectRepo(
  private val all: MutableStateFlow<List<ProjectEntity>> = MutableStateFlow(emptyList()),
) : ProjectRepository {
  val created = mutableListOf<Triple<String, String, String>>() // name,color,emoji
  val updated = mutableListOf<String>()
  val deleted = mutableListOf<String>()
  val reordered = mutableListOf<List<String>>()

  override fun observeAll(): Flow<List<ProjectEntity>> = all
  override suspend fun create(name: String, color: String, emoji: String): String {
    created += Triple(name, color, emoji); return "proj-${created.size}"
  }
  override suspend fun update(id: String, name: String?, color: String?, emoji: String?) { updated += id }
  override suspend fun delete(id: String) { deleted += id }
  override suspend fun reorder(orderedIds: List<String>) { reordered += orderedIds }

  fun setAll(v: List<ProjectEntity>) = all.let { it.value = v }
}

class FakeSubtaskRepo(
  private val byTask: MutableStateFlow<List<SubtaskEntity>> = MutableStateFlow(emptyList()),
) : SubtaskRepository {
  val created = mutableListOf<Pair<String, String>>() // taskId,title
  val updated = mutableListOf<String>()
  val deleted = mutableListOf<String>()

  override fun observeByTask(taskId: String): Flow<List<SubtaskEntity>> = byTask
  override suspend fun create(taskId: String, title: String): String { created += taskId to title; return "sub-${created.size}" }
  override suspend fun update(id: String, title: String?, completed: Boolean?, sortOrder: Int?) { updated += id }
  override suspend fun delete(id: String) { deleted += id }

  fun setByTask(v: List<SubtaskEntity>) = byTask.let { it.value = v }
}
