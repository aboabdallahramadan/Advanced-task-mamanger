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

  @Test fun scheduleFromInbox_updates_task_and_adds_to_pick() = runTest {
    val (vm, task, _) = vm(
      inbox = listOf(fakeTask(id = "i", status = TaskStatus.Inbox)),
    )
    vm.uiState.test {
      expectMostRecentItem()
      vm.scheduleFromInbox("i")
      val (id, edit) = task.updated.single()
      assertEquals("i", id)
      assertEquals(TaskStatus.Planned, edit.status)
      assertEquals(today, edit.plannedDate)
      assertEquals(listOf("i"), expectMostRecentItem().pickedIds)
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test fun sendToBacklog_assignProject_delete_delegate() = runTest {
    val (vm, task, _) = vm(inbox = listOf(fakeTask(id = "i", status = TaskStatus.Inbox)))
    vm.sendToBacklog("i")
    assertEquals(TaskStatus.Backlog, task.updated.last().second.status)
    vm.assignProject("i", "p1")
    assertEquals("p1", task.updated.last().second.projectId)
    vm.deleteTask("i")
    assertEquals(listOf("i"), task.deleted)
  }

  @Test fun assignTime_sets_scheduled_window() = runTest {
    val (vm, task, _) = vm()
    val start = Instant.parse("2026-06-21T09:00:00Z")
    val end = Instant.parse("2026-06-21T10:00:00Z")
    vm.assignTime("t", start, end)
    val edit = task.updated.single().second
    assertEquals(start, edit.scheduledStart)
    assertEquals(end, edit.scheduledEnd)
    assertEquals(TaskStatus.Scheduled, edit.status)
  }

  @Test fun commit_sets_plannedDate_on_picks_and_upserts_dailyplan() = runTest {
    val (vm, task, daily) = vm(
      yesterdayTasks = listOf(fakeTask(id = "u", plannedDate = yesterday, status = TaskStatus.Planned, durationMinutes = 60)),
      inbox = listOf(fakeTask(id = "i", status = TaskStatus.Inbox, durationMinutes = 90)),
    )
    vm.uiState.test {
      expectMostRecentItem()
      vm.toggleAdd("u"); vm.toggleAdd("i")
      expectMostRecentItem()
      var done = false
      vm.commit { done = true }
      // every picked id got plannedDate=today + status=Planned
      val edits = task.updated.associate { it.first to it.second }
      assertEquals(today, edits["u"]!!.plannedDate)
      assertEquals(TaskStatus.Planned, edits["u"]!!.status)
      assertEquals(today, edits["i"]!!.plannedDate)
      // DailyPlan upserted with the ordered picks + planned minutes (committedAt stamped by the repo)
      val up = daily.upserts.single()
      assertEquals(today, up.date)
      assertEquals(listOf("u", "i"), up.plannedTaskIds)
      assertEquals(150, up.plannedMinutes)
      assertEquals(true, expectMostRecentItem().committed)
      assertEquals(true, done)
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test fun commit_with_no_picks_upserts_empty_plan() = runTest {
    val (vm, task, daily) = vm()
    var done = false
    vm.commit { done = true }
    assertEquals(emptyList<String>(), daily.upserts.single().plannedTaskIds)
    assertEquals(0, daily.upserts.single().plannedMinutes)
    assertEquals(true, task.updated.isEmpty())  // nothing to re-plan
    assertEquals(true, done)
  }
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
