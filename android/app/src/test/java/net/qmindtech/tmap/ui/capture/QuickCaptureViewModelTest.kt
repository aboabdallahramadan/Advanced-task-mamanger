package net.qmindtech.tmap.ui.capture

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.testutil.FakeProjectRepo
import net.qmindtech.tmap.testutil.FakeTaskRepo
import net.qmindtech.tmap.testutil.FixedClock
import net.qmindtech.tmap.testutil.fakeProject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class QuickCaptureViewModelTest {
  private val clock = FixedClock(Instant.parse("2026-06-21T06:00:00Z")) // Sunday
  private val testDispatcher = UnconfinedTestDispatcher()
  @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
  @After fun tearDown() { Dispatchers.resetMain() }

  private fun vm(repo: FakeTaskRepo = FakeTaskRepo(), projs: FakeProjectRepo = FakeProjectRepo()): QuickCaptureViewModel {
    projs.setAll(listOf(fakeProject(id = "p-work", name = "Work")))
    return QuickCaptureViewModel(repo, projs, QuickCaptureParser(clock), clock)
  }

  @Test fun text_change_parses_and_enables_submit() = runTest(testDispatcher) {
    val vm = vm()
    vm.onTextChange("Finish slides #Work tomorrow 3pm")
    val s = vm.uiState.value
    assertEquals("Finish slides", s.parsed.title)
    assertEquals("p-work", s.parsed.projectId)
    assertTrue(s.canSubmit)
  }

  @Test fun submit_with_no_date_defaults_to_inbox_and_clears_text() = runTest(testDispatcher) {
    val repo = FakeTaskRepo()
    val vm = vm(repo)
    vm.onTextChange("Buy milk")
    vm.submit()
    assertEquals(1, repo.created.size)
    assertEquals("Buy milk", repo.created.first().title)
    assertEquals(TaskStatus.Inbox, repo.created.first().status)
    assertNull(repo.created.first().plannedDate)
    assertEquals("", vm.uiState.value.text) // stays open, field cleared
  }

  @Test fun submit_with_date_is_planned() = runTest(testDispatcher) {
    val repo = FakeTaskRepo()
    val vm = vm(repo)
    vm.onTextChange("Call mom tomorrow")
    vm.submit()
    assertEquals(TaskStatus.Planned, repo.created.first().status)
    assertEquals(LocalDate.of(2026, 6, 22), repo.created.first().plannedDate)
  }

  @Test fun chipToday_sets_today_and_chipInbox_clears() = runTest(testDispatcher) {
    val repo = FakeTaskRepo()
    val vm = vm(repo)
    vm.onTextChange("Standup")
    vm.chipToday()
    vm.submit()
    assertEquals(LocalDate.of(2026, 6, 21), repo.created.first().plannedDate)
    vm.onTextChange("Later")
    vm.chipToday()
    vm.chipInbox()
    vm.submit()
    assertEquals(TaskStatus.Inbox, repo.created[1].status)
    assertNull(repo.created[1].plannedDate)
  }

  @Test fun chipPriority_cycles_and_chipRemind_sets_reminder() = runTest(testDispatcher) {
    val repo = FakeTaskRepo()
    val vm = vm(repo)
    vm.onTextChange("Ship")
    vm.chipPriority()            // → 2 (high)
    vm.chipRemind()              // remind on
    vm.submit()
    assertEquals(2, repo.created.first().priority)
    assertEquals(0, repo.created.first().reminderMinutes) // at-start
  }

  @Test fun submit_blank_is_noop() = runTest(testDispatcher) {
    val repo = FakeTaskRepo()
    val vm = vm(repo)
    vm.onTextChange("   ")
    vm.submit()
    assertTrue(repo.created.isEmpty())
  }

  @Test fun chipToday_surfaces_active_state_and_chipInbox_clears_it() = runTest(testDispatcher) {
    val vm = vm()
    vm.chipToday()
    assertTrue("chipTodayActive should be true after chipToday()", vm.uiState.value.chipTodayActive)
    assertFalse("chipInboxActive should be false after chipToday()", vm.uiState.value.chipInboxActive)
    vm.chipInbox()
    assertTrue("chipInboxActive should be true after chipInbox()", vm.uiState.value.chipInboxActive)
    assertFalse("chipTodayActive should be false after chipInbox()", vm.uiState.value.chipTodayActive)
  }

  @Test fun chipPriority_surfaces_priorityLevel_and_cycles() = runTest(testDispatcher) {
    val vm = vm()
    assertEquals("priorityLevel starts at 0", 0, vm.uiState.value.priorityLevel)
    vm.chipPriority() // 0→2
    assertEquals(2, vm.uiState.value.priorityLevel)
    vm.chipPriority() // 2→1
    assertEquals(1, vm.uiState.value.priorityLevel)
    vm.chipPriority() // 1→0 (off)
    assertEquals(0, vm.uiState.value.priorityLevel)
  }

  @Test fun chip_active_state_resets_after_submit() = runTest(testDispatcher) {
    val vm = vm()
    vm.onTextChange("Task")
    vm.chipToday()
    vm.chipPriority()
    assertTrue(vm.uiState.value.chipTodayActive)
    assertEquals(2, vm.uiState.value.priorityLevel)
    vm.submit()
    assertFalse("chipTodayActive resets after submit", vm.uiState.value.chipTodayActive)
    assertEquals("priorityLevel resets after submit", 0, vm.uiState.value.priorityLevel)
  }
}
