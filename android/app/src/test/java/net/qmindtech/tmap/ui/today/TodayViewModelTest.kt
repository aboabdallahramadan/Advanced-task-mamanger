package net.qmindtech.tmap.ui.today

import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.testutil.FakeProjectRepo
import net.qmindtech.tmap.testutil.FakeTaskRepo
import net.qmindtech.tmap.testutil.FixedClock
import net.qmindtech.tmap.testutil.fakeProject
import net.qmindtech.tmap.testutil.fakeTask
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModelTest {
  private val today = LocalDate.of(2026, 6, 18)
  private val testDispatcher = UnconfinedTestDispatcher()

  @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
  @After fun tearDown() { Dispatchers.resetMain() }

  @Test fun timeOrder_puts_scheduled_first_then_planned_then_created_and_resolves_project() {
    val proj = fakeProject(id = "p1", name = "Work")
    val a = fakeTask(id = "a", title = "no-time", plannedDate = today, scheduledStart = null,
      createdAt = Instant.parse("2026-06-18T01:00:00Z"))
    val b = fakeTask(id = "b", title = "9am", scheduledStart = Instant.parse("2026-06-18T09:00:00Z"),
      plannedDate = today, projectId = "p1")
    val c = fakeTask(id = "c", title = "8am", scheduledStart = Instant.parse("2026-06-18T08:00:00Z"),
      plannedDate = today)
    val d = fakeTask(id = "d", title = "no-time-2", scheduledStart = null, plannedDate = today,
      createdAt = Instant.parse("2026-06-18T00:30:00Z"))

    val out = timeOrderToday(listOf(a, b, c, d), listOf(proj))

    assertEquals(listOf("c", "b", "d", "a"), out.map { it.task.id })
    assertEquals("Work", out.first { it.task.id == "b" }.projectName)
    assertEquals(null, out.first { it.task.id == "a" }.projectName)
  }

  @Test fun uiState_emits_ordered_items_and_clears_loading() = runTest(testDispatcher) {
    val tasksFlow = MutableStateFlow(
      listOf(
        fakeTask(id = "late", scheduledStart = Instant.parse("2026-06-18T15:00:00Z"), plannedDate = today),
        fakeTask(id = "early", scheduledStart = Instant.parse("2026-06-18T07:00:00Z"), plannedDate = today),
      )
    )
    val repo = FakeTaskRepo(today = tasksFlow)
    val vm = TodayViewModel(repo, FakeProjectRepo(), FixedClock(Instant.parse("2026-06-18T06:00:00Z")))
    vm.uiState.test {
      // initial loading frame may be coalesced; assert the settled state
      val s = expectMostRecentItem()
      assertEquals(false, s.loading)
      assertEquals(listOf("early", "late"), s.items.map { it.task.id })
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test fun toggleDone_delegates_to_repository_markDone() = runTest(testDispatcher) {
    val repo = FakeTaskRepo(today = MutableStateFlow(emptyList()))
    val vm = TodayViewModel(repo, FakeProjectRepo(), FixedClock(Instant.parse("2026-06-18T06:00:00Z")))
    val t = fakeTask(id = "x", status = TaskStatus.Planned)
    vm.toggleDone(t)
    assertEquals(listOf("x"), repo.markedDone)
  }
}
