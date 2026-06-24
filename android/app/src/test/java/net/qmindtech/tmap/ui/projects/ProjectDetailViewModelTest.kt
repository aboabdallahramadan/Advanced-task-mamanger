package net.qmindtech.tmap.ui.projects

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.local.dao.ProjectProgress
import net.qmindtech.tmap.testutil.FakeNoteRepo
import net.qmindtech.tmap.testutil.FakeProjectRepo
import net.qmindtech.tmap.testutil.FakeTaskRepo
import net.qmindtech.tmap.testutil.fakeNote
import net.qmindtech.tmap.testutil.fakeProject
import net.qmindtech.tmap.testutil.fakeTask
import net.qmindtech.tmap.ui.navigation.Route
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectDetailViewModelTest {
  private val testDispatcher = UnconfinedTestDispatcher()

  @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
  @After fun tearDown() { Dispatchers.resetMain() }

  private fun vmWith(noteRepo: FakeNoteRepo = FakeNoteRepo()): Triple<ProjectDetailViewModel, FakeTaskRepo, FakeNoteRepo> {
    val taskRepo = FakeTaskRepo().apply {
      setAll(
        listOf(
          fakeTask(id = "a", projectId = "p1", status = TaskStatus.Planned, rank = "0001"),
          fakeTask(id = "b", projectId = "p1", status = TaskStatus.Planned, rank = "0000"),
          fakeTask(id = "c", projectId = "p2", status = TaskStatus.Planned),
        )
      )
    }
    val projRepo = FakeProjectRepo().apply {
      setAll(listOf(fakeProject(id = "p1", name = "Work"), fakeProject(id = "p2", name = "Home")))
      setProgress(listOf(ProjectProgress("p1", total = 2, done = 0)))
    }
    val handle = SavedStateHandle(mapOf(Route.ProjectDetail.ARG_PROJECT_ID to "p1"))
    return Triple(ProjectDetailViewModel(handle, taskRepo, projRepo, noteRepo), taskRepo, noteRepo)
  }

  @Test fun loads_project_header_and_only_its_tasks_by_rank() = runTest {
    val (vm, _, _) = vmWith()
    vm.uiState.test {
      val s = expectMostRecentItem()
      assertEquals("Work", s.project?.name)
      assertEquals(2, s.total)
      assertEquals(0, s.done)
      assertEquals(0f, s.progress)
      assertEquals(listOf("b", "a"), s.items.map { it.task.id }) // manual rank asc
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test fun toggleDone_delegates() = runTest {
    val (vm, repo, _) = vmWith()
    vm.toggleDone(fakeTask(id = "a", projectId = "p1"))
    assertEquals(listOf("a"), repo.markedDone)
  }

  @Test fun update_delegates_to_project_repo() = runTest {
    val taskRepo = FakeTaskRepo()
    val projRepo = FakeProjectRepo().apply {
      setAll(listOf(fakeProject(id = "p1", name = "Work")))
      setProgress(emptyList())
    }
    val handle = SavedStateHandle(mapOf(Route.ProjectDetail.ARG_PROJECT_ID to "p1"))
    val vm = ProjectDetailViewModel(handle, taskRepo, projRepo, FakeNoteRepo())
    vm.update("Work Updated", "#FF0000", "🚀")
    assertEquals(listOf("p1"), projRepo.updated)
  }

  @Test fun update_blank_name_is_noop() = runTest {
    val taskRepo = FakeTaskRepo()
    val projRepo = FakeProjectRepo().apply {
      setAll(listOf(fakeProject(id = "p1", name = "Work")))
      setProgress(emptyList())
    }
    val handle = SavedStateHandle(mapOf(Route.ProjectDetail.ARG_PROJECT_ID to "p1"))
    val vm = ProjectDetailViewModel(handle, taskRepo, projRepo, FakeNoteRepo())
    vm.update("  ", "#FF0000", "🚀")
    assertEquals(emptyList<String>(), projRepo.updated)
  }

  @Test fun delete_delegates_to_project_repo() = runTest {
    val taskRepo = FakeTaskRepo()
    val projRepo = FakeProjectRepo().apply {
      setAll(listOf(fakeProject(id = "p1", name = "Work")))
      setProgress(emptyList())
    }
    val handle = SavedStateHandle(mapOf(Route.ProjectDetail.ARG_PROJECT_ID to "p1"))
    val vm = ProjectDetailViewModel(handle, taskRepo, projRepo, FakeNoteRepo())
    vm.delete()
    assertEquals(listOf("p1"), projRepo.deleted)
  }

  // ── P4.7: notes flow ────────────────────────────────────────────────────────

  @Test fun notes_flow_exposes_project_notes_sorted_by_updatedAt_desc() = runTest {
    val noteRepo = FakeNoteRepo()
    val older = fakeNote(id = "n1", projectId = "p1", title = "Old note",
      updatedAt = Instant.parse("2026-01-01T10:00:00Z"))
    val newer = fakeNote(id = "n2", projectId = "p1", title = "New note",
      updatedAt = Instant.parse("2026-01-01T12:00:00Z"))
    noteRepo.allFlow.value = listOf(older, newer)

    val (vm, _, _) = vmWith(noteRepo)
    vm.uiState.test {
      val s = expectMostRecentItem()
      // observeAll(null, "p1") is wired; FakeNoteRepo records the args
      assertEquals(null to "p1", noteRepo.lastObserveAllArgs)
      // sorted newest-first
      assertEquals(listOf("n2", "n1"), s.notes.map { it.id })
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test fun notes_flow_is_empty_when_project_has_no_notes() = runTest {
    val noteRepo = FakeNoteRepo() // allFlow starts empty
    val (vm, _, _) = vmWith(noteRepo)
    vm.uiState.test {
      val s = expectMostRecentItem()
      assertEquals(emptyList<Any>(), s.notes)
      cancelAndIgnoreRemainingEvents()
    }
  }
}
