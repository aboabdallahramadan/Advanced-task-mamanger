package net.qmindtech.tmap.ui.inbox

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
class InboxViewModelTest {
  private val testDispatcher = UnconfinedTestDispatcher()

  @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
  @After fun tearDown() { Dispatchers.resetMain() }

  @Test fun quickAdd_creates_inbox_task_with_trimmed_title() = runTest {
    val repo = FakeTaskRepo()
    val vm = InboxViewModel(repo, FakeProjectRepo())
    vm.quickAdd("  Buy milk  ")
    assertEquals(1, repo.created.size)
    assertEquals("Buy milk", repo.created.first().title)
    assertEquals(TaskStatus.Inbox, repo.created.first().status)
  }

  @Test fun quickAdd_ignores_blank() = runTest {
    val repo = FakeTaskRepo()
    val vm = InboxViewModel(repo, FakeProjectRepo())
    vm.quickAdd("   ")
    assertTrue(repo.created.isEmpty())
  }

  @Test fun uiState_resolves_project_names_for_inbox_rows() = runTest {
    val repo = FakeTaskRepo()
    repo.setByStatus(listOf(fakeTask(id = "i1", status = TaskStatus.Inbox, projectId = "p1")))
    val projRepo = FakeProjectRepo().apply { setAll(listOf(fakeProject(id = "p1", name = "Errands"))) }
    val vm = InboxViewModel(repo, projRepo)
    vm.uiState.test {
      val s = expectMostRecentItem()
      assertEquals(false, s.loading)
      assertEquals("Errands", s.items.single().projectName)
      cancelAndIgnoreRemainingEvents()
    }
  }
}
