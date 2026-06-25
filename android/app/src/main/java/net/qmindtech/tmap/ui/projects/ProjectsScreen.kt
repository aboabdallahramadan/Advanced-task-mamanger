package net.qmindtech.tmap.ui.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.qmindtech.tmap.ui.components.EmptyState
import net.qmindtech.tmap.ui.components.EmptySurface
import net.qmindtech.tmap.ui.components.emptyCopyFor
import net.qmindtech.tmap.ui.components.ProjectDot
import net.qmindtech.tmap.ui.components.SecondaryButton
import net.qmindtech.tmap.ui.components.parseProjectColor
import net.qmindtech.tmap.ui.theme.LocalTmapColors
import net.qmindtech.tmap.ui.theme.LocalTmapShapes
import net.qmindtech.tmap.ui.theme.LocalTmapSpacing
import net.qmindtech.tmap.ui.theme.LocalTmapType

/**
 * Midnight Calm project cards — Scaffold-free content composable.
 *
 * No inner Scaffold / TopAppBar / FloatingActionButton.
 * The global [net.qmindtech.tmap.ui.components.TmapFab] lives in MainScaffold.
 * An inline "+ New" [SecondaryButton] is shown in the header row.
 */
@Composable
fun ProjectsScreen(
    onOpenProject: (projectId: String) -> Unit,
    viewModel: ProjectsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = LocalTmapColors.current
    val shapes = LocalTmapShapes.current
    val spacing = LocalTmapSpacing.current
    val type = LocalTmapType.current
    var creating by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── Header: "N projects · done/total" + "+ New" button ─────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = spacing.sm),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${state.header.projectCount} projects · " +
                    "${state.header.doneTotal}/${state.header.taskTotal} done",
                style = type.meta,
                color = colors.textSecondary,
            )
            SecondaryButton(
                text = "+ New",
                onClick = { creating = true },
                modifier = Modifier.semantics { contentDescription = "New project" },
            )
        }

        // ── Content: empty state or card list ───────────────────────────────────
        if (!state.loading && state.rows.isEmpty()) {
            val copy = emptyCopyFor(EmptySurface.Projects)
            EmptyState(
                icon = Icons.Filled.Folder,
                title = copy.title,
                subtitle = copy.subtitle,
                actionLabel = copy.actionLabel,
                onAction = { creating = true },
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(state.rows, key = { it.project.id }) { row ->
                    val colorArgb: Long =
                        parseProjectColor(row.project.color) ?: 0xFF6EA8FEL

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                onClickLabel = "Open ${row.project.name}",
                                onClick = { onOpenProject(row.project.id) },
                            )
                            .background(colors.surface, RoundedCornerShape(shapes.card))
                            .padding(14.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                            // ── Top row: dot · emoji · name · open count ────────
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                            ) {
                                ProjectDot(colorArgb = colorArgb, size = 10.dp)
                                if (row.project.emoji.isNotBlank()) {
                                    Text(text = row.project.emoji)
                                }
                                Text(
                                    text = row.project.name,
                                    modifier = Modifier.weight(1f),
                                    style = type.body,
                                    color = colors.textPrimary,
                                )
                                Text(
                                    text = "${row.openTaskCount} open",
                                    style = type.label,
                                    color = colors.textTertiary,
                                )
                            }

                            // ── Progress bar in the project's color ─────────────
                            LinearProgressIndicator(
                                progress = row.progress,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 2.dp),
                                color = Color(colorArgb),
                                trackColor = colors.surfaceInset,
                            )

                            // ── done/total meta ──────────────────────────────────
                            Text(
                                text = "${row.done}/${row.total}",
                                style = type.meta,
                                color = colors.textTertiary,
                            )
                        }
                    }
                }
            }
        }
    }

    // ── Dialogs ──────────────────────────────────────────────────────────────
    // Edit/delete are now reachable via ProjectDetailScreen; only "create new" lives here.
    if (creating) {
        ProjectEditDialog(
            initial = null,
            onDismiss = { creating = false },
            onSave = { name, color, emoji ->
                viewModel.create(name, color, emoji)
                creating = false
            },
        )
    }
}
