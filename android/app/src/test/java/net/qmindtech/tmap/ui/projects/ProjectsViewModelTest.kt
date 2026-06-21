package net.qmindtech.tmap.ui.projects

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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectsViewModelTest {
  private val testDispatcher = UnconfinedTestDispatcher()

  @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
  @After fun tearDown() { Dispatchers.resetMain() }

  private fun vmWith(): Triple<ProjectsViewModel, FakeProjectRepo, FakeTaskRepo> {
    val projRepo = FakeProjectRepo().apply {
      setAll(listOf(fakeProject(id = "p1", name = "Work"), fakeProject(id = "p2", name = "Home")))
    }
    val taskRepo = FakeTaskRepo().apply {
      setAll(
        listOf(
          fakeTask(id = "a", projectId = "p1", status = TaskStatus.Planned),
          fakeTask(id = "b", projectId = "p1", status = TaskStatus.Done),
          fakeTask(id = "c", projectId = "p2", status = TaskStatus.Inbox),
        )
      )
    }
    return Triple(ProjectsViewModel(projRepo, taskRepo), projRepo, taskRepo)
  }

  @Test fun uiState_lists_projects_with_open_task_counts() = runTest {
    val (vm, _, _) = vmWith()
    vm.uiState.test {
      val s = expectMostRecentItem()
      assertEquals(false, s.loading)
      val byId = s.rows.associate { it.project.id to it.openTaskCount }
      assertEquals(1, byId["p1"]) // 1 open (Done excluded)
      assertEquals(1, byId["p2"])
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test fun create_delegates_and_blank_is_noop() = runTest {
    val (vm, projRepo, _) = vmWith()
    vm.create("  ", "#fff", "📁")
    assertTrue(projRepo.created.isEmpty())
    vm.create("New Project", "#6366f1", "🚀")
    assertEquals(1, projRepo.created.size)
    assertEquals("New Project", projRepo.created.first().first)
  }

  @Test fun update_and_delete_delegate() = runTest {
    val (vm, projRepo, _) = vmWith()
    vm.update("p1", "Work!", "#000", "💼")
    vm.delete("p2")
    assertEquals(listOf("p1"), projRepo.updated)
    assertEquals(listOf("p2"), projRepo.deleted)
  }

  @Test fun moveProject_reorders_and_emits_ordered_ids() = runTest {
    val (vm, projRepo, _) = vmWith()
    // ensure rows are populated
    vm.uiState.test { expectMostRecentItem(); cancelAndIgnoreRemainingEvents() }
    vm.moveProject(0, 1) // move p1 after p2
    assertEquals(listOf(listOf("p2", "p1")), projRepo.reordered)
  }
}
