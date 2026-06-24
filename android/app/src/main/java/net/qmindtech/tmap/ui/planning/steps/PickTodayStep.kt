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
import net.qmindtech.tmap.ui.components.ProjectDot
import net.qmindtech.tmap.ui.components.SectionLabel
import net.qmindtech.tmap.ui.planning.PlanItemUi
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
            if (!item.projectName.isNullOrBlank() || item.projectColor != null) {
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
                pickedIds = listOf("2"),
            ),
            onToggleAdd = {},
        )
    }
}
