package net.qmindtech.tmap.ui.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.qmindtech.tmap.data.local.dao.ProjectProgress
import net.qmindtech.tmap.data.local.entities.ProjectEntity
import net.qmindtech.tmap.data.repository.ProjectRepository
import javax.inject.Inject

data class ProjectRow(val project: ProjectEntity, val total: Int, val done: Int) {
  val openTaskCount: Int get() = total - done
  val progress: Float get() = if (total == 0) 0f else done.toFloat() / total
}

data class ProjectsHeader(val projectCount: Int, val doneTotal: Int, val taskTotal: Int)

data class ProjectsUiState(
  val loading: Boolean = true,
  val rows: List<ProjectRow> = emptyList(),
  val header: ProjectsHeader = ProjectsHeader(0, 0, 0),
)

@HiltViewModel
class ProjectsViewModel @Inject constructor(
  private val projectRepo: ProjectRepository,
) : ViewModel() {

  val uiState: StateFlow<ProjectsUiState> =
    combine(projectRepo.observeAll(), projectRepo.observeProgress()) { projects, progress ->
      val byId: Map<String, ProjectProgress> = progress.associateBy { it.projectId }
      val rows = projects.map { p ->
        val pr = byId[p.id]
        ProjectRow(p, total = pr?.total ?: 0, done = pr?.done ?: 0)
      }
      ProjectsUiState(
        loading = false,
        rows = rows,
        header = ProjectsHeader(
          projectCount = projects.size,
          doneTotal = rows.sumOf { it.done },
          taskTotal = rows.sumOf { it.total },
        ),
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
