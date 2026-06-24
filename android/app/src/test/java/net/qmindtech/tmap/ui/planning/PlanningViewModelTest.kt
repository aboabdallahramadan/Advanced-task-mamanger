package net.qmindtech.tmap.ui.planning

import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.local.entities.TaskEntity
import net.qmindtech.tmap.testutil.FakeDailyPlanRepo
import net.qmindtech.tmap.testutil.FakeProjectRepo
import net.qmindtech.tmap.testutil.FakeSettingsRepo
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
class PlanningViewModelTest {
  private val today = LocalDate.of(2026, 6, 21)
  private val yesterday = today.minusDays(1)
  private val testDispatcher = UnconfinedTestDispatcher()

  @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
  @After fun tearDown() { Dispatchers.resetMain() }

  private fun vm(
    yesterdayTasks: List<TaskEntity> = emptyList(),
    inbox: List<TaskEntity> = emptyList(),
    settings: List<net.qmindtech.tmap.data.local.entities.SettingEntity> = emptyList(),
  ): Triple<PlanningViewModel, FakeTaskRepo, FakeDailyPlanRepo> {
    val task = FakeTaskRepo(today = MutableStateFlow(yesterdayTasks), byStatus = MutableStateFlow(inbox))
    val projects = FakeProjectRepo().apply { setAll(listOf(fakeProject(id = "p1", name = "Work", color = "#6ea8fe"))) }
    val daily = FakeDailyPlanRepo()
    val set = FakeSettingsRepo().apply { set(settings) }
    val vm = PlanningViewModel(task, projects, daily, set, FixedClock(Instant.parse("2026-06-21T06:00:00Z")))
    return Triple(vm, task, daily)
  }

  @Test fun reflect_splits_yesterday_done_and_undone() = runTest {
    val (vm, _, _) = vm(
      yesterdayTasks = listOf(
        fakeTask(id = "d", title = "Done", plannedDate = yesterday, status = TaskStatus.Done),
        fakeTask(id = "u", title = "Undone", plannedDate = yesterday, status = TaskStatus.Planned, projectId = "p1"),
      ),
    )
    vm.uiState.test {
      val s = expectMostRecentItem()
      assertEquals(false, s.loading)
      assertEquals(listOf("d"), s.yesterdayDone.map { it.id })
      assertEquals(listOf("u"), s.yesterdayUndone.map { it.id })
      assertEquals("Work", s.yesterdayUndone.single().projectName)
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test fun pickToday_lists_carryover_and_inbox_and_toggleAdd_updates_capacity() = runTest {
    val (vm, _, _) = vm(
      yesterdayTasks = listOf(fakeTask(id = "u", plannedDate = yesterday, status = TaskStatus.Planned, durationMinutes = 60)),
      inbox = listOf(fakeTask(id = "i", status = TaskStatus.Inbox, durationMinutes = 90)),
      settings = listOf(net.qmindtech.tmap.data.local.entities.SettingEntity(KEY_WORKDAY_MINUTES, "360", 0)),
    )
    vm.uiState.test {
      var s = expectMostRecentItem()
      assertEquals(listOf("u"), s.carryOver.map { it.id })
      assertEquals(listOf("i"), s.inboxPicks.map { it.id })
      assertEquals(0, s.plannedMinutes)
      assertEquals(360, s.workdayMinutes)

      vm.toggleAdd("u")   // +60
      vm.toggleAdd("i")   // +90
      s = expectMostRecentItem()
      assertEquals(listOf("u", "i"), s.pickedIds)
      assertEquals(150, s.plannedMinutes)
      assertEquals(true, s.carryOver.single { it.id == "u" }.added)

      vm.toggleAdd("u")   // remove
      s = expectMostRecentItem()
      assertEquals(listOf("i"), s.pickedIds)
      assertEquals(90, s.plannedMinutes)
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test fun next_and_back_move_the_step_clamped() = runTest {
    val (vm, _, _) = vm()
    vm.uiState.test {
      assertEquals(PlanningStep.Reflect, expectMostRecentItem().step)
      vm.next(); assertEquals(PlanningStep.TriageInbox, expectMostRecentItem().step)
      vm.next(); vm.next(); vm.next() // PickToday, Timebox, clamp
      assertEquals(PlanningStep.Timebox, expectMostRecentItem().step)
      vm.back(); assertEquals(PlanningStep.PickToday, expectMostRecentItem().step)
      cancelAndIgnoreRemainingEvents()
    }
  }
}
