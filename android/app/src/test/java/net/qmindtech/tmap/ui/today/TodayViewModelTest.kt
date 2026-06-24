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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModelTest {
  private val today = LocalDate.of(2026, 6, 21)
  private val testDispatcher = UnconfinedTestDispatcher()

  @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
  @After fun tearDown() { Dispatchers.resetMain() }

  private fun vm(tasks: MutableStateFlow<List<net.qmindtech.tmap.data.local.entities.TaskEntity>>,
                 projects: FakeProjectRepo = FakeProjectRepo()): Pair<TodayViewModel, FakeTaskRepo> {
    val repo = FakeTaskRepo(today = tasks)
    return TodayViewModel(repo, projects, FixedClock(Instant.parse("2026-06-21T06:00:00Z"))) to repo
  }

  @Test fun groups_morning_afternoon_evening_other_and_resolves_project() = runTest(testDispatcher) {
    val projects = FakeProjectRepo().also { it.setAll(listOf(fakeProject(id = "p1", name = "Work"))) }
    val flow = MutableStateFlow(
      listOf(
        fakeTask(id = "morn", scheduledStart = Instant.parse("2026-06-21T09:00:00Z"), plannedDate = today, projectId = "p1"),
        fakeTask(id = "aft", scheduledStart = Instant.parse("2026-06-21T13:00:00Z"), plannedDate = today),
        fakeTask(id = "eve", scheduledStart = Instant.parse("2026-06-21T19:00:00Z"), plannedDate = today),
        fakeTask(id = "other", scheduledStart = null, plannedDate = today),
      )
    )
    val (vm, _) = vm(flow, projects)
    vm.uiState.test {
      val s = expectMostRecentItem()
      assertEquals(false, s.loading)
      assertEquals(
        listOf(TodaySection.Morning, TodaySection.Afternoon, TodaySection.Evening, TodaySection.Other),
        s.groups.map { it.section },
      )
      assertEquals("Work", s.groups[0].tasks.first().projectName)
      assertEquals("Good morning", s.greeting)
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test fun progress_reflects_done_total() = runTest(testDispatcher) {
    val flow = MutableStateFlow(
      listOf(
        fakeTask(id = "1", status = TaskStatus.Done, plannedDate = today),
        fakeTask(id = "2", status = TaskStatus.Planned, plannedDate = today, durationMinutes = 60),
      )
    )
    val (vm, _) = vm(flow)
    vm.uiState.test {
      val s = expectMostRecentItem()
      assertEquals(1, s.progress.done)
      assertEquals(2, s.progress.total)
      assertEquals(60, s.progress.minutesLeft)
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test fun toggleComplete_marks_done_when_open() = runTest(testDispatcher) {
    val flow = MutableStateFlow(listOf(fakeTask(id = "x", status = TaskStatus.Planned, plannedDate = today)))
    val (vm, repo) = vm(flow)
    vm.toggleComplete("x")
    assertEquals(listOf("x"), repo.markedDone)
  }

  @Test fun toggleComplete_reopens_when_done() = runTest(testDispatcher) {
    val flow = MutableStateFlow(listOf(fakeTask(id = "x", status = TaskStatus.Done, plannedDate = today)))
    val (vm, repo) = vm(flow)
    vm.toggleComplete("x")
    assertTrue(repo.markedDone.isEmpty())
    assertEquals(1, repo.updated.size)
    assertEquals(TaskStatus.Planned, repo.updated.first().second.status)
  }

  @Test fun defer_moves_task_to_tomorrow() = runTest(testDispatcher) {
    val flow = MutableStateFlow(listOf(fakeTask(id = "x", plannedDate = today)))
    val (vm, repo) = vm(flow)
    vm.defer("x")
    assertEquals(listOf("x" to today.plusDays(1)), repo.deferred)
  }

  @Test fun delete_and_reorder_and_moveToDay_delegate() = runTest(testDispatcher) {
    val flow = MutableStateFlow(listOf(fakeTask(id = "x", plannedDate = today)))
    val (vm, repo) = vm(flow)
    vm.delete("x")
    vm.reorder(listOf("x"))
    vm.moveToDay("x", today.plusDays(3))
    assertEquals(listOf("x"), repo.deleted)
    assertEquals(listOf(listOf("x")), repo.reordered)
    assertEquals(listOf("x" to today.plusDays(3)), repo.movedToDay)
  }

  @Test fun setMode_switches_to_timeline() = runTest(testDispatcher) {
    val (vm, _) = vm(MutableStateFlow(emptyList()))
    vm.setMode(TodayMode.Timeline)
    vm.uiState.test {
      assertEquals(TodayMode.Timeline, expectMostRecentItem().mode)
      cancelAndIgnoreRemainingEvents()
    }
  }

  // Carry-forward: section-boundary tests
  @Test fun section_boundary_11_59_is_morning() = runTest(testDispatcher) {
    // 11:59 UTC = 11:59 UTC zone (FixedClock uses UTC for zone via ZoneOffset.UTC)
    val flow = MutableStateFlow(
      listOf(fakeTask(id = "t", scheduledStart = Instant.parse("2026-06-21T11:59:00Z"), plannedDate = today))
    )
    val (vm, _) = vm(flow)
    vm.uiState.test {
      val s = expectMostRecentItem()
      assertEquals(listOf(TodaySection.Morning), s.groups.map { it.section })
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test fun section_boundary_12_00_is_afternoon() = runTest(testDispatcher) {
    val flow = MutableStateFlow(
      listOf(fakeTask(id = "t", scheduledStart = Instant.parse("2026-06-21T12:00:00Z"), plannedDate = today))
    )
    val (vm, _) = vm(flow)
    vm.uiState.test {
      val s = expectMostRecentItem()
      assertEquals(listOf(TodaySection.Afternoon), s.groups.map { it.section })
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test fun section_boundary_16_59_is_afternoon() = runTest(testDispatcher) {
    val flow = MutableStateFlow(
      listOf(fakeTask(id = "t", scheduledStart = Instant.parse("2026-06-21T16:59:00Z"), plannedDate = today))
    )
    val (vm, _) = vm(flow)
    vm.uiState.test {
      val s = expectMostRecentItem()
      assertEquals(listOf(TodaySection.Afternoon), s.groups.map { it.section })
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test fun section_boundary_17_00_is_evening() = runTest(testDispatcher) {
    val flow = MutableStateFlow(
      listOf(fakeTask(id = "t", scheduledStart = Instant.parse("2026-06-21T17:00:00Z"), plannedDate = today))
    )
    val (vm, _) = vm(flow)
    vm.uiState.test {
      val s = expectMostRecentItem()
      assertEquals(listOf(TodaySection.Evening), s.groups.map { it.section })
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test fun all_done_progress_has_zero_minutes_left() = runTest(testDispatcher) {
    val flow = MutableStateFlow(
      listOf(
        fakeTask(id = "1", status = TaskStatus.Done, plannedDate = today, durationMinutes = 30),
        fakeTask(id = "2", status = TaskStatus.Done, plannedDate = today, durationMinutes = 60),
      )
    )
    val (vm, _) = vm(flow)
    vm.uiState.test {
      val s = expectMostRecentItem()
      assertEquals(2, s.progress.done)
      assertEquals(2, s.progress.total)
      assertEquals(0, s.progress.minutesLeft)
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test fun empty_task_list_has_zero_progress() = runTest(testDispatcher) {
    val (vm, _) = vm(MutableStateFlow(emptyList()))
    vm.uiState.test {
      val s = expectMostRecentItem()
      assertEquals(0, s.progress.done)
      assertEquals(0, s.progress.total)
      assertEquals(0, s.progress.minutesLeft)
      assertTrue(s.groups.isEmpty())
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test fun timeblock_sets_scheduled_start_end_from_start_and_duration() = runTest(testDispatcher) {
    val flow = MutableStateFlow(
      listOf(fakeTask(id = "x", plannedDate = today, durationMinutes = 90)),
    )
    val (vm, repo) = vm(flow)
    vm.timeblock("x", LocalTime.of(10, 0))
    assertEquals(1, repo.updated.size)
    val (id, edit) = repo.updated.first()
    assertEquals("x", id)
    // FixedClock zone is UTC; today = 2026-06-21.
    assertEquals(Instant.parse("2026-06-21T10:00:00Z"), edit.scheduledStart)
    assertEquals(Instant.parse("2026-06-21T11:30:00Z"), edit.scheduledEnd)
    assertEquals(90, edit.durationMinutes)
  }

  @Test fun timeblock_defaults_to_60_minutes_when_duration_null() = runTest(testDispatcher) {
    val flow = MutableStateFlow(
      listOf(fakeTask(id = "y", plannedDate = today, durationMinutes = null)),
    )
    val (vm, repo) = vm(flow)
    vm.timeblock("y", LocalTime.of(9, 30))
    val edit = repo.updated.first().second
    assertEquals(Instant.parse("2026-06-21T09:30:00Z"), edit.scheduledStart)
    assertEquals(Instant.parse("2026-06-21T10:30:00Z"), edit.scheduledEnd)
    assertEquals(60, edit.durationMinutes)
  }

  @Test fun timeblock_unknown_task_is_noop() = runTest(testDispatcher) {
    val flow = MutableStateFlow(listOf(fakeTask(id = "x", plannedDate = today)))
    val (vm, repo) = vm(flow)
    vm.timeblock("does-not-exist", LocalTime.of(10, 0))
    assertTrue(repo.updated.isEmpty())
  }
}
