package net.qmindtech.tmap.ui.backlog

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
class BacklogViewModelTest {
  private val testDispatcher = UnconfinedTestDispatcher()

  @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
  @After fun tearDown() { Dispatchers.resetMain() }

  @Test fun uiState_exposes_backlog_rows_with_project_names() = runTest {
    val repo = FakeTaskRepo()
    repo.setByStatus(listOf(fakeTask(id = "b1", status = TaskStatus.Backlog, projectId = "p1")))
    val projRepo = FakeProjectRepo().apply { setAll(listOf(fakeProject(id = "p1", name = "Someday"))) }
    val vm = BacklogViewModel(repo, projRepo)
    vm.uiState.test {
      val s = expectMostRecentItem()
      assertEquals(false, s.loading)
      assertEquals(listOf("b1"), s.items.map { it.task.id })
      assertEquals("Someday", s.items.single().projectName)
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test fun toggleDone_delegates_markDone() = runTest {
    val repo = FakeTaskRepo()
    val vm = BacklogViewModel(repo, FakeProjectRepo())
    vm.toggleDone(fakeTask(id = "b9", status = TaskStatus.Backlog))
    assertEquals(listOf("b9"), repo.markedDone)
  }
}
