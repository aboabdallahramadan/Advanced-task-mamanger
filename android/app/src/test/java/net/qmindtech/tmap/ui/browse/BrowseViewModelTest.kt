package net.qmindtech.tmap.ui.browse

import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.testutil.FakeProjectRepo
import net.qmindtech.tmap.testutil.FakeTaskRepo
import net.qmindtech.tmap.testutil.fakeProject
import net.qmindtech.tmap.testutil.fakeTask
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BrowseViewModelTest {
  private val testDispatcher = UnconfinedTestDispatcher()

  @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
  @After fun tearDown() { Dispatchers.resetMain() }

  private fun vmWith(): Pair<BrowseViewModel, FakeTaskRepo> {
    val repo = FakeTaskRepo()
    repo.setAll(
      listOf(
        fakeTask(id = "i", title = "Inbox item", status = TaskStatus.Inbox, rank = "0000"),
        fakeTask(id = "b", title = "Backlog item", status = TaskStatus.Backlog, rank = "0001"),
        fakeTask(id = "ar", title = "Archived item", status = TaskStatus.Archived, rank = "0002"),
      )
    )
    repo.setByStatus(listOf(fakeTask(id = "b", title = "Backlog item", status = TaskStatus.Backlog, rank = "0001")))
    val proj = FakeProjectRepo().apply { setAll(listOf(fakeProject(id = "p1", name = "Work"))) }
    return BrowseViewModel(repo, proj) to repo
  }

  @Test fun default_allTasks_excludes_archived_and_reports_total() = runTest {
    val (vm, _) = vmWith()
    vm.uiState.test {
      val s = expectMostRecentItem()
      assertEquals(false, s.loading)
      assertEquals(BrowseSegment.AllTasks, s.segment)
      assertEquals(2, s.totalCount) // archived excluded
      assertEquals(0, s.activeFilterCount)
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test fun backlog_segment_shows_only_backlog() = runTest {
    val (vm, _) = vmWith()
    vm.setSegment(BrowseSegment.Backlog)
    vm.uiState.test {
      val s = expectMostRecentItem()
      assertEquals(listOf("b"), s.groups.flatMap { it.items }.map { it.task.id })
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test fun projects_segment_surfaces_project_list() = runTest {
    val (vm, _) = vmWith()
    vm.setSegment(BrowseSegment.Projects)
    vm.uiState.test {
      val s = expectMostRecentItem()
      assertEquals(listOf("p1"), s.projects.map { it.id })
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test fun setSearch_filters_and_increments_activeFilterCount() = runTest {
    val (vm, _) = vmWith()
    vm.setSearch("backlog")
    vm.uiState.test {
      val s = expectMostRecentItem()
      assertEquals(listOf("b"), s.groups.flatMap { it.items }.map { it.task.id })
      assertEquals(1, s.activeFilterCount)
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test fun setSort_same_field_toggles_direction() = runTest {
    val (vm, _) = vmWith()
    vm.setSort(SortField.Title)
    assertEquals(SortDirection.Desc, vm.uiState.value.filter.sortField.let { vm.uiState.value.filter.sortDirection })
    vm.setSort(SortField.Title)
    assertEquals(SortDirection.Asc, vm.uiState.value.filter.sortDirection)
  }

  @Test fun clearFilters_preserves_sort_group_and_segment() = runTest {
    val (vm, _) = vmWith()
    vm.setSegment(BrowseSegment.Backlog)
    vm.setGroupBy(GroupBy.Status)
    vm.setSort(SortField.Title)
    vm.setSearch("foo")
    vm.setShowArchived(true)
    vm.clearFilters()
    assertEquals("", vm.uiState.value.filter.search)
    assertEquals(false, vm.uiState.value.filter.showArchived)
    assertEquals(GroupBy.Status, vm.uiState.value.filter.groupBy)
    assertEquals(SortField.Title, vm.uiState.value.filter.sortField)
    assertEquals(BrowseSegment.Backlog, vm.uiState.value.segment)
  }

  @Test fun toggleDone_delegates() = runTest {
    val (vm, repo) = vmWith()
    vm.toggleDone(fakeTask(id = "i", status = TaskStatus.Inbox))
    assertEquals(listOf("i"), repo.markedDone)
  }

  @Test fun activeFilterCount_counts_each_diverging_facet() {
    assertEquals(0, activeFilterCount(TaskFilter()))
    assertEquals(1, activeFilterCount(TaskFilter(search = "x")))
    assertEquals(1, activeFilterCount(TaskFilter(showArchived = true)))
    assertEquals(1, activeFilterCount(TaskFilter(projectIds = setOf("p1"))))
    assertEquals(2, activeFilterCount(TaskFilter(search = "x", priorities = setOf(1))))
  }
}
