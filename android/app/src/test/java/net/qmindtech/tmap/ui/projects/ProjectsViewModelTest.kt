package net.qmindtech.tmap.ui.projects

import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.qmindtech.tmap.data.local.dao.ProjectProgress
import net.qmindtech.tmap.testutil.FakeProjectRepo
import net.qmindtech.tmap.testutil.fakeProject
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

  private fun vmWith(): Pair<ProjectsViewModel, FakeProjectRepo> {
    val projRepo = FakeProjectRepo().apply {
      setAll(listOf(fakeProject(id = "p1", name = "Work"), fakeProject(id = "p2", name = "Home")))
      setProgress(listOf(ProjectProgress("p1", total = 4, done = 1), ProjectProgress("p2", total = 2, done = 2)))
    }
    return ProjectsViewModel(projRepo) to projRepo
  }

  @Test fun uiState_maps_progress_rows_and_header() = runTest {
    val (vm, _) = vmWith()
    vm.uiState.test {
      val s = expectMostRecentItem()
      assertEquals(false, s.loading)
      val byId = s.rows.associateBy { it.project.id }
      assertEquals(3, byId.getValue("p1").openTaskCount) // 4 - 1
      assertEquals(0.25f, byId.getValue("p1").progress)
      assertEquals(0, byId.getValue("p2").openTaskCount)
      assertEquals(1.0f, byId.getValue("p2").progress)
      assertEquals(2, s.header.projectCount)
      assertEquals(3, s.header.doneTotal) // 1 + 2
      assertEquals(6, s.header.taskTotal) // 4 + 2
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test fun project_with_no_progress_row_is_zero() = runTest {
    val projRepo = FakeProjectRepo().apply {
      setAll(listOf(fakeProject(id = "p1", name = "Work")))
      setProgress(emptyList())
    }
    val vm = ProjectsViewModel(projRepo)
    vm.uiState.test {
      val s = expectMostRecentItem()
      assertEquals(0, s.rows.single().total)
      assertEquals(0f, s.rows.single().progress)
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test fun create_delegates_and_blank_is_noop() = runTest {
    val (vm, projRepo) = vmWith()
    vm.create("  ", "#fff", "📁")
    assertTrue(projRepo.created.isEmpty())
    vm.create("New Project", "#6EA8FE", "🚀")
    assertEquals(1, projRepo.created.size)
    assertEquals("New Project", projRepo.created.first().first)
  }

  @Test fun update_and_delete_delegate() = runTest {
    val (vm, projRepo) = vmWith()
    vm.update("p1", "Work!", "#000", "💼")
    vm.delete("p2")
    assertEquals(listOf("p1"), projRepo.updated)
    assertEquals(listOf("p2"), projRepo.deleted)
  }

  @Test fun moveProject_reorders_and_emits_ordered_ids() = runTest {
    val (vm, projRepo) = vmWith()
    vm.uiState.test { expectMostRecentItem(); cancelAndIgnoreRemainingEvents() }
    vm.moveProject(0, 1)
    assertEquals(listOf(listOf("p2", "p1")), projRepo.reordered)
  }
}
