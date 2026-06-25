package net.qmindtech.tmap.ui.notes

import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.qmindtech.tmap.testutil.FakeNoteGroupRepo
import net.qmindtech.tmap.testutil.FakeNoteRepo
import net.qmindtech.tmap.testutil.FakeProjectRepo
import net.qmindtech.tmap.testutil.fakeNote
import net.qmindtech.tmap.testutil.fakeNoteGroup
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class NotesViewModelTest {
  private val testDispatcher = UnconfinedTestDispatcher()
  @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
  @After fun tearDown() { Dispatchers.resetMain() }

  private fun vm(): Triple<NotesViewModel, FakeNoteRepo, FakeNoteGroupRepo> {
    val notes = FakeNoteRepo()
    val groups = FakeNoteGroupRepo().apply { allFlow.value = listOf(fakeNoteGroup(id = "g1", name = "Work")) }
    return Triple(NotesViewModel(notes, groups, FakeProjectRepo()), notes, groups)
  }

  @Test fun uiState_projects_chips_and_pinned_recent() = runTest {
    val (vm, notes, _) = vm()
    notes.allFlow.value = listOf(
      fakeNote(id = "p", title = "Pinned", pinnedAt = Instant.parse("2026-06-20T10:00:00Z")),
      fakeNote(id = "r", title = "Recent", updatedAt = Instant.parse("2026-06-20T09:00:00Z")),
    )
    vm.uiState.test {
      val s = expectMostRecentItem()
      assertEquals(false, s.loading)
      assertEquals(listOf(null, "g1"), s.chips.map { it.id })
      assertEquals(listOf("p"), s.pinned.map { it.id })
      assertEquals(listOf("r"), s.recent.map { it.id })
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test fun selectNotebook_requeries_notes_with_groupId() = runTest {
    val (vm, notes, _) = vm()
    vm.uiState.test { expectMostRecentItem(); cancelAndIgnoreRemainingEvents() }
    vm.selectNotebook("g1")
    vm.uiState.test {
      val s = expectMostRecentItem()
      assertEquals("g1", s.selectedGroupId)
      cancelAndIgnoreRemainingEvents()
    }
    assertEquals("g1" to null, notes.lastObserveAllArgs)
  }

  @Test fun createNote_delegates_with_current_notebook_and_returns_id() = runTest {
    val (vm, notes, _) = vm()
    notes.nextId = "n-123"
    vm.uiState.test { expectMostRecentItem(); cancelAndIgnoreRemainingEvents() }
    vm.selectNotebook("g1")
    var newId: String? = null
    vm.createNote { newId = it }
    assertEquals(1, notes.created.size)
    assertEquals("g1", notes.created.first().groupId)
    assertEquals("", notes.created.first().title)
    assertEquals("n-123", newId)
  }

  @Test fun togglePin_calls_setPinned_with_inverted_value() = runTest {
    val (vm, notes, _) = vm()
    vm.togglePin("x", currentlyPinned = false)
    vm.togglePin("y", currentlyPinned = true)
    assertEquals(listOf("x" to true, "y" to false), notes.pinned)
  }

  @Test fun deleteNote_and_createNotebook_delegate_blank_noop() = runTest {
    val (vm, notes, groups) = vm()
    vm.deleteNote("z"); assertEquals(listOf("z"), notes.deleted)
    vm.createNotebook("   "); assertTrue(groups.created.isEmpty())
    vm.createNotebook("Ideas", "💡")
    assertEquals(Triple("Ideas", "💡", null), groups.created.single())
  }

  @Test fun renameNotebook_delegates_with_trimmed_name_and_emoji() = runTest {
    val (vm, _, groups) = vm()
    vm.renameNotebook("g1", "  Work Stuff  ", "📁")
    val rec = groups.updates.single()
    assertEquals("g1", rec.id)
    assertEquals("Work Stuff", rec.name)
    assertEquals("📁", rec.emoji)
  }

  @Test fun renameNotebook_blank_name_is_noop() = runTest {
    val (vm, _, groups) = vm()
    vm.renameNotebook("g1", "   ", "📁")
    assertTrue(groups.updated.isEmpty())
  }

  @Test fun deleteNotebook_delegates_to_repo() = runTest {
    val (vm, _, groups) = vm()
    vm.deleteNotebook("g1")
    assertEquals(listOf("g1"), groups.deleted)
  }

  @Test fun deleteNotebook_resets_selection_to_all_notes_when_selected() = runTest {
    val (vm, _, groups) = vm()
    vm.uiState.test { expectMostRecentItem(); cancelAndIgnoreRemainingEvents() }
    vm.selectNotebook("g1")
    vm.deleteNotebook("g1")
    assertEquals(listOf("g1"), groups.deleted)
    vm.uiState.test {
      val s = expectMostRecentItem()
      assertEquals(null, s.selectedGroupId)
      cancelAndIgnoreRemainingEvents()
    }
  }
}
