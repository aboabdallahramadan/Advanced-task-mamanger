package net.qmindtech.tmap.ui.backlog

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
import net.qmindtech.tmap.data.repository.TaskRepository
import net.qmindtech.tmap.ui.today.TaskListItem
import javax.inject.Inject

data class BacklogUiState(
  val loading: Boolean = true,
  val items: List<TaskListItem> = emptyList(),
)

@HiltViewModel
class BacklogViewModel @Inject constructor(
  private val taskRepo: TaskRepository,
  projectRepo: ProjectRepository,
) : ViewModel() {

  val uiState: StateFlow<BacklogUiState> =
    combine(taskRepo.observeByStatus(TaskStatus.Backlog), projectRepo.observeAll()) { tasks, projects ->
      val names = projects.associate { it.id to it.name }
      BacklogUiState(
        loading = false,
        items = tasks.map { TaskListItem(it, it.projectId?.let { pid -> names[pid] }) },
      )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BacklogUiState())

  fun toggleDone(task: TaskEntity) {
    viewModelScope.launch { taskRepo.markDone(task.id) }
  }
}
