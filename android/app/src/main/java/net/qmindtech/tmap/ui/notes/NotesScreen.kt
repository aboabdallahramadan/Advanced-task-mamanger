package net.qmindtech.tmap.ui.notes

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.StickyNote2
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.qmindtech.tmap.ui.components.EmptyState
import net.qmindtech.tmap.ui.components.EmptySurface
import net.qmindtech.tmap.ui.components.emptyCopyFor
import net.qmindtech.tmap.ui.components.FilterChip
import net.qmindtech.tmap.ui.components.SectionLabel
import net.qmindtech.tmap.ui.components.TmapFab
import net.qmindtech.tmap.ui.theme.LocalTmapColors
import net.qmindtech.tmap.ui.theme.LocalTmapShapes
import net.qmindtech.tmap.ui.theme.LocalTmapSpacing
import net.qmindtech.tmap.ui.theme.LocalTmapType
import net.qmindtech.tmap.ui.theme.TmapTheme
import java.time.Instant

/** A handful of emoji choices for the notebook create/rename dialog. */
private val NOTEBOOK_EMOJIS: List<String> = listOf("📓", "💼", "🏠", "💡", "🎯", "📚", "🚀", "🎨")

// ─────────────────────────────────────────────────────────────────────────────
// Stateful entry point (wired to NavGraph in P4.7)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Notes tab — stateful entry point.
 *
 * Collects [NotesViewModel.uiState] with lifecycle-awareness, then delegates rendering to
 * [NotesContent]. The FAB triggers [NotesViewModel.createNote] and immediately opens the newly
 * created note via [onOpenNote]. Card taps forward the existing note id directly.
 *
 * @param onOpenNote  Navigation callback: called with a note id (existing) or a freshly-created id
 *                    (new). The editor sheet (P4.6) is hoisted above this screen; P4.7 wires it.
 */
@Composable
fun NotesScreen(
    onOpenNote: (String) -> Unit,
    viewModel: NotesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // null = no dialog; NotebookDialogTarget.New = create; .Edit = rename/delete existing chip.
    var dialog by remember { mutableStateOf<NotebookDialogTarget?>(null) }

    NotesContent(
        state = state,
        onSelectNotebook = viewModel::selectNotebook,
        onOpenNote = onOpenNote,
        onTogglePin = viewModel::togglePin,
        onNewNote = { viewModel.createNote(onCreated = onOpenNote) },
        onNewNotebook = { dialog = NotebookDialogTarget.New },
        // Only real notebooks (id != null) are editable; the synthetic "All Notes" chip is not.
        onEditNotebook = { chip -> chip.id?.let { dialog = NotebookDialogTarget.Edit(it, chip.label) } },
    )

    when (val d = dialog) {
        is NotebookDialogTarget.New -> NotebookEditDialog(
            initialName = "",
            isEdit = false,
            onDismiss = { dialog = null },
            onSave = { name, emoji -> viewModel.createNotebook(name, emoji); dialog = null },
            onDelete = null,
        )
        is NotebookDialogTarget.Edit -> NotebookEditDialog(
            initialName = d.name,
            isEdit = true,
            onDismiss = { dialog = null },
            onSave = { name, emoji -> viewModel.renameNotebook(d.id, name, emoji); dialog = null },
            onDelete = { viewModel.deleteNotebook(d.id); dialog = null },
        )
        null -> Unit
    }
}

/** Which notebook dialog (if any) is open. */
private sealed interface NotebookDialogTarget {
    data object New : NotebookDialogTarget
    data class Edit(val id: String, val name: String) : NotebookDialogTarget
}

// ─────────────────────────────────────────────────────────────────────────────
// Stateless content (for previews and reuse)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Stateless Notes screen content.
 *
 * Layout (Midnight Calm spec — full-app.html Notes panel):
 *  - App-background gradient fills the entire [Box].
 *  - [LazyColumn]:
 *      1. "Notes" title (Title type scale, textPrimary)
 *      2. Horizontally-scrolling [FilterChip] notebook chip row
 *      3. If [NotesUiState.isEmpty]: [EmptyState]
 *         Else: PINNED [SectionLabel] + pinned [NoteCard]s (only when non-empty),
 *               RECENT [SectionLabel] + recent [NoteCard]s
 *  - [TmapFab] overlaid bottom-end; a fade scrim above it prevents cards from clipping under it.
 */
@Composable
fun NotesContent(
    state: NotesUiState,
    onSelectNotebook: (String?) -> Unit,
    onOpenNote: (String) -> Unit,
    onTogglePin: (String, Boolean) -> Unit,
    onNewNote: () -> Unit,
    onNewNotebook: () -> Unit = {},
    onEditNotebook: (NotebookChip) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = LocalTmapColors.current
    val spacing = LocalTmapSpacing.current
    val type = LocalTmapType.current

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(colors.bgTop, colors.bgBottom))),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = spacing.screenH,
                end = spacing.screenH,
                top = spacing.xl,
                // Extra bottom padding so last card is not hidden under the FAB + scrim.
                bottom = 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            // ── 1. Screen title ──────────────────────────────────────────────
            item(key = "title") {
                Text(
                    text = "Notes",
                    style = type.title,
                    color = colors.textPrimary,
                    modifier = Modifier.padding(bottom = spacing.sm),
                )
            }

            // ── 2. Notebook chip row ─────────────────────────────────────────
            item(key = "chip-row") {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                    contentPadding = PaddingValues(bottom = spacing.xs),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(state.chips, key = { it.id ?: "__all__" }) { chip ->
                        // Long-press a real notebook chip (id != null) to rename/delete it.
                        // The synthetic "All Notes" chip (id == null) is selection-only.
                        NotebookChipItem(
                            chip = chip,
                            onClick = { onSelectNotebook(chip.id) },
                            onLongClick = if (chip.id != null) {
                                { onEditNotebook(chip) }
                            } else {
                                null
                            },
                        )
                    }
                    // Trailing "+ New notebook" affordance.
                    item(key = "__new_notebook__") {
                        NewNotebookChip(onClick = onNewNotebook)
                    }
                }
            }

            // ── 3a. Empty state ──────────────────────────────────────────────
            if (state.isEmpty) {
                item(key = "empty") {
                    val copy = if (state.selectedGroupId != null) {
                        emptyCopyFor(EmptySurface.NotesGroup)
                    } else {
                        emptyCopyFor(EmptySurface.Notes)
                    }
                    EmptyState(
                        icon = Icons.AutoMirrored.Outlined.StickyNote2,
                        title = copy.title,
                        subtitle = copy.subtitle,
                        actionLabel = copy.actionLabel,
                        onAction = if (copy.actionLabel != null) onNewNote else null,
                        modifier = Modifier.fillParentMaxHeight(0.6f),
                    )
                }
            } else {
                // ── 3b. PINNED section (only when there are pinned notes) ────
                if (state.pinned.isNotEmpty()) {
                    item(key = "label-pinned") {
                        SectionLabel(
                            text = "Pinned",
                            modifier = Modifier.padding(top = spacing.md, bottom = spacing.base),
                        )
                    }
                    items(state.pinned, key = { it.id }) { note ->
                        NoteCard(
                            note = note,
                            onClick = { onOpenNote(note.id) },
                            onTogglePin = { onTogglePin(note.id, note.pinned) },
                        )
                    }
                }

                // ── 3c. RECENT section ───────────────────────────────────────
                item(key = "label-recent") {
                    SectionLabel(
                        text = "Recent",
                        modifier = Modifier.padding(top = spacing.md, bottom = spacing.base),
                    )
                }
                items(state.recent, key = { it.id }) { note ->
                    NoteCard(
                        note = note,
                        onClick = { onOpenNote(note.id) },
                        onTogglePin = { onTogglePin(note.id, note.pinned) },
                    )
                }
            }
        }

        // ── FAB scrim: 72dp tall gradient from transparent → bgBottom ──────────
        // Gives the FAB a soft landing so cards beneath it don't visually clash.
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 0.dp)
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to colors.bgBottom.copy(alpha = 0f),
                            1f to colors.bgBottom.copy(alpha = 0.92f),
                        ),
                    ),
                )
                // Height covers the FAB (56dp) + its bottom padding (20dp) + a little headroom.
                .padding(top = 72.dp),
        )

        // ── FAB ──────────────────────────────────────────────────────────────
        TmapFab(
            onClick = onNewNote,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = spacing.lg, bottom = spacing.xl),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Notebook chip + dialog (notebook management UI)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A selectable notebook chip that mirrors [FilterChip]'s Midnight Calm styling but supports an
 * optional long-press (rename/delete) via [combinedClickable]. The long-press exposes a
 * non-gesture-equivalent a11y action label so screen-reader users can reach edit too.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NotebookChipItem(
    chip: NotebookChip,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    val colors = LocalTmapColors.current
    val shapes = LocalTmapShapes.current
    val type = LocalTmapType.current
    val pill = RoundedCornerShape(shapes.pill)
    Row(
        modifier = Modifier
            .background(
                color = if (chip.selected) colors.surfaceRaised else colors.surfaceInset,
                shape = pill,
            )
            .border(
                width = 1.dp,
                color = if (chip.selected) colors.accent else colors.borderSubtle,
                shape = pill,
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = if (onLongClick != null) "Edit notebook ${chip.label}" else null,
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = chip.label,
            style = type.meta,
            color = if (chip.selected) colors.accent else colors.textSecondary,
        )
    }
}

/** Trailing "+ New notebook" chip; icon-only leading add glyph carries the a11y description. */
@Composable
private fun NewNotebookChip(onClick: () -> Unit) {
    val colors = LocalTmapColors.current
    val shapes = LocalTmapShapes.current
    val type = LocalTmapType.current
    val pill = RoundedCornerShape(shapes.pill)
    Row(
        modifier = Modifier
            .background(color = colors.surfaceInset, shape = pill)
            .border(width = 1.dp, color = colors.borderSubtle, shape = pill)
            .semantics {
                contentDescription = "New notebook"
                role = Role.Button
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Add,
            contentDescription = null,
            tint = colors.textSecondary,
            modifier = Modifier.padding(0.dp),
        )
        Text(
            text = "New notebook",
            style = type.meta,
            color = colors.textSecondary,
        )
    }
}

/**
 * Create/rename/delete dialog for a notebook (note-group). Mirrors [ProjectEditDialog]'s pattern:
 * an [AlertDialog] using tokens only, a name field + emoji picker, Create/Update confirm, optional
 * danger Delete (edit mode), and Cancel. RTL-safe (start/end aware Rows) and a11y-labelled.
 */
@Composable
fun NotebookEditDialog(
    initialName: String,
    isEdit: Boolean,
    onDismiss: () -> Unit,
    onSave: (name: String, emoji: String) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val colors = LocalTmapColors.current
    val type = LocalTmapType.current

    var name by remember { mutableStateOf(initialName) }
    var emoji by remember { mutableStateOf(NOTEBOOK_EMOJIS.first()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceRaised,
        title = {
            Text(
                text = if (isEdit) "Edit Notebook" else "New Notebook",
                style = type.heading,
                color = colors.textPrimary,
            )
        },
        text = {
            androidx.compose.foundation.layout.Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Notebook name", color = colors.textSecondary) },
                    singleLine = true,
                )
                Text(text = "Icon", style = type.label, color = colors.textSecondary)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    NOTEBOOK_EMOJIS.forEach { e ->
                        Text(
                            text = e,
                            fontWeight = if (emoji == e) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier
                                .clickable { emoji = e }
                                .semantics { contentDescription = "Select icon $e" },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name.trim(), emoji) },
                enabled = name.isNotBlank(),
            ) {
                Text(
                    text = if (isEdit) "Update" else "Create",
                    color = colors.accent,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        dismissButton = {
            Row {
                if (isEdit && onDelete != null) {
                    TextButton(
                        onClick = onDelete,
                        modifier = Modifier.semantics { contentDescription = "Delete notebook" },
                    ) {
                        Text(text = "Delete", color = colors.danger)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(text = "Cancel", color = colors.textSecondary)
                }
            }
        },
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Previews
// ─────────────────────────────────────────────────────────────────────────────

@androidx.compose.ui.tooling.preview.Preview(
    showBackground = true,
    backgroundColor = 0xFF141519,
    name = "NotesContent – populated (1 pinned + 2 recent + 4 chips)",
)
@Composable
private fun PreviewNotesContentPopulated() {
    TmapTheme {
        NotesContent(
            state = NotesUiState(
                loading = false,
                chips = listOf(
                    NotebookChip(id = null, label = "All Notes", selected = true),
                    NotebookChip(id = "work", label = "Work", selected = false),
                    NotebookChip(id = "personal", label = "Personal", selected = false),
                    NotebookChip(id = "ideas", label = "Ideas", selected = false),
                ),
                selectedGroupId = null,
                pinned = listOf(
                    NoteCardUi(
                        id = "p1",
                        title = "Q3 strategy brain-dump",
                        snippet = "Three bets for next quarter: double down on onboarding, " +
                            "ship the mobile app, and tighten the weekly planning ritual…",
                        projectColor = 0xFF6EA8FEL,
                        projectName = "Work",
                        updatedAt = Instant.now().minusSeconds(7_200),
                        pinned = true,
                    ),
                ),
                recent = listOf(
                    NoteCardUi(
                        id = "r1",
                        title = "Books to read",
                        snippet = "Thinking in Systems, The Beginning of Infinity, Seeing Like a State…",
                        projectColor = 0xFFC9A0FFL,
                        projectName = "Ideas",
                        updatedAt = Instant.now().minusSeconds(90_000),
                        pinned = false,
                    ),
                    NoteCardUi(
                        id = "r2",
                        title = "Morning pages",
                        snippet = "Slept well. Feeling clear about the week ahead.",
                        projectColor = null,
                        projectName = null,
                        updatedAt = Instant.now().minusSeconds(300),
                        pinned = false,
                    ),
                ),
            ),
            onSelectNotebook = {},
            onOpenNote = {},
            onTogglePin = { _, _ -> },
            onNewNote = {},
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview(
    showBackground = true,
    backgroundColor = 0xFF141519,
    name = "NotesContent – empty state",
)
@Composable
private fun PreviewNotesContentEmpty() {
    TmapTheme {
        NotesContent(
            state = NotesUiState(
                loading = false,
                chips = listOf(
                    NotebookChip(id = null, label = "All Notes", selected = true),
                ),
                selectedGroupId = null,
                pinned = emptyList(),
                recent = emptyList(),
            ),
            onSelectNotebook = {},
            onOpenNote = {},
            onTogglePin = { _, _ -> },
            onNewNote = {},
        )
    }
}
