package net.qmindtech.tmap.ui.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.local.entities.TaskEntity
import net.qmindtech.tmap.data.repository.ProjectRepository
import net.qmindtech.tmap.data.repository.TaskDraft
import net.qmindtech.tmap.data.repository.TaskRepository
import net.qmindtech.tmap.ui.today.TaskListItem
import javax.inject.Inject

data class InboxUiState(
  val loading: Boolean = true,
  val items: List<TaskListItem> = emptyList(),
)

@HiltViewModel
class InboxViewModel @Inject constructor(
  private val taskRepo: TaskRepository,
  projectRepo: ProjectRepository,
) : ViewModel() {

  val uiState: StateFlow<InboxUiState> =
    combine(taskRepo.observeByStatus(TaskStatus.Inbox), projectRepo.observeAll()) { tasks, projects ->
      val names = projects.associate { it.id to it.name }
      InboxUiState(
        loading = false,
        items = tasks.map { TaskListItem(it, it.projectId?.let { pid -> names[pid] }) },
      )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InboxUiState())

  fun quickAdd(title: String) {
    val trimmed = title.trim()
    if (trimmed.isEmpty()) return
    viewModelScope.launch {
      taskRepo.create(TaskDraft(title = trimmed, status = TaskStatus.Inbox))
    }
  }

  fun toggleDone(task: TaskEntity) {
    viewModelScope.launch { taskRepo.markDone(task.id) }
  }
}
