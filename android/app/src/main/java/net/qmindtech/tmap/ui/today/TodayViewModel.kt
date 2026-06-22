package net.qmindtech.tmap.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.qmindtech.tmap.data.local.entities.ProjectEntity
import net.qmindtech.tmap.data.local.entities.TaskEntity
import net.qmindtech.tmap.data.repository.ProjectRepository
import net.qmindtech.tmap.data.repository.TaskRepository
import net.qmindtech.tmap.util.Clock
import javax.inject.Inject

data class TaskListItem(val task: TaskEntity, val projectName: String?)

/** Legacy list-only state kept until P1.3 rebuilds the ViewModel. */
data class TodayListUiState(
  val loading: Boolean = true,
  val items: List<TaskListItem> = emptyList(),
)

fun timeOrderToday(tasks: List<TaskEntity>, projects: List<ProjectEntity>): List<TaskListItem> {
  val names = projects.associate { it.id to it.name }
  val far = java.time.Instant.MAX
  return tasks
    .sortedWith(
      compareBy<TaskEntity> { it.scheduledStart ?: far }
        .thenBy { it.plannedDate ?: java.time.LocalDate.MAX }
        .thenBy { it.createdAt }
    )
    .map { TaskListItem(it, it.projectId?.let { pid -> names[pid] }) }
}

@HiltViewModel
class TodayViewModel @Inject constructor(
  private val taskRepo: TaskRepository,
  projectRepo: ProjectRepository,
  clock: Clock,
) : ViewModel() {

  val uiState: StateFlow<TodayListUiState> =
    combine(taskRepo.observeToday(clock.today()), projectRepo.observeAll()) { tasks, projects ->
      TodayListUiState(loading = false, items = timeOrderToday(tasks, projects))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayListUiState())

  fun toggleDone(task: TaskEntity) {
    viewModelScope.launch { taskRepo.markDone(task.id) }
  }
}
