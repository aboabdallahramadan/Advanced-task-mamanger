package net.qmindtech.tmap.ui.notes

/** Minimal state for the Quick-add Note widget overlay: the field text and whether it can be saved. */
data class QuickNoteUiState(
    val text: String = "",
    val canSubmit: Boolean = false,
)
