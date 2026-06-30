package net.qmindtech.tmap.ui.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.qmindtech.tmap.data.repository.NoteRepository
import net.qmindtech.tmap.ui.common.wrapPlainTextToHtml
import net.qmindtech.tmap.util.NoteCapture
import javax.inject.Inject

/**
 * Backs the Quick-add Note widget overlay ([net.qmindtech.tmap.widget.NoteCaptureTrampolineActivity]).
 * One free-text field → [NoteCapture.fromQuickText] → [NoteRepository.create] (write-through). On a
 * successful save it invokes [submit]'s `onSaved` so the host activity can toast + finish (note
 * capture is one-and-done, not rapid-fire).
 */
@HiltViewModel
class QuickNoteViewModel @Inject constructor(
    private val noteRepo: NoteRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(QuickNoteUiState())
    val uiState: StateFlow<QuickNoteUiState> = _state.asStateFlow()

    fun onTextChange(s: String) = _state.update { it.copy(text = s, canSubmit = s.isNotBlank()) }

    /** Persist the note. Calls [onSaved] only when a note was actually created. */
    fun submit(onSaved: () -> Unit) {
        val draft = NoteCapture.fromQuickText(_state.value.text) ?: return
        viewModelScope.launch {
            // Swallow a (rare) persistence failure instead of crashing: leave the overlay open with
            // the user's text intact so they can retry. Reset + onSaved only on success.
            runCatching { noteRepo.create(draft.title, wrapPlainTextToHtml(draft.content)) }
                .onSuccess {
                    _state.value = QuickNoteUiState()
                    onSaved()
                }
        }
    }
}
