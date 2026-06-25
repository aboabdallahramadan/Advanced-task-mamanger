package net.qmindtech.tmap.ui.notes

import net.qmindtech.tmap.data.local.entities.NoteEntity
import net.qmindtech.tmap.data.local.entities.NoteGroupEntity
import net.qmindtech.tmap.data.local.entities.ProjectEntity
import net.qmindtech.tmap.ui.common.htmlToPlainText

/**
 * Editor state.
 *
 * IMPORTANT (I2 fix): [content] holds the CLEANED, human-editable plain text shown in the
 * OutlinedTextField — NOT the raw HTML the web client stores. The original rich HTML is kept
 * verbatim in [originalContent] purely as the save-preservation source: if the user never edits
 * the body, the ViewModel passes `content = null` to the repository so the server HTML is
 * preserved (never flattened). Only when the user edits the body do we re-wrap their plain text
 * into minimal HTML on save.
 */
data class NoteEditorUiState(
    val noteId: String? = null,
    val isEdit: Boolean = false,
    val loading: Boolean = true,
    val title: String = "",
    val content: String = "",
    /** Verbatim stored HTML (web TipTap). Display uses [content]; this is only the save source. */
    val originalContent: String = "",
    val groupId: String? = null,
    val projectId: String? = null,
    val pinned: Boolean = false,
    val groups: List<NoteGroupEntity> = emptyList(),
    val projects: List<ProjectEntity> = emptyList(),
    val saved: Boolean = false,
)

fun NoteEntity.toEditorState(
    groups: List<NoteGroupEntity>,
    projects: List<ProjectEntity>,
): NoteEditorUiState = NoteEditorUiState(
    noteId = id,
    isEdit = true,
    loading = false,
    title = title,
    // Show cleaned text (no raw tags) in the editable field…
    content = htmlToPlainText(content),
    // …but keep the original HTML to preserve it on save when the body is untouched.
    originalContent = content,
    groupId = groupId,
    projectId = projectId,
    pinned = pinnedAt != null,
    groups = groups,
    projects = projects,
)
