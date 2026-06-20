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
import java.time.LocalDate
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
  private val taskId: String? = rawId?.takeIf { it.isNotBlank() && it != "new" }

  private val _state = MutableStateFlow(
    if (taskId == null) TaskEditorUiState(isEdit = false, loading = false) else TaskEditorUiState()
  )
  val uiState: StateFlow<TaskEditorUiState> = _state.asStateFlow()

  init {
    if (taskId != null) {
      viewModelScope.launch {
        combine(
          taskRepo.observe(taskId),
          subtaskRepo.observeByTask(taskId),
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

  fun save(onDone: () -> Unit) {
    val s = _state.value
    if (s.title.isBlank()) return
    viewModelScope.launch {
      if (taskId == null) taskRepo.create(s.toDraft()) else taskRepo.update(taskId, s.toEdit())
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
