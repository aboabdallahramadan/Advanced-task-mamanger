package net.qmindtech.tmap.ui.projects

import androidx.lifecycle.SavedStateHandle
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
import net.qmindtech.tmap.ui.browse.BrowseTaskItem
import net.qmindtech.tmap.ui.browse.TaskFilter
import net.qmindtech.tmap.ui.browse.applyTaskFilter
import net.qmindtech.tmap.ui.navigation.Route
import javax.inject.Inject

data class ProjectDetailUiState(
  val loading: Boolean = true,
  val project: ProjectEntity? = null,
  val total: Int = 0,
  val done: Int = 0,
  val items: List<BrowseTaskItem> = emptyList(),
) {
  val progress: Float get() = if (total == 0) 0f else done.toFloat() / total
}

@HiltViewModel
class ProjectDetailViewModel @Inject constructor(
  savedStateHandle: SavedStateHandle,
  private val taskRepo: TaskRepository,
  private val projectRepo: ProjectRepository,
) : ViewModel() {

  private val projectId: String =
    savedStateHandle.get<String>(Route.ProjectDetail.ARG_PROJECT_ID).orEmpty()

  val uiState: StateFlow<ProjectDetailUiState> =
    combine(
      taskRepo.observeAll(),
      projectRepo.observeAll(),
      projectRepo.observeProgress(),
    ) { tasks, projects, progress ->
      val project = projects.firstOrNull { it.id == projectId }
      val pr = progress.firstOrNull { it.projectId == projectId }
      // Project-scoped, manual-rank list reusing the Browse engine.
      val groups = applyTaskFilter(tasks, projects, TaskFilter(projectIds = setOf(projectId)))
      ProjectDetailUiState(
        loading = false,
        project = project,
        total = pr?.total ?: 0,
        done = pr?.done ?: 0,
        items = groups.flatMap { it.items },
      )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProjectDetailUiState())

  fun toggleDone(task: TaskEntity) {
    viewModelScope.launch { taskRepo.markDone(task.id) }
  }

  fun update(name: String, color: String, emoji: String) {
    val n = name.trim()
    if (n.isEmpty()) return
    viewModelScope.launch { projectRepo.update(projectId, name = n, color = color, emoji = emoji) }
  }

  fun delete() {
    viewModelScope.launch { projectRepo.delete(projectId) }
  }
}
