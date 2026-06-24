package net.qmindtech.tmap.ui.projects

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.local.dao.ProjectProgress
import net.qmindtech.tmap.testutil.FakeProjectRepo
import net.qmindtech.tmap.testutil.FakeTaskRepo
import net.qmindtech.tmap.testutil.fakeProject
import net.qmindtech.tmap.testutil.fakeTask
import net.qmindtech.tmap.ui.navigation.Route
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectDetailViewModelTest {
  private val testDispatcher = UnconfinedTestDispatcher()

  @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
  @After fun tearDown() { Dispatchers.resetMain() }

  private fun vmWith(): Pair<ProjectDetailViewModel, FakeTaskRepo> {
    val taskRepo = FakeTaskRepo().apply {
      setAll(
        listOf(
          fakeTask(id = "a", projectId = "p1", status = TaskStatus.Planned, rank = "0001"),
          fakeTask(id = "b", projectId = "p1", status = TaskStatus.Planned, rank = "0000"),
          fakeTask(id = "c", projectId = "p2", status = TaskStatus.Planned),
        )
      )
    }
    val projRepo = FakeProjectRepo().apply {
      setAll(listOf(fakeProject(id = "p1", name = "Work"), fakeProject(id = "p2", name = "Home")))
      setProgress(listOf(ProjectProgress("p1", total = 2, done = 0)))
    }
    val handle = SavedStateHandle(mapOf(Route.ProjectDetail.ARG_PROJECT_ID to "p1"))
    return ProjectDetailViewModel(handle, taskRepo, projRepo) to taskRepo
  }

  @Test fun loads_project_header_and_only_its_tasks_by_rank() = runTest {
    val (vm, _) = vmWith()
    vm.uiState.test {
      val s = expectMostRecentItem()
      assertEquals("Work", s.project?.name)
      assertEquals(2, s.total)
      assertEquals(0, s.done)
      assertEquals(0f, s.progress)
      assertEquals(listOf("b", "a"), s.items.map { it.task.id }) // manual rank asc
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test fun toggleDone_delegates() = runTest {
    val (vm, repo) = vmWith()
    vm.toggleDone(fakeTask(id = "a", projectId = "p1"))
    assertEquals(listOf("a"), repo.markedDone)
  }

  @Test fun update_delegates_to_project_repo() = runTest {
    val taskRepo = FakeTaskRepo()
    val projRepo = FakeProjectRepo().apply {
      setAll(listOf(fakeProject(id = "p1", name = "Work")))
      setProgress(emptyList())
    }
    val handle = SavedStateHandle(mapOf(Route.ProjectDetail.ARG_PROJECT_ID to "p1"))
    val vm = ProjectDetailViewModel(handle, taskRepo, projRepo)
    vm.update("Work Updated", "#FF0000", "🚀")
    assertEquals(listOf("p1"), projRepo.updated)
  }

  @Test fun update_blank_name_is_noop() = runTest {
    val taskRepo = FakeTaskRepo()
    val projRepo = FakeProjectRepo().apply {
      setAll(listOf(fakeProject(id = "p1", name = "Work")))
      setProgress(emptyList())
    }
    val handle = SavedStateHandle(mapOf(Route.ProjectDetail.ARG_PROJECT_ID to "p1"))
    val vm = ProjectDetailViewModel(handle, taskRepo, projRepo)
    vm.update("  ", "#FF0000", "🚀")
    assertEquals(emptyList<String>(), projRepo.updated)
  }

  @Test fun delete_delegates_to_project_repo() = runTest {
    val taskRepo = FakeTaskRepo()
    val projRepo = FakeProjectRepo().apply {
      setAll(listOf(fakeProject(id = "p1", name = "Work")))
      setProgress(emptyList())
    }
    val handle = SavedStateHandle(mapOf(Route.ProjectDetail.ARG_PROJECT_ID to "p1"))
    val vm = ProjectDetailViewModel(handle, taskRepo, projRepo)
    vm.delete()
    assertEquals(listOf("p1"), projRepo.deleted)
  }
}
