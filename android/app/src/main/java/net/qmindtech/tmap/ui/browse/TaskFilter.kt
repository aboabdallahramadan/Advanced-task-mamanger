package net.qmindtech.tmap.ui.browse

import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.local.entities.ProjectEntity
import net.qmindtech.tmap.data.local.entities.TaskEntity
import net.qmindtech.tmap.ui.common.htmlToPlainText
import net.qmindtech.tmap.ui.components.PriorityDisplay
import net.qmindtech.tmap.ui.components.StatusDisplay
import net.qmindtech.tmap.ui.components.TaskUi
import net.qmindtech.tmap.ui.components.toUi
import java.time.LocalDate

enum class SortField { CreatedAt, Priority, PlannedDate, Title, Status, ManualRank }
enum class SortDirection { Asc, Desc }
enum class GroupBy { None, Status, Project, Priority }

/** Filter/sort/group spec for the Browse hub. Pure data; defaults to the user's manual order. */
data class TaskFilter(
  val search: String = "",
  val statuses: Set<TaskStatus> = NON_ARCHIVED_STATUSES,
  val showArchived: Boolean = false,
  val priorities: Set<Int?> = ALL_PRIORITIES,
  val projectIds: Set<String>? = null,
  val dateFrom: LocalDate? = null,
  val dateTo: LocalDate? = null,
  val sortField: SortField = SortField.ManualRank,
  val sortDirection: SortDirection = SortDirection.Asc,
  val groupBy: GroupBy = GroupBy.None,
) {
  companion object {
    val NON_ARCHIVED_STATUSES: Set<TaskStatus> = setOf(
      TaskStatus.Inbox, TaskStatus.Backlog, TaskStatus.Planned, TaskStatus.Scheduled, TaskStatus.Done,
    )
    val ALL_PRIORITIES: Set<Int?> = setOf(1, 2, 3, 4, null)
  }
}

/** A Browse row: the FIXED TaskUi projection (for TaskCard) plus the raw entity (actions/keys). */
data class BrowseTaskItem(val ui: TaskUi, val task: TaskEntity)

data class TaskGroup(val key: String, val label: String, val items: List<BrowseTaskItem>)

/**
 * Plain-text projection of a (possibly HTML) note body for SEARCH. Delegates to the shared
 * [htmlToPlainText] so search matches decoded text (entities like `&amp;` / `&#39;`), not just
 * tag-stripped raw HTML. Kept as a named function for any existing call sites.
 */
fun stripHtml(s: String?): String = htmlToPlainText(s)

fun applyTaskFilter(
  tasks: List<TaskEntity>,
  projects: List<ProjectEntity>,
  filter: TaskFilter,
): List<TaskGroup> {
  val projectById: Map<String, ProjectEntity> = projects.associateBy { it.id }
  val query = filter.search.trim().lowercase()

  val filtered = tasks.filter { t ->
    // Status (archived gated by showArchived; others by the status set)
    if (t.status == TaskStatus.Archived) {
      if (!filter.showArchived) return@filter false
    } else {
      if (t.status !in filter.statuses) return@filter false
    }
    // Priority (set may contain null)
    if (t.priority !in filter.priorities) return@filter false
    // Project (null filter = no constraint; "" = no project)
    filter.projectIds?.let { sel ->
      if ((t.projectId ?: "") !in sel) return@filter false
    }
    // Date range (inclusive; null plannedDate fails when a bound is set)
    if (filter.dateFrom != null && (t.plannedDate == null || t.plannedDate < filter.dateFrom)) return@filter false
    if (filter.dateTo != null && (t.plannedDate == null || t.plannedDate > filter.dateTo)) return@filter false
    // Search over title + notes(html-stripped) + project name
    if (query.isNotEmpty()) {
      val name = t.projectId?.let { projectById[it]?.name } ?: ""
      val matches = t.title.lowercase().contains(query) ||
        stripHtml(t.notes).lowercase().contains(query) ||
        name.lowercase().contains(query)
      if (!matches) return@filter false
    }
    true
  }

  val sorted = filtered.sortedWith(comparatorFor(filter))
  val items = sorted.map { BrowseTaskItem(it.toUi(it.projectId?.let { pid -> projectById[pid] }), it) }

  if (filter.groupBy == GroupBy.None) {
    return listOf(TaskGroup(key = "all", label = "All Tasks", items = items))
  }

  val ordered = LinkedHashMap<String, MutableList<BrowseTaskItem>>()
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
      GroupBy.Project -> if (key.isEmpty()) "No Project" else (projectById[key]?.name ?: key)
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
    // Null ranks sort last in ascending order (matches DAO "rank IS NULL, rank").
    SortField.ManualRank -> compareBy(nullsLast()) { it.rank }
  }
  return if (filter.sortDirection == SortDirection.Asc) base else base.reversed()
}
