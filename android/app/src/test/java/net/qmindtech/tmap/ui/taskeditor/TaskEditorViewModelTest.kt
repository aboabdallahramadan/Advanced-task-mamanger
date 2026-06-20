package net.qmindtech.tmap.ui.taskeditor

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.testutil.FakeProjectRepo
import net.qmindtech.tmap.testutil.FakeSubtaskRepo
import net.qmindtech.tmap.testutil.FakeTaskRepo
import net.qmindtech.tmap.testutil.FixedClock
import net.qmindtech.tmap.testutil.fakeProject
import net.qmindtech.tmap.testutil.fakeSubtask
import net.qmindtech.tmap.testutil.fakeTask
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class TaskEditorViewModelTest {
  private val now = Instant.parse("2026-06-18T12:00:00Z")
  private fun clock() = FixedClock(now)
  private val testDispatcher = UnconfinedTestDispatcher()

  @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
  @After fun tearDown() { Dispatchers.resetMain() }

  private fun editVm(repo: FakeTaskRepo, subs: FakeSubtaskRepo = FakeSubtaskRepo(), id: String = "t1"): TaskEditorViewModel =
    TaskEditorViewModel(repo, subs, FakeProjectRepo(), clock(), SavedStateHandle(mapOf("taskId" to id)))

  private fun createVm(repo: FakeTaskRepo, subs: FakeSubtaskRepo = FakeSubtaskRepo()): TaskEditorViewModel =
    TaskEditorViewModel(repo, subs, FakeProjectRepo(), clock(), SavedStateHandle(mapOf("taskId" to null)))

  @Test fun toEditorState_maps_entity_fields() {
    val t = fakeTask(
      id = "t1", title = "Plan", notes = "<p>x</p>", projectId = "p1", priority = 2,
      status = TaskStatus.Planned, plannedDate = LocalDate.of(2026, 6, 18), durationMinutes = 45,
      reminderMinutes = 10,
    )
    val subs = listOf(fakeSubtask(id = "s1", taskId = "t1"))
    val projs = listOf(fakeProject(id = "p1", name = "Work"))
    val s = t.toEditorState(subs, projs)
    assertEquals(true, s.isEdit)
    assertEquals("Plan", s.title)
    assertEquals("p1", s.projectId)
    assertEquals(2, s.priority)
    assertEquals(45, s.durationMinutes)
    assertEquals(10, s.reminderMinutes)
    assertEquals(1, s.subtasks.size)
    assertEquals(1, s.projects.size)
    assertEquals(false, s.loading)
  }

  @Test fun save_create_dispatches_create_with_draft() = runTest(testDispatcher) {
    val repo = FakeTaskRepo()
    val vm = createVm(repo)
    vm.onTitleChange("New thing")
    vm.onStatusChange(TaskStatus.Backlog)
    var done = false
    vm.save { done = true }
    assertEquals(1, repo.created.size)
    assertEquals("New thing", repo.created.first().title)
    assertEquals(TaskStatus.Backlog, repo.created.first().status)
    assertTrue(repo.updated.isEmpty())
    assertTrue(done)
  }

  @Test fun save_edit_dispatches_update() = runTest(testDispatcher) {
    val repo = FakeTaskRepo()
    repo.setSingle(fakeTask(id = "t1", title = "Old", status = TaskStatus.Planned))
    val vm = editVm(repo)
    vm.onTitleChange("Renamed")
    var done = false
    vm.save { done = true }
    assertEquals(1, repo.updated.size)
    assertEquals("t1", repo.updated.first().first)
    assertEquals("Renamed", repo.updated.first().second.title)
    assertTrue(repo.created.isEmpty())
    assertTrue(done)
  }

  @Test fun save_blank_title_is_noop() = runTest(testDispatcher) {
    val repo = FakeTaskRepo()
    val vm = createVm(repo)
    vm.onTitleChange("   ")
    var done = false
    vm.save { done = true }
    assertTrue(repo.created.isEmpty())
    assertEquals(false, done)
  }

  @Test fun markDone_sets_status_done_and_completedAt_and_delegates() = runTest(testDispatcher) {
    val repo = FakeTaskRepo()
    repo.setSingle(fakeTask(id = "t1", status = TaskStatus.Planned))
    val vm = editVm(repo)
    vm.markDone()
    assertEquals(TaskStatus.Done, vm.uiState.value.status)
    assertEquals(now, vm.uiState.value.completedAt)
    assertEquals(listOf("t1"), repo.markedDone)
  }

  @Test fun delete_delegates_and_invokes_callback() = runTest(testDispatcher) {
    val repo = FakeTaskRepo()
    repo.setSingle(fakeTask(id = "t1"))
    val vm = editVm(repo)
    var done = false
    vm.delete { done = true }
    assertEquals(listOf("t1"), repo.deleted)
    assertTrue(done)
  }

  @Test fun addSubtask_only_when_persisted_task() = runTest(testDispatcher) {
    val repo = FakeTaskRepo()
    val subs = FakeSubtaskRepo()
    // create mode: no taskId → addSubtask is a no-op
    val createVm = createVm(repo, subs)
    createVm.addSubtask("nope")
    assertTrue(subs.created.isEmpty())
    // edit mode: persisted task → creates
    repo.setSingle(fakeTask(id = "t1"))
    val edit = editVm(repo, subs)
    edit.addSubtask("Real sub")
    assertEquals(listOf("t1" to "Real sub"), subs.created)
  }
}
