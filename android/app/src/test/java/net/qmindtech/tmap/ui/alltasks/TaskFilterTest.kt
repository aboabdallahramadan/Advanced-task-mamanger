package net.qmindtech.tmap.ui.alltasks

import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.testutil.fakeProject
import net.qmindtech.tmap.testutil.fakeTask
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class TaskFilterTest {
  private val projects = listOf(
    fakeProject(id = "p1", name = "Work", emoji = "💼"),
    fakeProject(id = "p2", name = "حجوزات عيادات", emoji = "🏥"),
  )

  private fun ids(groups: List<TaskGroup>) = groups.flatMap { g -> g.items.map { it.task.id } }

  // ---- defaults ----
  @Test fun default_filter_excludes_archived_includes_others_one_group() {
    val tasks = listOf(
      fakeTask(id = "a", status = TaskStatus.Inbox),
      fakeTask(id = "b", status = TaskStatus.Archived),
    )
    val out = applyTaskFilter(tasks, projects, TaskFilter())
    assertEquals(1, out.size)
    assertEquals("all", out.first().key)
    assertEquals("All Tasks", out.first().label)
    assertEquals(listOf("a"), ids(out))
  }

  // ---- status filter (multi) ----
  @Test fun applyTaskFilter_empty_statuses_excludes_all_non_archived() {
    // An empty statuses set is NOT treated as "match all" — it means no non-archived
    // status is selected, so all non-archived tasks are excluded (intentional semantics).
    val tasks = listOf(
      fakeTask(id = "i", status = TaskStatus.Inbox),
      fakeTask(id = "p", status = TaskStatus.Planned),
    )
    val out = applyTaskFilter(tasks, projects, TaskFilter(statuses = emptySet()))
    assertEquals(emptyList<String>(), ids(out))
  }

  @Test fun status_filter_keeps_only_selected_non_archived() {
    val tasks = listOf(
      fakeTask(id = "i", status = TaskStatus.Inbox),
      fakeTask(id = "p", status = TaskStatus.Planned),
      fakeTask(id = "d", status = TaskStatus.Done),
    )
    val out = applyTaskFilter(tasks, projects, TaskFilter(statuses = setOf(TaskStatus.Planned)))
    assertEquals(listOf("p"), ids(out))
  }

  @Test fun showArchived_toggle_includes_archived_independently() {
    val tasks = listOf(
      fakeTask(id = "i", status = TaskStatus.Inbox),
      fakeTask(id = "ar", status = TaskStatus.Archived),
    )
    val out = applyTaskFilter(tasks, projects, TaskFilter(showArchived = true))
    assertEquals(setOf("i", "ar"), ids(out).toSet())
  }

  // ---- priority filter (multi, incl null) ----
  @Test fun priority_filter_multi_including_null() {
    val tasks = listOf(
      fakeTask(id = "u", priority = 1),
      fakeTask(id = "m", priority = 3),
      fakeTask(id = "n", priority = null),
    )
    val out = applyTaskFilter(tasks, projects, TaskFilter(priorities = setOf(1, null)))
    assertEquals(setOf("u", "n"), ids(out).toSet())
  }

  // ---- project filter (multi; null = no constraint) ----
  @Test fun project_null_filter_means_all_pass() {
    val tasks = listOf(fakeTask(id = "a", projectId = "p1"), fakeTask(id = "b", projectId = null))
    val out = applyTaskFilter(tasks, projects, TaskFilter(projectIds = null))
    assertEquals(setOf("a", "b"), ids(out).toSet())
  }

  @Test fun project_filter_matches_id_and_empty_for_no_project() {
    val tasks = listOf(
      fakeTask(id = "a", projectId = "p1"),
      fakeTask(id = "b", projectId = "p2"),
      fakeTask(id = "c", projectId = null),
    )
    val workOnly = applyTaskFilter(tasks, projects, TaskFilter(projectIds = setOf("p1")))
    assertEquals(listOf("a"), ids(workOnly))
    val noneOnly = applyTaskFilter(tasks, projects, TaskFilter(projectIds = setOf("")))
    assertEquals(listOf("c"), ids(noneOnly))
  }

  // ---- date range (inclusive; null plannedDate fails when bound set) ----
  @Test fun date_range_inclusive_and_excludes_null_planned() {
    val tasks = listOf(
      fakeTask(id = "before", plannedDate = LocalDate.of(2026, 6, 1)),
      fakeTask(id = "inLow", plannedDate = LocalDate.of(2026, 6, 10)),
      fakeTask(id = "inHigh", plannedDate = LocalDate.of(2026, 6, 20)),
      fakeTask(id = "after", plannedDate = LocalDate.of(2026, 6, 25)),
      fakeTask(id = "noDate", plannedDate = null),
    )
    val out = applyTaskFilter(
      tasks, projects,
      TaskFilter(dateFrom = LocalDate.of(2026, 6, 10), dateTo = LocalDate.of(2026, 6, 20)),
    )
    assertEquals(setOf("inLow", "inHigh"), ids(out).toSet())
  }

  // ---- search (title, notes-html-stripped, project name) ----
  @Test fun search_matches_title_case_insensitive() {
    val tasks = listOf(fakeTask(id = "a", title = "Email Report"), fakeTask(id = "b", title = "Call"))
    val out = applyTaskFilter(tasks, projects, TaskFilter(search = "email"))
    assertEquals(listOf("a"), ids(out))
  }

  @Test fun search_matches_notes_with_html_stripped() {
    val tasks = listOf(fakeTask(id = "a", title = "x", notes = "<p>budget <b>review</b></p>"))
    val out = applyTaskFilter(tasks, projects, TaskFilter(search = "budget review"))
    assertEquals(listOf("a"), ids(out))
  }

  @Test fun search_matches_project_name_including_arabic() {
    val tasks = listOf(fakeTask(id = "a", projectId = "p2"), fakeTask(id = "b", projectId = "p1"))
    val out = applyTaskFilter(tasks, projects, TaskFilter(search = "حجوزات"))
    assertEquals(listOf("a"), ids(out))
  }

  // ---- sort keys ----
  @Test fun sort_createdAt_desc_default() {
    val tasks = listOf(
      fakeTask(id = "old", createdAt = Instant.parse("2026-06-01T00:00:00Z")),
      fakeTask(id = "new", createdAt = Instant.parse("2026-06-10T00:00:00Z")),
    )
    val out = applyTaskFilter(tasks, projects, TaskFilter(sortField = SortField.CreatedAt, sortDirection = SortDirection.Desc))
    assertEquals(listOf("new", "old"), ids(out))
  }

  @Test fun sort_priority_asc_nulls_last() {
    val tasks = listOf(
      fakeTask(id = "n", priority = null),
      fakeTask(id = "p3", priority = 3),
      fakeTask(id = "p1", priority = 1),
    )
    val out = applyTaskFilter(tasks, projects, TaskFilter(sortField = SortField.Priority, sortDirection = SortDirection.Asc))
    assertEquals(listOf("p1", "p3", "n"), ids(out))
  }

  @Test fun sort_plannedDate_asc_missing_sorts_as_empty_first() {
    val tasks = listOf(
      fakeTask(id = "d2", plannedDate = LocalDate.of(2026, 6, 20)),
      fakeTask(id = "none", plannedDate = null),
      fakeTask(id = "d1", plannedDate = LocalDate.of(2026, 6, 10)),
    )
    val out = applyTaskFilter(tasks, projects, TaskFilter(sortField = SortField.PlannedDate, sortDirection = SortDirection.Asc))
    assertEquals(listOf("none", "d1", "d2"), ids(out))
  }

  @Test fun sort_title_asc() {
    val tasks = listOf(fakeTask(id = "b", title = "Banana"), fakeTask(id = "a", title = "Apple"))
    val out = applyTaskFilter(tasks, projects, TaskFilter(sortField = SortField.Title, sortDirection = SortDirection.Asc))
    assertEquals(listOf("a", "b"), ids(out))
  }

  @Test fun sort_status_uses_lifecycle_order() {
    val tasks = listOf(
      fakeTask(id = "done", status = TaskStatus.Done),
      fakeTask(id = "inbox", status = TaskStatus.Inbox),
      fakeTask(id = "planned", status = TaskStatus.Planned),
    )
    val out = applyTaskFilter(tasks, projects, TaskFilter(sortField = SortField.Status, sortDirection = SortDirection.Asc))
    assertEquals(listOf("inbox", "planned", "done"), ids(out))
  }

  // ---- grouping ----
  @Test fun group_by_status_first_seen_order() {
    val tasks = listOf(
      fakeTask(id = "p", status = TaskStatus.Planned, createdAt = Instant.parse("2026-06-03T00:00:00Z")),
      fakeTask(id = "i", status = TaskStatus.Inbox, createdAt = Instant.parse("2026-06-02T00:00:00Z")),
      fakeTask(id = "p2", status = TaskStatus.Planned, createdAt = Instant.parse("2026-06-01T00:00:00Z")),
    )
    // CreatedAt desc → p, i, p2 → first-seen group order: Planned, Inbox
    val out = applyTaskFilter(tasks, projects, TaskFilter(groupBy = GroupBy.Status))
    assertEquals(listOf("Planned", "Inbox"), out.map { it.label })
    assertEquals(listOf("p", "p2"), out.first().items.map { it.task.id })
  }

  @Test fun group_by_project_uses_name_and_no_project_label() {
    val tasks = listOf(fakeTask(id = "a", projectId = "p1"), fakeTask(id = "b", projectId = null))
    val out = applyTaskFilter(tasks, projects, TaskFilter(groupBy = GroupBy.Project, sortField = SortField.Title, sortDirection = SortDirection.Asc))
    val labels = out.map { it.label }.toSet()
    assertEquals(setOf("Work", "No Project"), labels)
  }

  @Test fun group_by_priority_uses_priority_labels() {
    val tasks = listOf(fakeTask(id = "u", priority = 1), fakeTask(id = "n", priority = null))
    val out = applyTaskFilter(tasks, projects, TaskFilter(groupBy = GroupBy.Priority, sortField = SortField.Priority, sortDirection = SortDirection.Asc))
    assertEquals(listOf("Urgent", "No Priority"), out.map { it.label })
  }

  // ---- combination ----
  @Test fun combination_status_priority_search_sort_group() {
    val tasks = listOf(
      fakeTask(id = "keep", title = "Quarterly plan", status = TaskStatus.Planned, priority = 1, projectId = "p1"),
      fakeTask(id = "wrongStatus", title = "Quarterly notes", status = TaskStatus.Done, priority = 1),
      fakeTask(id = "wrongPrio", title = "Quarterly chat", status = TaskStatus.Planned, priority = 4),
      fakeTask(id = "noMatch", title = "Lunch", status = TaskStatus.Planned, priority = 1),
    )
    val out = applyTaskFilter(
      tasks, projects,
      TaskFilter(
        search = "quarterly",
        statuses = setOf(TaskStatus.Planned),
        priorities = setOf(1),
        sortField = SortField.Title,
        sortDirection = SortDirection.Asc,
        groupBy = GroupBy.Project,
      ),
    )
    assertEquals(listOf("keep"), ids(out))
    assertEquals("Work", out.single().label)
  }
}
