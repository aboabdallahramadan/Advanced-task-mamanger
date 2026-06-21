package net.qmindtech.tmap.ui.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.local.entities.ProjectEntity
import net.qmindtech.tmap.data.repository.ProjectRepository
import net.qmindtech.tmap.data.repository.TaskRepository
import javax.inject.Inject

data class ProjectRow(val project: ProjectEntity, val openTaskCount: Int)

data class ProjectsUiState(
  val loading: Boolean = true,
  val rows: List<ProjectRow> = emptyList(),
)

@HiltViewModel
class ProjectsViewModel @Inject constructor(
  private val projectRepo: ProjectRepository,
  taskRepo: TaskRepository,
) : ViewModel() {

  val uiState: StateFlow<ProjectsUiState> =
    combine(projectRepo.observeAll(), taskRepo.observeAll()) { projects, tasks ->
      val openByProject = tasks
        .filter { it.status != TaskStatus.Done && it.status != TaskStatus.Archived && it.projectId != null }
        .groupingBy { it.projectId!! }
        .eachCount()
      ProjectsUiState(
        loading = false,
        rows = projects.map { ProjectRow(it, openByProject[it.id] ?: 0) },
      )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProjectsUiState())

  fun create(name: String, color: String, emoji: String) {
    val n = name.trim()
    if (n.isEmpty()) return
    viewModelScope.launch { projectRepo.create(n, color, emoji) }
  }

  fun update(id: String, name: String, color: String, emoji: String) {
    val n = name.trim()
    if (n.isEmpty()) return
    viewModelScope.launch { projectRepo.update(id, name = n, color = color, emoji = emoji) }
  }

  fun delete(id: String) {
    viewModelScope.launch { projectRepo.delete(id) }
  }

  fun moveProject(fromIndex: Int, toIndex: Int) {
    val current = uiState.value.rows.map { it.project.id }.toMutableList()
    if (fromIndex !in current.indices || toIndex !in current.indices) return
    val moved = current.removeAt(fromIndex)
    current.add(toIndex, moved)
    viewModelScope.launch { projectRepo.reorder(current) }
  }
}
