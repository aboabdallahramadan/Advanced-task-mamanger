package net.qmindtech.tmap.ui.notes

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.qmindtech.tmap.data.repository.NoteGroupRepository
import net.qmindtech.tmap.data.repository.NoteRepository
import net.qmindtech.tmap.data.repository.ProjectRepository
import javax.inject.Inject

@HiltViewModel
class NoteEditorViewModel @Inject constructor(
    private val noteRepo: NoteRepository,
    private val noteGroupRepo: NoteGroupRepository,
    private val projectRepo: ProjectRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val rawId: String? = savedStateHandle.get<String?>("noteId")
    private val initialNoteId: String? = rawId?.takeIf { it.isNotBlank() && it != "new" }

    private var noteId: String? = initialNoteId
    private var observeJob: Job? = null

    /**
     * True if the note was blank (title and content both empty) at the time it was first loaded.
     * Used by [discardIfEmpty] to distinguish an eagerly-created blank note (safe to delete on
     * dismiss) from a pre-existing note whose content the user manually cleared (must NOT delete).
     * Set only once on the first non-null emission from the observe flow; user edits never flip it.
     */
    private var loadedBlank: Boolean = false
    private var loadedBlankSet: Boolean = false

    private val _state = MutableStateFlow(
        if (initialNoteId == null) NoteEditorUiState(isEdit = false, loading = false)
        else NoteEditorUiState()
    )
    val uiState: StateFlow<NoteEditorUiState> = _state.asStateFlow()

    init {
        startObserving(initialNoteId)
    }

    private fun startObserving(id: String?) {
        observeJob?.cancel()
        if (id != null) {
            observeJob = viewModelScope.launch {
                combine(
                    noteRepo.observe(id),
                    noteGroupRepo.observeAll(),
                    projectRepo.observeAll(),
                ) { note, groups, projects ->
                    Triple(note, groups, projects)
                }.collect { (note, groups, projects) ->
                    if (note != null) {
                        if (!loadedBlankSet) {
                            loadedBlank = note.title.isBlank() && note.content.isBlank()
                            loadedBlankSet = true
                        }
                        _state.value = note.toEditorState(groups, projects)
                    } else {
                        _state.update { it.copy(loading = false, groups = groups, projects = projects) }
                    }
                }
            }
        } else {
            observeJob = viewModelScope.launch {
                combine(
                    noteGroupRepo.observeAll(),
                    projectRepo.observeAll(),
                ) { groups, projects -> groups to projects }
                .collect { (groups, projects) ->
                    _state.update { it.copy(groups = groups, projects = projects) }
                }
            }
        }
    }

    fun onTitleChange(s: String) = _state.update { it.copy(title = s) }
    fun onContentChange(s: String) = _state.update { it.copy(content = s) }
    fun onGroupChange(id: String?) = _state.update { it.copy(groupId = id) }
    fun onProjectChange(id: String?) = _state.update { it.copy(projectId = id) }

    fun togglePin() {
        val id = noteId ?: return
        val newPinned = !_state.value.pinned
        _state.update { it.copy(pinned = newPinned) }
        viewModelScope.launch { noteRepo.setPinned(id, newPinned) }
    }

    fun save(onDone: () -> Unit) {
        val s = _state.value
        if (s.title.isBlank() && s.content.isBlank()) return
        val id = noteId
        viewModelScope.launch {
            if (id == null) {
                val newId = noteRepo.create(s.title.trim(), s.content, s.groupId, s.projectId)
                noteId = newId
                _state.update { it.copy(noteId = newId, isEdit = true, saved = true) }
            } else {
                noteRepo.update(id, title = s.title.trim(), content = s.content, groupId = s.groupId, projectId = s.projectId)
                _state.update { it.copy(saved = true) }
            }
            onDone()
        }
    }

    fun delete(onDone: () -> Unit) {
        val id = noteId ?: run { onDone(); return }
        viewModelScope.launch { noteRepo.delete(id); onDone() }
    }

    /**
     * Called by [NoteEditorSheet] when the sheet is dismissed.
     * Deletes the note ONLY if it was blank when loaded (eagerly-created, never typed into).
     * A pre-existing note whose content the user manually cleared is NOT deleted here.
     */
    fun discardIfEmpty() {
        val id = noteId ?: return
        val s = _state.value
        if (s.isEdit && loadedBlank && s.title.isBlank() && s.content.isBlank()) {
            viewModelScope.launch { noteRepo.delete(id) }
        }
    }
}
