package net.qmindtech.tmap.ui.taskeditor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.local.entities.SubtaskEntity
import net.qmindtech.tmap.data.repository.ProjectRepository
import net.qmindtech.tmap.data.repository.SubtaskRepository
import net.qmindtech.tmap.data.repository.TaskRepository
import net.qmindtech.tmap.util.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject

@HiltViewModel
class TaskEditorViewModel @Inject constructor(
  private val taskRepo: TaskRepository,
  private val subtaskRepo: SubtaskRepository,
  private val projectRepo: ProjectRepository,
  private val clock: Clock,
  savedStateHandle: SavedStateHandle,
) : ViewModel() {

  // "new" sentinel and null both mean create-mode.
  private val rawId: String? = savedStateHandle.get<String?>("taskId")
  private val initialTaskId: String? = rawId?.takeIf { it.isNotBlank() && it != "new" }

  // Mutable so load() can override the value set by SavedStateHandle (sheet usage).
  private var taskId: String? = initialTaskId

  private val _state = MutableStateFlow(
    if (initialTaskId == null) TaskEditorUiState(isEdit = false, loading = false) else TaskEditorUiState()
  )
  val uiState: StateFlow<TaskEditorUiState> = _state.asStateFlow()

  init {
    startObserving(initialTaskId)
  }

  /**
   * Called by [TaskEditorSheet] when the sheet is opened with a specific taskId (sheet path
   * bypasses SavedStateHandle injection, so the composable passes the id explicitly).
   *
   * `null` / `"new"` → create-mode.  Any other non-blank string → edit-mode.
   *
   * This is safe to call in a [LaunchedEffect] — it replaces the active observation job
   * only if the id is actually different from the current one.
   */
  fun load(id: String?) {
    val resolved = id?.takeIf { it.isNotBlank() && it != "new" }
    if (resolved == taskId && _state.value.title.isNotBlank()) return   // already loaded
    taskId = resolved
    _state.value = if (resolved == null) {
      TaskEditorUiState(isEdit = false, loading = false)
    } else {
      TaskEditorUiState()
    }
    startObserving(resolved)
  }

  private fun startObserving(id: String?) {
    if (id != null) {
      viewModelScope.launch {
        combine(
          taskRepo.observe(id),
          subtaskRepo.observeByTask(id),
          projectRepo.observeAll(),
        ) { task, subs, projects ->
          Triple(task, subs, projects)
        }.collect { (task, subs, projects) ->
          if (task != null) {
            _state.value = task.toEditorState(subs, projects)
          } else {
            _state.update { it.copy(loading = false, projects = projects) }
          }
        }
      }
    } else {
      viewModelScope.launch {
        projectRepo.observeAll().collect { projects -> _state.update { it.copy(projects = projects) } }
      }
    }
  }

  fun onTitleChange(s: String) = _state.update { it.copy(title = s) }
  fun onNotesChange(s: String) = _state.update { it.copy(notes = s) }
  fun onProjectChange(id: String?) = _state.update { it.copy(projectId = id) }
  fun onStatusChange(s: TaskStatus) = _state.update { it.copy(status = s) }
  fun onPriorityChange(p: Int?) = _state.update { it.copy(priority = p) }
  fun onPlannedDateChange(d: LocalDate?) = _state.update { it.copy(plannedDate = d) }
  fun onDurationChange(m: Int?) = _state.update { it.copy(durationMinutes = m) }
  fun onReminderChange(m: Int?) = _state.update { it.copy(reminderMinutes = m) }

  fun onScheduledStartChange(instant: Instant?) = _state.update {
    val end = it.scheduledEnd
    val dur = if (instant != null && end != null) Duration.between(instant, end).toMinutes().toInt() else it.durationMinutes
    it.copy(scheduledStart = instant, durationMinutes = dur)
  }

  fun onScheduledEndChange(instant: Instant?) = _state.update {
    val start = it.scheduledStart
    val dur = if (start != null && instant != null) Duration.between(start, instant).toMinutes().toInt() else it.durationMinutes
    it.copy(scheduledEnd = instant, durationMinutes = dur)
  }

  fun onDueDateChange(date: LocalDate?) = _state.update { it.copy(dueDate = date) }

  /**
   * Exposed so composables can obtain the user's timezone for building Instants from LocalTime
   * without depending on [Clock] directly.
   */
  fun zone(): ZoneId = clock.zone()

  /**
   * Pick a scheduled-start time.  The date anchor is [TaskEditorUiState.plannedDate] if set,
   * otherwise [Clock.today].  Converts LocalDate+LocalTime → Instant and delegates to
   * [onScheduledStartChange], preserving duration-recompute logic already there.
   */
  fun onScheduledStartTimeChange(time: LocalTime) {
    val date = _state.value.plannedDate ?: clock.today()
    val instant = date.atTime(time).atZone(clock.zone()).toInstant()
    onScheduledStartChange(instant)
  }

  /**
   * Pick a scheduled-end time.  Date anchor same as [onScheduledStartTimeChange].
   * Delegates to [onScheduledEndChange], preserving duration-recompute logic.
   */
  fun onScheduledEndTimeChange(time: LocalTime) {
    val date = _state.value.plannedDate ?: clock.today()
    val instant = date.atTime(time).atZone(clock.zone()).toInstant()
    onScheduledEndChange(instant)
  }

  fun reorderSubtasks(orderedIds: List<String>) {
    if (taskId == null) return
    viewModelScope.launch {
      orderedIds.forEachIndexed { i, sid -> subtaskRepo.update(sid, sortOrder = i) }
    }
  }

  fun save(onDone: () -> Unit) {
    val s = _state.value
    if (s.title.isBlank()) return
    val id = taskId   // capture before coroutine; taskId is a var and can't be smart-cast inside lambda
    viewModelScope.launch {
      if (id == null) taskRepo.create(s.toDraft()) else taskRepo.update(id, s.toEdit())
      _state.update { it.copy(saved = true) }
      onDone()
    }
  }

  fun markDone() {
    val id = taskId ?: return
    _state.update { it.copy(status = TaskStatus.Done, completedAt = clock.now()) }
    viewModelScope.launch { taskRepo.markDone(id) }
  }

  fun delete(onDone: () -> Unit) {
    val id = taskId ?: run { onDone(); return }
    viewModelScope.launch { taskRepo.delete(id); onDone() }
  }

  fun addSubtask(title: String) {
    val id = taskId ?: return
    val t = title.trim()
    if (t.isEmpty()) return
    viewModelScope.launch { subtaskRepo.create(id, t) }
  }

  fun toggleSubtask(s: SubtaskEntity) {
    if (taskId == null) return
    viewModelScope.launch { subtaskRepo.update(s.id, completed = !s.completed) }
  }

  fun renameSubtask(s: SubtaskEntity, title: String) {
    if (taskId == null) return
    val t = title.trim()
    if (t.isEmpty() || t == s.title) return
    viewModelScope.launch { subtaskRepo.update(s.id, title = t) }
  }

  fun deleteSubtask(id: String) {
    if (taskId == null) return
    viewModelScope.launch { subtaskRepo.delete(id) }
  }
}
