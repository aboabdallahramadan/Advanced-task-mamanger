package net.qmindtech.tmap.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.local.entities.ProjectEntity
import net.qmindtech.tmap.data.local.entities.TaskEntity
import net.qmindtech.tmap.data.repository.ProjectRepository
import net.qmindtech.tmap.data.repository.TaskRepository
import java.time.LocalDate
import javax.inject.Inject

enum class BrowseSegment { AllTasks, Backlog, Projects }

data class BrowseUiState(
  val loading: Boolean = true,
  val segment: BrowseSegment = BrowseSegment.AllTasks,
  val filter: TaskFilter = TaskFilter(),
  val groups: List<TaskGroup> = emptyList(),
  val projects: List<ProjectEntity> = emptyList(),
  val totalCount: Int = 0,
  val activeFilterCount: Int = 0,
)

/** How many filter facets diverge from the cleared default (drives the filter badge). */
fun activeFilterCount(filter: TaskFilter): Int {
  var n = 0
  if (filter.search.isNotBlank()) n++
  if (filter.statuses != TaskFilter.NON_ARCHIVED_STATUSES) n++
  if (filter.showArchived) n++
  if (filter.priorities != TaskFilter.ALL_PRIORITIES) n++
  if (filter.projectIds != null) n++
  if (filter.dateFrom != null || filter.dateTo != null) n++
  return n
}

@HiltViewModel
class BrowseViewModel @Inject constructor(
  private val taskRepo: TaskRepository,
  projectRepo: ProjectRepository,
) : ViewModel() {

  private val segment = MutableStateFlow(BrowseSegment.AllTasks)
  private val filter = MutableStateFlow(TaskFilter())

  val uiState: StateFlow<BrowseUiState> =
    combine(
      taskRepo.observeAll(),
      projectRepo.observeAll(),
      segment,
      filter,
    ) { tasks, projects, seg, f ->
      // Backlog segment forces statuses = {Backlog} on top of the user's filter.
      val effective = if (seg == BrowseSegment.Backlog) f.copy(statuses = setOf(TaskStatus.Backlog)) else f
      val groups = if (seg == BrowseSegment.Projects) emptyList() else applyTaskFilter(tasks, projects, effective)
      BrowseUiState(
        loading = false,
        segment = seg,
        filter = f,
        groups = groups,
        projects = projects,
        totalCount = groups.sumOf { it.items.size },
        activeFilterCount = activeFilterCount(f),
      )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, BrowseUiState())

  fun setSegment(s: BrowseSegment) = segment.update { s }
  fun setSearch(q: String) = filter.update { it.copy(search = q) }
  fun setStatuses(s: Set<TaskStatus>) = filter.update { it.copy(statuses = s) }
  fun setShowArchived(b: Boolean) = filter.update { it.copy(showArchived = b) }
  fun setPriorities(p: Set<Int?>) = filter.update { it.copy(priorities = p) }
  fun setProjectIds(ids: Set<String>?) = filter.update { it.copy(projectIds = ids) }
  fun setDateRange(from: LocalDate?, to: LocalDate?) = filter.update { it.copy(dateFrom = from, dateTo = to) }
  fun setGroupBy(g: GroupBy) = filter.update { it.copy(groupBy = g) }

  fun setSort(field: SortField) = filter.update {
    if (it.sortField == field) {
      it.copy(sortDirection = if (it.sortDirection == SortDirection.Asc) SortDirection.Desc else SortDirection.Asc)
    } else {
      it.copy(sortField = field, sortDirection = SortDirection.Desc)
    }
  }

  fun clearFilters() = filter.update {
    TaskFilter(sortField = it.sortField, sortDirection = it.sortDirection, groupBy = it.groupBy)
  }

  fun toggleDone(task: TaskEntity) {
    viewModelScope.launch { taskRepo.markDone(task.id) }
  }
}
