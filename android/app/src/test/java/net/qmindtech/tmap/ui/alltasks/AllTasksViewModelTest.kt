package net.qmindtech.tmap.ui.alltasks

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
class AllTasksViewModelTest {
  private val testDispatcher = UnconfinedTestDispatcher()

  @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
  @After fun tearDown() { Dispatchers.resetMain() }

  private fun vmWith(): Pair<AllTasksViewModel, FakeTaskRepo> {
    val repo = FakeTaskRepo()
    repo.setAll(
      listOf(
        fakeTask(id = "i", title = "Inbox item", status = TaskStatus.Inbox),
        fakeTask(id = "p", title = "Planned item", status = TaskStatus.Planned),
        fakeTask(id = "ar", title = "Archived item", status = TaskStatus.Archived),
      )
    )
    val proj = FakeProjectRepo().apply { setAll(listOf(fakeProject(id = "p1", name = "Work"))) }
    return AllTasksViewModel(repo, proj) to repo
  }

  @Test fun default_excludes_archived_and_reports_total() = runTest {
    val (vm, _) = vmWith()
    vm.uiState.test {
      val s = expectMostRecentItem()
      assertEquals(false, s.loading)
      assertEquals(2, s.totalCount)
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test fun setStatuses_filters_and_redrives_groups() = runTest {
    val (vm, _) = vmWith()
    vm.setStatuses(setOf(TaskStatus.Planned))
    vm.uiState.test {
      val s = expectMostRecentItem()
      assertEquals(listOf("p"), s.groups.flatMap { it.items }.map { it.task.id })
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test fun setSort_same_field_toggles_direction() = runTest {
    val (vm, _) = vmWith()
    vm.setGroupBy(GroupBy.None)
    vm.setSort(SortField.Title)
    assertEquals(SortDirection.Desc, vm.uiState.value.filter.sortDirection)
    vm.setSort(SortField.Title)
    assertEquals(SortDirection.Asc, vm.uiState.value.filter.sortDirection)
    vm.setSort(SortField.Status)
    assertEquals(SortField.Status, vm.uiState.value.filter.sortField)
    assertEquals(SortDirection.Desc, vm.uiState.value.filter.sortDirection)
  }

  @Test fun clearFilters_resets_to_defaults() = runTest {
    val (vm, _) = vmWith()
    vm.setSearch("foo")
    vm.setShowArchived(true)
    vm.clearFilters()
    assertEquals("", vm.uiState.value.filter.search)
    assertEquals(false, vm.uiState.value.filter.showArchived)
  }

  @Test fun toggleDone_delegates() = runTest {
    val (vm, repo) = vmWith()
    vm.toggleDone(fakeTask(id = "i", status = TaskStatus.Inbox))
    assertEquals(listOf("i"), repo.markedDone)
  }
}
