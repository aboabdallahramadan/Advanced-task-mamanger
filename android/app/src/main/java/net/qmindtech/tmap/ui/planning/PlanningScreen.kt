package net.qmindtech.tmap.ui.planning

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.qmindtech.tmap.ui.planning.steps.CapacityDock
import net.qmindtech.tmap.ui.planning.steps.PickTodayStep
import net.qmindtech.tmap.ui.planning.steps.ReflectStep
import net.qmindtech.tmap.ui.planning.steps.TimeboxStep
import net.qmindtech.tmap.ui.planning.steps.TriageInboxStep
import net.qmindtech.tmap.ui.theme.LocalTmapColors
import net.qmindtech.tmap.ui.theme.LocalTmapType
import net.qmindtech.tmap.ui.theme.TmapBackground
import net.qmindtech.tmap.ui.theme.TmapTheme

/**
 * Stateful entry point for the full-screen planning ritual. Collects [PlanningViewModel.uiState]
 * and delegates rendering + callbacks to [PlanningContent].
 *
 * Used by the nav graph at [net.qmindtech.tmap.ui.navigation.Route.Planning].
 *
 * @param onClose Back out of the ritual (pop to Today) — used on first step or via the ✕ close.
 * @param viewModel Hilt-injected [PlanningViewModel].
 */
@Composable
fun PlanningScreen(
    onClose: () -> Unit,
    viewModel: PlanningViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    PlanningContent(
        state = state,
        onBack = { if (state.isFirstStep) onClose() else viewModel.back() },
        onNext = viewModel::next,
        onCommit = { viewModel.commit(onDone = onClose) },
        onToggleAdd = viewModel::toggleAdd,
        onSchedule = viewModel::scheduleFromInbox,
        onBacklog = viewModel::sendToBacklog,
        onDelete = viewModel::deleteTask,
    )
}

/**
 * Stateless planning ritual host — used by [PlanningScreen] and previews.
 *
 * Layout:
 *  - [TmapBackground] (Midnight Calm vertical gradient) fills the screen.
 *  - Stepper header: back chevron [IconButton] + progress dots row; amber eyebrow + big heading.
 *  - Body: the current step's composable in a [Modifier.weight(1f)] region.
 *  - Bottom: [CapacityDock] pinned at the bottom; label reads "Plan my day →" on the final step.
 */
@Composable
fun PlanningContent(
    state: PlanningUiState,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onCommit: () -> Unit,
    onToggleAdd: (String) -> Unit,
    onSchedule: (String) -> Unit,
    onBacklog: (String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalTmapColors.current
    val type = LocalTmapType.current

    TmapBackground(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── Stepper header ───────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 20.dp, top = 8.dp, bottom = 0.dp),
            ) {
                // Back chevron + progress dots
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Back",
                            tint = colors.textSecondary,
                            modifier = Modifier.size(20.dp),
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // Progress dots — one per step; amber = active, textTertiary = past, borderSubtle = future
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        repeat(PlanningStep.STEP_COUNT) { index ->
                            val stepOrdinal = state.step.ordinal
                            val dotColor = when {
                                index == stepOrdinal -> colors.accent
                                index < stepOrdinal -> colors.textTertiary
                                else -> colors.borderSubtle
                            }
                            val dotSize = if (index == stepOrdinal) 8.dp else 6.dp
                            Box(
                                modifier = Modifier
                                    .size(dotSize)
                                    .clip(CircleShape)
                                    .background(dotColor),
                            )
                            if (index < PlanningStep.STEP_COUNT - 1) {
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                        }
                    }
                }

                // Amber eyebrow (label style, uppercase)
                Text(
                    text = state.step.eyebrow,
                    style = type.label,
                    color = colors.accent,
                    modifier = Modifier.padding(start = 20.dp, top = 10.dp, bottom = 4.dp),
                )

                // Big heading
                Text(
                    text = state.step.heading,
                    style = type.heading,
                    color = colors.textPrimary,
                    modifier = Modifier.padding(start = 20.dp, bottom = 8.dp),
                )
            }

            // ── Step body (scrollable, weight fills available space) ─────────
            Box(modifier = Modifier.weight(1f)) {
                when (state.step) {
                    PlanningStep.Reflect -> ReflectStep(
                        state = state,
                        modifier = Modifier.fillMaxSize(),
                    )
                    PlanningStep.TriageInbox -> TriageInboxStep(
                        state = state,
                        onSchedule = onSchedule,
                        onBacklog = onBacklog,
                        onDelete = onDelete,
                        modifier = Modifier.fillMaxSize(),
                    )
                    PlanningStep.PickToday -> PickTodayStep(
                        state = state,
                        onToggleAdd = onToggleAdd,
                        modifier = Modifier.fillMaxSize(),
                    )
                    PlanningStep.Timebox -> TimeboxStep(
                        state = state,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            // ── Capacity dock pinned at bottom ───────────────────────────────
            CapacityDock(
                plannedMinutes = state.plannedMinutes,
                workdayMinutes = state.workdayMinutes,
                fraction = state.capacityFraction,
                continueLabel = if (state.isLastStep) "Plan my day →" else "Continue →",
                onContinue = { if (state.isLastStep) onCommit() else onNext() },
            )
        }
    }
}

// ── Previews ─────────────────────────────────────────────────────────────────

@Preview(showBackground = true, backgroundColor = 0xFF191A20, name = "Planning – PickToday step")
@Composable
private fun PlanningContentPickTodayPreview() {
    TmapTheme {
        PlanningContent(
            state = PlanningUiState(
                loading = false,
                step = PlanningStep.PickToday,
                carryOver = listOf(
                    PlanItemUi(
                        id = "1",
                        title = "Review pull requests",
                        projectName = "Work",
                        projectColor = 0xFF6EA8FE,
                        durationMinutes = 45,
                        added = true,
                    ),
                ),
                inboxPicks = listOf(
                    PlanItemUi(
                        id = "2",
                        title = "Book dentist appointment",
                        projectName = "Health",
                        projectColor = 0xFFF0A868,
                        durationMinutes = null,
                        added = false,
                    ),
                ),
                pickedIds = listOf("1"),
                plannedMinutes = 45,
                workdayMinutes = 360,
            ),
            onBack = {},
            onNext = {},
            onCommit = {},
            onToggleAdd = {},
            onSchedule = {},
            onBacklog = {},
            onDelete = {},
        )
    }
}
