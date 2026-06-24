package net.qmindtech.tmap.ui.notes

import net.qmindtech.tmap.data.local.entities.NoteEntity
import net.qmindtech.tmap.data.local.entities.NoteGroupEntity
import net.qmindtech.tmap.data.local.entities.ProjectEntity

data class NoteEditorUiState(
    val noteId: String? = null,
    val isEdit: Boolean = false,
    val loading: Boolean = true,
    val title: String = "",
    val content: String = "",
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
    content = content,
    groupId = groupId,
    projectId = projectId,
    pinned = pinnedAt != null,
    groups = groups,
    projects = projects,
)
