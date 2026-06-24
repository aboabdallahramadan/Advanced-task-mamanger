package net.qmindtech.tmap.ui.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.qmindtech.tmap.data.repository.NoteGroupRepository
import net.qmindtech.tmap.data.repository.NoteRepository
import net.qmindtech.tmap.data.repository.ProjectRepository
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class NotesViewModel @Inject constructor(
    private val noteRepo: NoteRepository,
    private val noteGroupRepo: NoteGroupRepository,
    private val projectRepo: ProjectRepository,
) : ViewModel() {

    private val selectedGroupId = MutableStateFlow<String?>(null)

    private val notesForSelection = selectedGroupId.flatMapLatest { groupId ->
        noteRepo.observeAll(groupId = groupId, projectId = null)
    }

    val uiState: StateFlow<NotesUiState> =
        combine(
            noteGroupRepo.observeAll(),
            notesForSelection,
            projectRepo.observeAll(),
            selectedGroupId,
        ) { groups, notes, projects, selected ->
            buildNotesUiState(groups, notes, projects, selected)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, NotesUiState())

    fun selectNotebook(groupId: String?) {
        selectedGroupId.value = groupId
    }

    fun createNote(onCreated: (String) -> Unit = {}) {
        viewModelScope.launch {
            val id = noteRepo.create(title = "", content = "", groupId = selectedGroupId.value, projectId = null)
            onCreated(id)
        }
    }

    fun togglePin(id: String, currentlyPinned: Boolean) {
        viewModelScope.launch { noteRepo.setPinned(id, !currentlyPinned) }
    }

    fun deleteNote(id: String) {
        viewModelScope.launch { noteRepo.delete(id) }
    }

    fun createNotebook(name: String, emoji: String = "📓") {
        val n = name.trim()
        if (n.isEmpty()) return
        viewModelScope.launch { noteGroupRepo.create(n, emoji, null) }
    }
}
