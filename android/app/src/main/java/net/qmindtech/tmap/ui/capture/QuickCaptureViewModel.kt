package net.qmindtech.tmap.ui.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.local.entities.ProjectEntity
import net.qmindtech.tmap.data.repository.ProjectRepository
import net.qmindtech.tmap.data.repository.TaskDraft
import net.qmindtech.tmap.data.repository.TaskRepository
import net.qmindtech.tmap.util.Clock
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject

@HiltViewModel
class QuickCaptureViewModel @Inject constructor(
  private val taskRepo: TaskRepository,
  private val projectRepo: ProjectRepository,
  private val parser: QuickCaptureParser,
  private val clock: Clock,
) : ViewModel() {

  // Chip overrides layered over the parsed result.
  private var dateOverride: LocalDate? = null
  private var clearDate: Boolean = false
  private var priorityOverride: Int? = null

  private val _state = MutableStateFlow(QuickCaptureUiState())
  val uiState: StateFlow<QuickCaptureUiState> = _state.asStateFlow()

  init {
    viewModelScope.launch {
      projectRepo.observeAll().collect { projects ->
        _state.update { it.copy(projects = projects, parsed = reparse(it.text, projects)) }
      }
    }
  }

  private fun reparse(text: String, projects: List<ProjectEntity>) =
    parser.parse(text, projects)

  fun onTextChange(s: String) {
    _state.update {
      val p = reparse(s, it.projects)
      it.copy(text = s, parsed = p, canSubmit = p.title.isNotBlank())
    }
  }

  fun chipToday() {
    clearDate = false
    dateOverride = clock.today()
  }

  fun chipInbox() {
    clearDate = true
    dateOverride = null
  }

  fun chipPriority() {
    priorityOverride = when (priorityOverride ?: _state.value.parsed.priority) {
      0 -> 2; 2 -> 1; else -> 0
    }.takeIf { it != 0 }
  }

  fun chipRemind() {
    _state.update { it.copy(remind = !it.remind) }
  }

  fun submit() {
    val s = _state.value
    val title = s.parsed.title
    if (title.isBlank()) return
    val effectiveDate = when {
      clearDate -> null
      dateOverride != null -> dateOverride
      else -> s.parsed.plannedDate
    }
    val effectivePriority = (priorityOverride ?: s.parsed.priority).takeIf { it > 0 }
    val start: LocalTime? = s.parsed.scheduledStart
    val startInstant = if (effectiveDate != null && start != null) {
      effectiveDate.atTime(start).atZone(clock.zone()).toInstant()
    } else null
    val draft = TaskDraft(
      title = title,
      projectId = s.parsed.projectId,
      priority = effectivePriority,
      plannedDate = effectiveDate,
      scheduledStart = startInstant,
      status = if (effectiveDate != null) TaskStatus.Planned else TaskStatus.Inbox,
      reminderMinutes = if (s.remind) 0 else null,
    )
    viewModelScope.launch { taskRepo.create(draft) }
    // Reset for rapid-fire capture; keep projects, drop chip overrides.
    dateOverride = null
    clearDate = false
    priorityOverride = null
    _state.update { QuickCaptureUiState(projects = it.projects) }
  }
}
