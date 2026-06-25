package net.qmindtech.tmap.ui.notes

import net.qmindtech.tmap.data.local.entities.NoteEntity
import net.qmindtech.tmap.data.local.entities.NoteGroupEntity
import net.qmindtech.tmap.data.local.entities.ProjectEntity
import net.qmindtech.tmap.ui.common.htmlToPlainText
import java.time.Instant

// `null` groupId in the chip row = the "All Notes" pseudo-notebook.
data class NotebookChip(val id: String?, val label: String, val selected: Boolean)

data class NoteCardUi(
  val id: String,
  val title: String,
  val snippet: String,
  val projectColor: Long?,   // null = no project dot
  val projectName: String?,  // null = no project label
  val updatedAt: Instant,
  val pinned: Boolean,
)

data class NotesUiState(
  val loading: Boolean = true,
  val chips: List<NotebookChip> = emptyList(),
  val selectedGroupId: String? = null,   // null = All Notes
  val pinned: List<NoteCardUi> = emptyList(),
  val recent: List<NoteCardUi> = emptyList(),
) {
  val isEmpty: Boolean get() = pinned.isEmpty() && recent.isEmpty()
}

// Snippet = first ~120 chars of content. Content is stored as rich HTML (TipTap) by the web
// client, so strip tags + decode entities (htmlToPlainText) before single-lining + ellipsising.
// Empty/blank content -> "".
fun noteSnippet(content: String, max: Int = 120): String {
  val flat = htmlToPlainText(content)
  if (flat.isEmpty()) return ""
  return if (flat.length <= max) flat else flat.take(max) + "…"
}

private fun parseColor(hex: String?): Long? {
  val h = hex?.trim()?.removePrefix("#") ?: return null
  val rgb = when (h.length) {
    6 -> h
    8 -> h.takeLast(6)
    else -> return null
  }
  return runCatching { 0xFF000000L or rgb.toLong(16) }.getOrNull()
}

private fun NoteEntity.toCardUi(projectsById: Map<String, ProjectEntity>): NoteCardUi {
  val proj = projectId?.let { projectsById[it] }
  return NoteCardUi(
    id = id,
    title = title,
    snippet = noteSnippet(content),
    projectColor = parseColor(proj?.color),
    projectName = proj?.name,
    updatedAt = updatedAt,
    pinned = pinnedAt != null,
  )
}

// Pure projection: chips (All Notes first, then groups in input order); pinned = pinnedAt != null
// sorted by pinnedAt desc (newest pin first); recent = the rest sorted by updatedAt desc.
fun buildNotesUiState(
  groups: List<NoteGroupEntity>,
  notes: List<NoteEntity>,
  projects: List<ProjectEntity>,
  selectedGroupId: String?,
): NotesUiState {
  val projectsById = projects.associateBy { it.id }
  val chips = buildList {
    add(NotebookChip(id = null, label = "All Notes", selected = selectedGroupId == null))
    groups.forEach { g -> add(NotebookChip(id = g.id, label = g.name, selected = g.id == selectedGroupId)) }
  }
  val (pinnedNotes, recentNotes) = notes.partition { it.pinnedAt != null }
  val pinned = pinnedNotes
    .sortedByDescending { it.pinnedAt }
    .map { it.toCardUi(projectsById) }
  val recent = recentNotes
    .sortedByDescending { it.updatedAt }
    .map { it.toCardUi(projectsById) }
  return NotesUiState(
    loading = false,
    chips = chips,
    selectedGroupId = selectedGroupId,
    pinned = pinned,
    recent = recent,
  )
}
