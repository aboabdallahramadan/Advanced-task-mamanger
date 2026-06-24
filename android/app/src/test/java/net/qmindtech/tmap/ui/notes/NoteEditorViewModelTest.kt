package net.qmindtech.tmap.ui.notes

import androidx.lifecycle.SavedStateHandle
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NoteEditorViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun editVm(notes: FakeNoteRepo, id: String = "n1"): NoteEditorViewModel =
        NoteEditorViewModel(
            noteRepo = notes,
            noteGroupRepo = FakeNoteGroupRepo(),
            projectRepo = FakeProjectRepo(),
            savedStateHandle = SavedStateHandle(mapOf("noteId" to id)),
        )

    private fun createVm(notes: FakeNoteRepo): NoteEditorViewModel =
        NoteEditorViewModel(
            noteRepo = notes,
            noteGroupRepo = FakeNoteGroupRepo(),
            projectRepo = FakeProjectRepo(),
            savedStateHandle = SavedStateHandle(mapOf("noteId" to null)),
        )

    @Test fun toEditorState_maps_entity_fields() {
        val note = fakeNote(id = "n1", title = "Q3 Strategy", content = "Three bets", groupId = "g1", projectId = "p1")
        val state = note.toEditorState(emptyList(), emptyList())
        assertEquals(true, state.isEdit)
        assertEquals("Q3 Strategy", state.title)
        assertEquals("Three bets", state.content)
        assertEquals("g1", state.groupId)
        assertEquals("p1", state.projectId)
        assertEquals(false, state.loading)
    }

    @Test fun toEditorState_pinned_when_pinnedAt_set() {
        val note = fakeNote(id = "n1", pinnedAt = java.time.Instant.now())
        val state = note.toEditorState(emptyList(), emptyList())
        assertEquals(true, state.pinned)
    }

    @Test fun create_mode_starts_with_isEdit_false_and_loading_false() {
        val vm = createVm(FakeNoteRepo())
        assertEquals(false, vm.uiState.value.isEdit)
        assertEquals(false, vm.uiState.value.loading)
    }

    @Test fun edit_mode_loads_note_from_flow() = runTest(testDispatcher) {
        val notes = FakeNoteRepo()
        notes.singleFlow.value = fakeNote(id = "n1", title = "Loaded Note")
        val vm = editVm(notes)
        assertEquals("Loaded Note", vm.uiState.value.title)
        assertEquals(true, vm.uiState.value.isEdit)
    }

    @Test fun save_create_calls_repo_create() = runTest(testDispatcher) {
        val notes = FakeNoteRepo()
        val vm = createVm(notes)
        vm.onTitleChange("My Note")
        vm.onContentChange("Some content")
        var done = false
        vm.save { done = true }
        assertEquals(1, notes.created.size)
        assertEquals("My Note", notes.created.first().title)
        assertEquals("Some content", notes.created.first().content)
        assertTrue(done)
    }

    @Test fun save_edit_calls_repo_update() = runTest(testDispatcher) {
        val notes = FakeNoteRepo()
        notes.singleFlow.value = fakeNote(id = "n1", title = "Old Title")
        val vm = editVm(notes)
        vm.onTitleChange("New Title")
        var done = false
        vm.save { done = true }
        assertEquals(1, notes.updated.size)
        assertEquals("n1", notes.updated.first())
        assertTrue(done)
    }

    @Test fun save_blank_title_and_content_is_noop() = runTest(testDispatcher) {
        val notes = FakeNoteRepo()
        val vm = createVm(notes)
        var done = false
        vm.save { done = true }
        assertTrue(notes.created.isEmpty())
        assertEquals(false, done)
    }

    @Test fun save_blank_title_but_non_blank_content_proceeds() = runTest(testDispatcher) {
        val notes = FakeNoteRepo()
        val vm = createVm(notes)
        vm.onContentChange("Some content without title")
        var done = false
        vm.save { done = true }
        assertEquals(1, notes.created.size)
        assertTrue(done)
    }

    @Test fun delete_delegates_and_invokes_callback() = runTest(testDispatcher) {
        val notes = FakeNoteRepo()
        notes.singleFlow.value = fakeNote(id = "n1")
        val vm = editVm(notes)
        var done = false
        vm.delete { done = true }
        assertEquals(listOf("n1"), notes.deleted)
        assertTrue(done)
    }

    @Test fun delete_create_mode_invokes_callback_without_repo_call() = runTest(testDispatcher) {
        val notes = FakeNoteRepo()
        val vm = createVm(notes)
        var done = false
        vm.delete { done = true }
        assertTrue(notes.deleted.isEmpty())
        assertTrue(done)
    }

    @Test fun togglePin_calls_setPinned_with_inverted_value() = runTest(testDispatcher) {
        val notes = FakeNoteRepo()
        notes.singleFlow.value = fakeNote(id = "n1")
        val vm = editVm(notes)
        // initial pinned = false (no pinnedAt)
        vm.togglePin()
        assertEquals(listOf("n1" to true), notes.pinned)
    }

    @Test fun onGroupChange_updates_state() {
        val vm = createVm(FakeNoteRepo())
        vm.onGroupChange("g1")
        assertEquals("g1", vm.uiState.value.groupId)
    }

    @Test fun onProjectChange_updates_state() {
        val vm = createVm(FakeNoteRepo())
        vm.onProjectChange("p1")
        assertEquals("p1", vm.uiState.value.projectId)
    }

    @Test fun discardIfEmpty_edit_mode_blank_deletes_note() = runTest(testDispatcher) {
        // Note was eagerly created blank and was never typed into → delete on dismiss.
        val notes = FakeNoteRepo()
        notes.singleFlow.value = fakeNote(id = "n1", title = "", content = "")
        val vm = editVm(notes)
        vm.discardIfEmpty()
        assertEquals(listOf("n1"), notes.deleted)
    }

    @Test fun discardIfEmpty_edit_mode_with_content_does_not_delete() = runTest(testDispatcher) {
        val notes = FakeNoteRepo()
        notes.singleFlow.value = fakeNote(id = "n1", title = "Hello", content = "")
        val vm = editVm(notes)
        vm.discardIfEmpty()
        assertTrue(notes.deleted.isEmpty())
    }

    /**
     * Regression test for the data-loss bug: a pre-existing note loaded WITH content, then
     * blanked by the user, must NOT be deleted on dismiss.
     */
    @Test fun discardIfEmpty_loaded_with_content_user_blanks_does_not_delete() = runTest(testDispatcher) {
        val notes = FakeNoteRepo()
        notes.singleFlow.value = fakeNote(id = "n1", title = "Important note", content = "Do not lose this")
        val vm = editVm(notes)
        // User clears all text before dismissing.
        vm.onTitleChange("")
        vm.onContentChange("")
        vm.discardIfEmpty()
        // loadedBlank was false (note had content when loaded) → must NOT delete.
        assertTrue(notes.deleted.isEmpty())
    }

    @Test fun discardIfEmpty_create_mode_is_noop() = runTest(testDispatcher) {
        val notes = FakeNoteRepo()
        val vm = createVm(notes)
        vm.discardIfEmpty()
        assertTrue(notes.deleted.isEmpty())
    }

    @Test fun save_create_promotes_noteId_and_isEdit() = runTest(testDispatcher) {
        val notes = FakeNoteRepo()
        notes.nextId = "created-id"
        val vm = createVm(notes)
        vm.onTitleChange("New note")
        vm.onContentChange("Some content")
        vm.save {}
        // VM must no longer be in create-mode after a successful save.
        assertEquals("created-id", vm.uiState.value.noteId)
        assertEquals(true, vm.uiState.value.isEdit)
        assertEquals(true, vm.uiState.value.saved)
    }
}
