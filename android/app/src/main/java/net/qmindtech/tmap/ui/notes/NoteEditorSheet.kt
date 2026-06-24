package net.qmindtech.tmap.ui.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.qmindtech.tmap.data.local.entities.NoteGroupEntity
import net.qmindtech.tmap.data.local.entities.ProjectEntity
import net.qmindtech.tmap.ui.components.FilterChip
import net.qmindtech.tmap.ui.components.PrimaryButton
import net.qmindtech.tmap.ui.components.ProjectDot
import net.qmindtech.tmap.ui.components.SheetScaffold
import net.qmindtech.tmap.ui.theme.LocalTmapColors
import net.qmindtech.tmap.ui.theme.LocalTmapSpacing
import net.qmindtech.tmap.ui.theme.LocalTmapType
import net.qmindtech.tmap.ui.theme.TmapTheme
import java.time.Instant

// ─────────────────────────────────────────────────────────────────────────────
// Stateful entry point (wired in SheetHost / NavGraph in P4.7)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Bottom-sheet note editor. Wraps [SheetScaffold] with title + content fields,
 * notebook + project assignment, pin toggle (edit mode only), and Save/Delete actions.
 *
 * On dismiss, calls [NoteEditorViewModel.discardIfEmpty] so eagerly-created blank
 * notes are not left behind in the list or sync queue.
 */
@Composable
fun NoteEditorSheet(
    noteId: String?,
    onClose: () -> Unit,
    viewModel: NoteEditorViewModel = hiltViewModel(),
) {
    // When opened as a sheet the ViewModel's SavedStateHandle has no noteId injected by the
    // nav graph, so we drive loading imperatively.  load() is idempotent when called with the
    // same id and the state is already populated.
    LaunchedEffect(noteId) { viewModel.load(noteId) }

    val state by viewModel.uiState.collectAsStateWithLifecycle()

    fun dismiss() {
        viewModel.discardIfEmpty()
        onClose()
    }

    NoteEditorContent(
        state = state,
        onDismiss = ::dismiss,
        onTitleChange = viewModel::onTitleChange,
        onContentChange = viewModel::onContentChange,
        onGroupChange = viewModel::onGroupChange,
        onProjectChange = viewModel::onProjectChange,
        onTogglePin = viewModel::togglePin,
        onSave = { viewModel.save(::dismiss) },
        onDelete = { viewModel.delete(::dismiss) },
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Stateless content (used for Preview)
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NoteEditorContent(
    state: NoteEditorUiState,
    onDismiss: () -> Unit,
    onTitleChange: (String) -> Unit,
    onContentChange: (String) -> Unit,
    onGroupChange: (String?) -> Unit,
    onProjectChange: (String?) -> Unit,
    onTogglePin: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = LocalTmapColors.current
    val type = LocalTmapType.current
    val spacing = LocalTmapSpacing.current

    val textFieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = colors.accent,
        unfocusedBorderColor = colors.borderSubtle,
        cursorColor = colors.accent,
        focusedLabelColor = colors.accent,
        unfocusedLabelColor = colors.textSecondary,
        focusedTextColor = colors.textPrimary,
        unfocusedTextColor = colors.textBody,
    )

    SheetScaffold(
        onDismiss = onDismiss,
        title = if (state.isEdit) "Edit Note" else "New Note",
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            // ── Title ───────────────────────────────────────────────────────
            OutlinedTextField(
                value = state.title,
                onValueChange = onTitleChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Title") },
                singleLine = true,
                colors = textFieldColors,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )

            // ── Content (editorial writing space) ──────────────────────────
            OutlinedTextField(
                value = state.content,
                onValueChange = onContentChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Content") },
                minLines = 4,
                maxLines = 12,
                colors = textFieldColors,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            )

            // ── Notebook (groupId) selector ─────────────────────────────────
            NoteEditorSectionLabel(text = "Notebook", colors = colors, type = type)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                verticalArrangement = Arrangement.spacedBy(spacing.base),
            ) {
                FilterChip(
                    label = "No notebook",
                    selected = state.groupId == null,
                    onClick = { onGroupChange(null) },
                )
                state.groups.forEach { g ->
                    FilterChip(
                        label = "${g.emoji} ${g.name}",
                        selected = state.groupId == g.id,
                        onClick = { onGroupChange(g.id) },
                    )
                }
            }

            // ── Project selector ────────────────────────────────────────────
            NoteEditorSectionLabel(text = "Project", colors = colors, type = type)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                verticalArrangement = Arrangement.spacedBy(spacing.base),
            ) {
                FilterChip(
                    label = "No project",
                    selected = state.projectId == null,
                    onClick = { onProjectChange(null) },
                )
                state.projects.forEach { p ->
                    val colorHex = p.color
                    val colorArgb = runCatching {
                        val hex = colorHex.trim().removePrefix("#")
                        0xFF000000L or hex.toLong(16)
                    }.getOrNull()
                    // FilterChip has no leadingContent — use a Row with ProjectDot + FilterChip
                    if (colorArgb != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            ProjectDot(colorArgb = colorArgb)
                            FilterChip(
                                label = p.name,
                                selected = state.projectId == p.id,
                                onClick = { onProjectChange(p.id) },
                            )
                        }
                    } else {
                        FilterChip(
                            label = p.name,
                            selected = state.projectId == p.id,
                            onClick = { onProjectChange(p.id) },
                        )
                    }
                }
            }

            // ── Pin toggle (edit mode only) ──────────────────────────────────
            if (state.isEdit) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                ) {
                    IconButton(
                        onClick = onTogglePin,
                        modifier = Modifier.semantics {
                            contentDescription = if (state.pinned) "Unpin note" else "Pin note"
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PushPin,
                            contentDescription = null,
                            tint = if (state.pinned) colors.accent else colors.textTertiary,
                        )
                    }
                    Text(
                        text = if (state.pinned) "Pinned" else "Pin note",
                        style = type.body,
                        color = if (state.pinned) colors.textPrimary else colors.textSecondary,
                    )
                }
            }

            Spacer(modifier = Modifier.height(spacing.sm))

            // ── Delete (edit mode only) ──────────────────────────────────────
            if (state.isEdit) {
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.semantics { contentDescription = "Delete note" },
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = null,
                        tint = colors.danger,
                    )
                }
            }

            // ── Save button ──────────────────────────────────────────────────
            PrimaryButton(
                text = if (state.isEdit) "Update" else "Save",
                onClick = onSave,
                enabled = state.title.isNotBlank() || state.content.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = if (state.isEdit) "Update note" else "Save note"
                    },
            )

            Spacer(modifier = Modifier.height(spacing.base))
        }
    }
}

// ── Private helpers ───────────────────────────────────────────────────────────

@Composable
private fun NoteEditorSectionLabel(
    text: String,
    colors: net.qmindtech.tmap.ui.theme.TmapColorScheme,
    type: net.qmindtech.tmap.ui.theme.TmapType,
) {
    Text(
        text = text.uppercase(),
        style = type.label,
        color = colors.textTertiary,
    )
}

// ── Preview ───────────────────────────────────────────────────────────────────

@Preview(showBackground = true, backgroundColor = 0xFF23242B, name = "NoteEditorContent – edit mode")
@Composable
private fun PreviewNoteEditorContent() {
    TmapTheme {
        NoteEditorContent(
            state = NoteEditorUiState(
                noteId = "n1",
                isEdit = true,
                loading = false,
                title = "Q3 Strategy",
                content = "Three bets for next quarter...",
                groupId = "g1",
                projectId = "p1",
                pinned = true,
                groups = listOf(
                    NoteGroupEntity(
                        id = "g1", name = "Work", emoji = "💼", projectId = null,
                        rank = null, createdAt = Instant.now(), updatedAt = Instant.now(),
                        changeSeq = 0, deletedAt = null,
                    ),
                ),
                projects = listOf(
                    ProjectEntity(
                        id = "p1", name = "Work", color = "#6EA8FE", emoji = "📁",
                        rank = null, actualTimeMinutes = 0,
                        createdAt = Instant.now(), updatedAt = Instant.now(), changeSeq = 0,
                    ),
                ),
            ),
            onDismiss = {},
            onTitleChange = {},
            onContentChange = {},
            onGroupChange = {},
            onProjectChange = {},
            onTogglePin = {},
            onSave = {},
            onDelete = {},
        )
    }
}
