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
import org.junit.Assert.assertFalse
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

    /** Simulates the sheet path: ViewModel created with no SavedStateHandle noteId. */
    private fun sheetVm(notes: FakeNoteRepo): NoteEditorViewModel =
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
        // Body is wrapped to minimal HTML on create (round-trips with the web TipTap editor).
        assertEquals("<p>Some content</p>", notes.created.first().content)
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

    // ── I2 fix: HTML content cleaning + dirty-tracking save guardrail ──────────

    /** (i) Opening a note with HTML content shows CLEANED text (no tags) in state. */
    @Test fun load_html_note_shows_cleaned_text_in_state() = runTest(testDispatcher) {
        val notes = FakeNoteRepo()
        notes.singleFlow.value = fakeNote(
            id = "n1",
            title = "Q3",
            content = "<p>Foo &amp; bar &#39;baz&#39;</p>",
        )
        val vm = editVm(notes)
        // Editable field shows decoded plain text — no raw tags/entities.
        assertEquals("Foo & bar 'baz'", vm.uiState.value.content)
        // Original HTML preserved separately as the save source.
        assertEquals("<p>Foo &amp; bar &#39;baz&#39;</p>", vm.uiState.value.originalContent)
    }

    /**
     * (ii) Saving WITHOUT editing the body passes content=null to update so the server HTML is
     * preserved (NoteRepository.update does content ?: current.content). THE critical guardrail.
     */
    @Test fun save_without_editing_body_passes_null_content_preserving_server_html() =
        runTest(testDispatcher) {
            val notes = FakeNoteRepo()
            notes.singleFlow.value = fakeNote(
                id = "n1",
                title = "Q3",
                content = "<p>Rich <b>server</b> HTML &amp; more</p>",
            )
            val vm = editVm(notes)
            // User edits only the TITLE, never touches the body.
            vm.onTitleChange("Q3 Strategy")
            vm.save {}
            val rec = notes.updates.single()
            assertEquals("n1", rec.id)
            assertEquals("Q3 Strategy", rec.title)
            // content == null → repository keeps the original server HTML untouched.
            assertEquals(null, rec.content)
        }

    /** (iii) Editing the body then saving passes wrapped HTML (the user's new text). */
    @Test fun save_after_editing_body_passes_wrapped_html() = runTest(testDispatcher) {
        val notes = FakeNoteRepo()
        notes.singleFlow.value = fakeNote(
            id = "n1",
            title = "Q3",
            content = "<p>Old body</p>",
        )
        val vm = editVm(notes)
        vm.onContentChange("New body & stuff")
        vm.save {}
        val rec = notes.updates.single()
        assertEquals("n1", rec.id)
        assertEquals("<p>New body &amp; stuff</p>", rec.content)
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

    // ── load() — sheet path (SavedStateHandle has no noteId) ──────────────────

    @Test fun load_existing_note_switches_to_edit_mode_and_populates_state() = runTest(testDispatcher) {
        val notes = FakeNoteRepo()
        notes.singleFlow.value = fakeNote(id = "n1", title = "Meeting notes", content = "Action items")
        val vm = sheetVm(notes)
        assertEquals(false, vm.uiState.value.isEdit)

        vm.load("n1")

        assertEquals(true, vm.uiState.value.isEdit)
        assertEquals("Meeting notes", vm.uiState.value.title)
        assertEquals("Action items", vm.uiState.value.content)
        assertFalse(vm.uiState.value.loading)
    }

    @Test fun load_existing_note_with_content_sets_loadedBlank_false_so_discard_does_not_delete() = runTest(testDispatcher) {
        val notes = FakeNoteRepo()
        notes.singleFlow.value = fakeNote(id = "n1", title = "Important note", content = "Keep this")
        val vm = sheetVm(notes)
        vm.load("n1")

        // Note had content when loaded → loadedBlank=false → discardIfEmpty must NOT delete.
        vm.discardIfEmpty()
        assertTrue(notes.deleted.isEmpty())
    }

    @Test fun load_existing_blank_note_sets_loadedBlank_true_so_discard_deletes() = runTest(testDispatcher) {
        val notes = FakeNoteRepo()
        notes.singleFlow.value = fakeNote(id = "n2", title = "", content = "")
        val vm = sheetVm(notes)
        vm.load("n2")

        // Note was blank when loaded (eagerly created) → loadedBlank=true → discardIfEmpty deletes.
        vm.discardIfEmpty()
        assertEquals(listOf("n2"), notes.deleted)
    }

    @Test fun load_null_stays_in_create_mode() = runTest(testDispatcher) {
        val notes = FakeNoteRepo()
        val vm = sheetVm(notes)
        vm.load(null)
        assertEquals(false, vm.uiState.value.isEdit)
        assertEquals("", vm.uiState.value.title)
    }

    @Test fun load_new_sentinel_stays_in_create_mode() = runTest(testDispatcher) {
        val notes = FakeNoteRepo()
        val vm = sheetVm(notes)
        vm.load("new")
        assertEquals(false, vm.uiState.value.isEdit)
    }

    // ── observe-job cancellation regression ───────────────────────────────────

    /**
     * Regression: load("a") then load("b") must cancel the "a" collector so that
     * a later emission on "a"'s flow cannot overwrite _state with stale data.
     */
    @Test fun load_cancels_prior_observe_so_stale_note_a_emit_does_not_overwrite_note_b() =
        runTest(testDispatcher) {
            val notes = FakeNoteRepo()
            notes.setForId(fakeNote(id = "a", title = "Note A"))
            notes.setForId(fakeNote(id = "b", title = "Note B"))

            val vm = sheetVm(notes)

            vm.load("a")
            assertEquals("Note A", vm.uiState.value.title)

            vm.load("b")
            assertEquals("Note B", vm.uiState.value.title)

            // Emit an update to note "a" — the prior observeJob must be cancelled so this
            // emission does NOT overwrite the state that now belongs to note "b".
            notes.emitForId("a", fakeNote(id = "a", title = "Note A — updated"))

            assertEquals("Note B", vm.uiState.value.title)
        }
}
