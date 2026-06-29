package net.qmindtech.tmap.ui.planning.steps

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.qmindtech.tmap.ui.components.EmptyState
import net.qmindtech.tmap.ui.components.ProjectDot
import net.qmindtech.tmap.ui.components.SectionLabel
import net.qmindtech.tmap.ui.planning.PlanItemUi
import net.qmindtech.tmap.ui.planning.PlanProjectGroupUi
import net.qmindtech.tmap.ui.planning.PlanningUiState
import net.qmindtech.tmap.ui.theme.LocalTmapColors
import net.qmindtech.tmap.ui.theme.LocalTmapShapes
import net.qmindtech.tmap.ui.theme.LocalTmapType
import net.qmindtech.tmap.ui.theme.TmapTheme

@Composable
fun PickTodayStep(
    state: PlanningUiState,
    onToggleAdd: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.carryOver.isEmpty() && state.inboxPicks.isEmpty() &&
        state.backlogPicks.isEmpty() && state.everythingElse.isEmpty()
    ) {
        EmptyState(
            icon = Icons.Default.Inbox,
            title = "Nothing to plan yet",
            subtitle = "Capture a task or add some to your backlog.",
            modifier = modifier,
        )
        return
    }

    LazyColumn(modifier = modifier.fillMaxSize()) {
        if (state.carryOver.isNotEmpty()) {
            item {
                SectionLabel(
                    text = "Carry over from yesterday",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
                )
            }
            items(state.carryOver, key = { it.id }) { item ->
                PlanRow(item = item, onToggleAdd = { onToggleAdd(item.id) })
            }
        }

        if (state.inboxPicks.isNotEmpty()) {
            item {
                SectionLabel(
                    text = "From your inbox",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
                )
            }
            items(state.inboxPicks, key = { "inbox_${it.id}" }) { item ->
                PlanRow(item = item, onToggleAdd = { onToggleAdd(item.id) })
            }
        }

        if (state.backlogPicks.isNotEmpty()) {
            item {
                SectionLabel(
                    text = "From your backlog",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
                )
            }
            items(state.backlogPicks, key = { "backlog_${it.id}" }) { item ->
                PlanRow(item = item, onToggleAdd = { onToggleAdd(item.id) })
            }
        }

        state.everythingElse.forEach { group ->
            val groupKey = group.projectId ?: "none"
            item(key = "else_header_$groupKey") {
                ProjectGroupLabel(
                    name = group.projectName ?: "No Project",
                    colorArgb = group.projectColor,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
                )
            }
            items(group.items, key = { "else_${groupKey}_${it.id}" }) { item ->
                PlanRow(item = item, onToggleAdd = { onToggleAdd(item.id) })
            }
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

/**
 * A single pick-today row — an 18dp [surface] card with title + project meta on the start side,
 * and a trailing trailing control on the end side.
 *
 * When [PlanItemUi.added] is true the card dims to alpha=0.62f and the trailing control is a
 * 28dp circular amber-gradient ✓ (`contentDescription = "Added"`).
 * When false the trailing control is an amber-gradient "+ Add" pill
 * (`contentDescription = "Add to today"`).
 * Both controls call [onToggleAdd] — non-gesture toggle required by a11y §9.
 */
@Composable
fun PlanRow(item: PlanItemUi, onToggleAdd: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalTmapColors.current
    val shapes = LocalTmapShapes.current
    val type = LocalTmapType.current
    val accentBrush = Brush.linearGradient(listOf(colors.accent, colors.accentEnd))

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 5.dp)
            .alpha(if (item.added) 0.62f else 1f)
            .background(color = colors.surface, shape = RoundedCornerShape(shapes.card))
            .padding(start = 14.dp, end = 10.dp, top = 13.dp, bottom = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // Start side: title + optional project meta
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = item.title,
                style = type.body,
                color = colors.textPrimary,
            )
            if (!item.projectName.isNullOrBlank() || item.projectColor != null || item.hint != null) {
                Row(
                    modifier = Modifier.padding(top = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    if (item.projectColor != null) {
                        ProjectDot(colorArgb = item.projectColor)
                    }
                    if (!item.projectName.isNullOrBlank()) {
                        Text(
                            text = item.projectName,
                            style = type.meta,
                            color = colors.textSecondary,
                        )
                    }
                    // "Everything else" rows carry a locator hint (e.g. "Planned · Jun 30"); show it
                    // in a subtler tone, prefixed with a dot only when a project label precedes it.
                    if (item.hint != null) {
                        Text(
                            text = if (!item.projectName.isNullOrBlank()) "· ${item.hint}" else item.hint,
                            style = type.meta,
                            color = colors.textTertiary,
                        )
                    }
                }
            }
        }

        // End side: circular ✓ (added) or "+ Add" pill
        if (item.added) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(brush = accentBrush)
                    .clickable(role = Role.Button, onClick = onToggleAdd)
                    .semantics { contentDescription = "Added" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = colors.onAccent,
                    modifier = Modifier.size(16.dp),
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(shapes.pill))
                    .background(brush = accentBrush)
                    .clickable(role = Role.Button, onClick = onToggleAdd)
                    .semantics { contentDescription = "Add to today" }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "+ Add",
                    style = type.meta,
                    color = colors.onAccent,
                )
            }
        }
    }
}

/**
 * Section header for an "Everything else" project group: an optional [colorArgb] dot followed by the
 * uppercase project [name] (via [SectionLabel]). The "No Project" group passes [colorArgb] = null
 * and renders dot-less.
 */
@Composable
private fun ProjectGroupLabel(name: String, colorArgb: Long?, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (colorArgb != null) {
            ProjectDot(colorArgb = colorArgb)
        }
        SectionLabel(text = name)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF191A20)
@Composable
private fun PickTodayStepPreview() {
    TmapTheme {
        PickTodayStep(
            state = PlanningUiState(
                loading = false,
                carryOver = listOf(
                    PlanItemUi(
                        id = "1", title = "Review pull requests",
                        projectName = "Work", projectColor = 0xFF6EA8FE,
                        durationMinutes = 45, added = false,
                    ),
                    PlanItemUi(
                        id = "2", title = "Write spec doc",
                        projectName = "Work", projectColor = 0xFF6EA8FE,
                        durationMinutes = 60, added = true,
                    ),
                ),
                inboxPicks = listOf(
                    PlanItemUi(
                        id = "3", title = "Book dentist appointment",
                        projectName = "Health", projectColor = 0xFFF0A868,
                        durationMinutes = null, added = false,
                    ),
                ),
                backlogPicks = listOf(
                    PlanItemUi(
                        id = "4", title = "Plan Q3 roadmap",
                        projectName = "Work", projectColor = 0xFF6EA8FE,
                        durationMinutes = 90, added = false,
                    ),
                ),
                everythingElse = listOf(
                    PlanProjectGroupUi(
                        projectId = "work", projectName = "Work", projectColor = 0xFF6EA8FE,
                        items = listOf(
                            PlanItemUi(
                                id = "5", title = "Prep onboarding deck",
                                projectName = "Work", projectColor = 0xFF6EA8FE,
                                durationMinutes = 30, added = false, hint = "Planned · Jun 30",
                            ),
                        ),
                    ),
                    PlanProjectGroupUi(
                        projectId = null, projectName = null, projectColor = null,
                        items = listOf(
                            PlanItemUi(
                                id = "6", title = "1:1 with Sam",
                                projectName = null, projectColor = null,
                                durationMinutes = 30, added = false, hint = "Scheduled · Jul 2",
                            ),
                        ),
                    ),
                ),
                pickedIds = listOf("2"),
            ),
            onToggleAdd = {},
        )
    }
}
