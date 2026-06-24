package net.qmindtech.tmap.testutil

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.local.dao.ProjectProgress
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
  override fun zone(): java.time.ZoneId = java.time.ZoneOffset.UTC
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

  // Per-id flows for testing multi-task observation scenarios.
  private val perIdFlows = mutableMapOf<String, MutableStateFlow<TaskEntity?>>()

  override fun observeAll(): Flow<List<TaskEntity>> = all
  override fun observeToday(today: LocalDate): Flow<List<TaskEntity>> = this.today
  override fun observeByStatus(s: TaskStatus): Flow<List<TaskEntity>> = byStatus
  override fun observe(id: String): Flow<TaskEntity?> =
    perIdFlows[id] ?: single
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

  /** Sets up a per-id flow so [observe] returns a flow scoped to [entity.id]. */
  fun setForId(entity: TaskEntity) {
    perIdFlows.getOrPut(entity.id) { MutableStateFlow(null) }.value = entity
  }

  /** Emits a new value on the per-id flow for [id] (must have been set via [setForId] first). */
  fun emitForId(id: String, entity: TaskEntity?) {
    perIdFlows[id]?.value = entity
  }
}

class FakeProjectRepo(
  private val all: MutableStateFlow<List<ProjectEntity>> = MutableStateFlow(emptyList()),
  private val progress: MutableStateFlow<List<ProjectProgress>> = MutableStateFlow(emptyList()),
) : ProjectRepository {
  val created = mutableListOf<Triple<String, String, String>>() // name,color,emoji
  val updated = mutableListOf<String>()
  val deleted = mutableListOf<String>()
  val reordered = mutableListOf<List<String>>()

  override fun observeAll(): Flow<List<ProjectEntity>> = all
  override fun observeProgress(): Flow<List<ProjectProgress>> = progress
  override suspend fun create(name: String, color: String, emoji: String): String {
    created += Triple(name, color, emoji); return "proj-${created.size}"
  }
  override suspend fun update(id: String, name: String?, color: String?, emoji: String?) { updated += id }
  override suspend fun delete(id: String) { deleted += id }
  override suspend fun reorder(orderedIds: List<String>) { reordered += orderedIds }

  fun setAll(v: List<ProjectEntity>) = all.let { it.value = v }
  fun setProgress(v: List<ProjectProgress>) = progress.let { it.value = v }
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

fun fakeNote(
  id: String,
  groupId: String? = null,
  projectId: String? = null,
  title: String = "Note $id",
  content: String = "",
  rank: String? = null,
  pinnedAt: Instant? = null,
  createdAt: Instant = EPOCH,
  updatedAt: Instant = EPOCH,
  changeSeq: Long = 0,
): net.qmindtech.tmap.data.local.entities.NoteEntity =
  net.qmindtech.tmap.data.local.entities.NoteEntity(
    id = id, groupId = groupId, projectId = projectId, title = title, content = content,
    rank = rank, createdAt = createdAt, updatedAt = updatedAt, changeSeq = changeSeq,
    deletedAt = null, pinnedAt = pinnedAt,
  )

fun fakeNoteGroup(
  id: String,
  name: String = "Notebook $id",
  emoji: String = "📓",
  projectId: String? = null,
  rank: String? = null,
  createdAt: Instant = EPOCH,
): net.qmindtech.tmap.data.local.entities.NoteGroupEntity =
  net.qmindtech.tmap.data.local.entities.NoteGroupEntity(
    id = id, name = name, emoji = emoji, projectId = projectId, rank = rank,
    createdAt = createdAt, updatedAt = createdAt, changeSeq = 0, deletedAt = null,
  )

data class NoteDraftRecord(
  val title: String,
  val content: String,
  val groupId: String?,
  val projectId: String?,
)

class FakeNoteRepo(
  val allFlow: MutableStateFlow<List<net.qmindtech.tmap.data.local.entities.NoteEntity>> =
    MutableStateFlow(emptyList()),
  val singleFlow: MutableStateFlow<net.qmindtech.tmap.data.local.entities.NoteEntity?> =
    MutableStateFlow(null),
) : net.qmindtech.tmap.data.repository.NoteRepository {
  var lastObserveAllArgs: Pair<String?, String?>? = null
  val created = mutableListOf<NoteDraftRecord>()
  val updated = mutableListOf<String>()
  val deleted = mutableListOf<String>()
  val pinned = mutableListOf<Pair<String, Boolean>>()
  val reordered = mutableListOf<List<String>>()
  var nextId = "note-new"

  override fun observeAll(
    groupId: String?,
    projectId: String?,
  ): kotlinx.coroutines.flow.Flow<List<net.qmindtech.tmap.data.local.entities.NoteEntity>> {
    lastObserveAllArgs = groupId to projectId
    return allFlow
  }

  override fun observe(
    id: String,
  ): kotlinx.coroutines.flow.Flow<net.qmindtech.tmap.data.local.entities.NoteEntity?> = singleFlow

  override suspend fun create(
    title: String,
    content: String,
    groupId: String?,
    projectId: String?,
  ): String {
    created += NoteDraftRecord(title, content, groupId, projectId)
    return nextId
  }

  override suspend fun update(
    id: String,
    title: String?,
    content: String?,
    groupId: String?,
    projectId: String?,
  ) {
    updated += id
  }

  override suspend fun delete(id: String) { deleted += id }

  override suspend fun setPinned(id: String, pinned: Boolean) { this.pinned += id to pinned }

  override suspend fun reorder(ids: List<String>) { reordered += ids }
}

class FakeNoteGroupRepo(
  val allFlow: MutableStateFlow<List<net.qmindtech.tmap.data.local.entities.NoteGroupEntity>> =
    MutableStateFlow(emptyList()),
) : net.qmindtech.tmap.data.repository.NoteGroupRepository {
  val created = mutableListOf<Triple<String, String, String?>>()
  val updated = mutableListOf<String>()
  val deleted = mutableListOf<String>()
  val reordered = mutableListOf<List<String>>()
  var nextId = "group-new"

  override fun observeAll(): kotlinx.coroutines.flow.Flow<List<net.qmindtech.tmap.data.local.entities.NoteGroupEntity>> =
    allFlow

  override suspend fun create(name: String, emoji: String, projectId: String?): String {
    created += Triple(name, emoji, projectId)
    return nextId
  }

  override suspend fun update(id: String, name: String?, emoji: String?, projectId: String?) {
    updated += id
  }

  override suspend fun delete(id: String) { deleted += id }

  override suspend fun reorder(ids: List<String>) { reordered += ids }
}
