package net.qmindtech.tmap.ui.alltasks

import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.local.entities.ProjectEntity
import net.qmindtech.tmap.data.local.entities.TaskEntity
import net.qmindtech.tmap.ui.components.PriorityDisplay
import net.qmindtech.tmap.ui.components.StatusDisplay
import net.qmindtech.tmap.ui.today.TaskListItem
import java.time.LocalDate

enum class SortField { CreatedAt, Priority, PlannedDate, Title, Status }
enum class SortDirection { Asc, Desc }
enum class GroupBy { None, Status, Project, Priority }

val NON_ARCHIVED_STATUSES: Set<TaskStatus> = setOf(
  TaskStatus.Inbox, TaskStatus.Backlog, TaskStatus.Planned, TaskStatus.Scheduled, TaskStatus.Done,
)
val ALL_PRIORITIES: Set<Int?> = setOf(1, 2, 3, 4, null)

data class TaskFilter(
  val search: String = "",
  val statuses: Set<TaskStatus> = NON_ARCHIVED_STATUSES,
  val showArchived: Boolean = false,
  val priorities: Set<Int?> = ALL_PRIORITIES,
  val projectIds: Set<String>? = null,
  val dateFrom: LocalDate? = null,
  val dateTo: LocalDate? = null,
  val sortField: SortField = SortField.CreatedAt,
  val sortDirection: SortDirection = SortDirection.Desc,
  val groupBy: GroupBy = GroupBy.None,
)

data class TaskGroup(val key: String, val label: String, val items: List<TaskListItem>)

private val HTML_TAG = Regex("<[^>]*>")
private val WS = Regex("\\s+")

fun stripHtml(s: String?): String =
  s?.replace(HTML_TAG, " ")?.replace(WS, " ")?.trim() ?: ""

fun applyTaskFilter(
  tasks: List<TaskEntity>,
  projects: List<ProjectEntity>,
  filter: TaskFilter,
): List<TaskGroup> {
  val projectName: Map<String, String> = projects.associate { it.id to it.name }
  val query = filter.search.trim().lowercase()

  val filtered = tasks.filter { t ->
    // Status
    if (t.status == TaskStatus.Archived) {
      if (!filter.showArchived) return@filter false
    } else {
      if (t.status !in filter.statuses) return@filter false
    }
    // Priority (set may contain null)
    if (t.priority !in filter.priorities) return@filter false
    // Project (null filter = no constraint; "" = no project)
    filter.projectIds?.let { sel ->
      val key = t.projectId ?: ""
      if (key !in sel) return@filter false
    }
    // Date range (inclusive)
    if (filter.dateFrom != null && (t.plannedDate == null || t.plannedDate < filter.dateFrom)) return@filter false
    if (filter.dateTo != null && (t.plannedDate == null || t.plannedDate > filter.dateTo)) return@filter false
    // Search
    if (query.isNotEmpty()) {
      val name = t.projectId?.let { projectName[it] } ?: ""
      val matches = t.title.lowercase().contains(query) ||
        stripHtml(t.notes).lowercase().contains(query) ||
        name.lowercase().contains(query)
      if (!matches) return@filter false
    }
    true
  }

  val sorted = filtered.sortedWith(comparatorFor(filter))

  val items = sorted.map { TaskListItem(it, it.projectId?.let { pid -> projectName[pid] }) }

  if (filter.groupBy == GroupBy.None) {
    return listOf(TaskGroup(key = "all", label = "All Tasks", items = items))
  }

  val ordered = LinkedHashMap<String, MutableList<TaskListItem>>()
  for (item in items) {
    val t = item.task
    val key = when (filter.groupBy) {
      GroupBy.Status -> t.status.name
      GroupBy.Project -> t.projectId ?: ""
      GroupBy.Priority -> PriorityDisplay.label(t.priority)
      GroupBy.None -> "all"
    }
    ordered.getOrPut(key) { mutableListOf() }.add(item)
  }
  return ordered.map { (key, group) ->
    val label = when (filter.groupBy) {
      GroupBy.Status -> StatusDisplay.label(TaskStatus.valueOf(key))
      GroupBy.Project -> if (key.isEmpty()) "No Project" else (projectName[key] ?: key)
      GroupBy.Priority -> key
      GroupBy.None -> "All Tasks"
    }
    TaskGroup(key = key, label = label, items = group)
  }
}

private fun comparatorFor(filter: TaskFilter): Comparator<TaskEntity> {
  val base: Comparator<TaskEntity> = when (filter.sortField) {
    SortField.CreatedAt -> compareBy { it.createdAt }
    SortField.Priority -> compareBy { it.priority ?: 99 }
    SortField.PlannedDate -> compareBy { it.plannedDate?.toString() ?: "" }
    SortField.Title -> compareBy { it.title }
    SortField.Status -> compareBy { StatusDisplay.order.getValue(it.status) }
  }
  return if (filter.sortDirection == SortDirection.Asc) base else base.reversed()
}
