package net.qmindtech.tmap.ui.today

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
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import net.qmindtech.tmap.ui.components.EmptyState
import net.qmindtech.tmap.ui.components.PrimaryButton
import net.qmindtech.tmap.ui.components.ProgressRing
import net.qmindtech.tmap.ui.components.SecondaryButton
import net.qmindtech.tmap.ui.components.SegmentedControl
import net.qmindtech.tmap.ui.theme.LocalTmapColors
import net.qmindtech.tmap.ui.theme.LocalTmapSpacing
import net.qmindtech.tmap.ui.theme.LocalTmapType

/**
 * Today screen — rebuilt to Midnight Calm spec (daily-core.html ①).
 *
 * Layout:
 *  - Header: date eyebrow (amber uppercase) + List/Timeline [SegmentedControl] inline;
 *            greeting (title size); progress bar + "X of Y · Nh left" label;
 *            "Plan my day" (primary) + "Focus" (secondary) action buttons.
 *  - Body: [TodayListContent] (List mode) or Timeline placeholder (Timeline mode, P7).
 *  - Undo snackbar on complete.
 *
 * Note: the [TmapFab] lives in [MainScaffold]'s outer scaffold (it's present on all primary tabs),
 * so [TodayScreen] does not add a second FAB. [onOpenCapture] is received so it can be forwarded
 * if future phases need a screen-level capture entry point.
 */
@Composable
fun TodayScreen(
    onOpenTask: (taskId: String) -> Unit,
    onOpenCapture: () -> Unit,
    onPlanMyDay: () -> Unit,
    onFocus: () -> Unit,
    viewModel: TodayViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = LocalTmapColors.current
    val spacing = LocalTmapSpacing.current
    val type = LocalTmapType.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(hostState = snackbar) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // ── Header ──────────────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.screenH, vertical = spacing.base),
            ) {
                // Row 1: date eyebrow + List/Timeline toggle.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = state.dateEyebrow,
                        style = type.label,
                        color = colors.accent,
                    )
                    SegmentedControl(
                        options = listOf("List", "Timeline"),
                        selectedIndex = if (state.mode == TodayMode.List) 0 else 1,
                        onSelect = { idx ->
                            viewModel.setMode(if (idx == 0) TodayMode.List else TodayMode.Timeline)
                        },
                    )
                }

                Spacer(Modifier.height(spacing.md))

                // Greeting.
                Text(
                    text = state.greeting,
                    style = type.title,
                    color = colors.textPrimary,
                )

                Spacer(Modifier.height(spacing.md))

                // Progress ring + label.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ProgressRing(
                        progress = state.progress.fraction,
                        modifier = Modifier.size(44.dp),
                    ) {
                        // Center label inside the ring: compact done/total.
                        Text(
                            text = "${state.progress.done}/${state.progress.total}",
                            style = type.meta,
                            color = colors.textSecondary,
                        )
                    }
                    Spacer(Modifier.width(spacing.sm))
                    val hoursLeft = state.progress.minutesLeft / 60
                    val minsLeft = state.progress.minutesLeft % 60
                    val timeLabel = if (hoursLeft > 0) "${hoursLeft}h left" else "${minsLeft}m left"
                    Text(
                        text = "${state.progress.done} of ${state.progress.total} · $timeLabel",
                        style = type.meta,
                        color = colors.textSecondary,
                    )
                }

                Spacer(Modifier.height(spacing.md))

                // Action buttons: Plan my day + Focus.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                ) {
                    PrimaryButton(
                        text = "Plan my day",
                        onClick = onPlanMyDay,
                        modifier = Modifier.weight(1f),
                    )
                    SecondaryButton(
                        text = "Focus",
                        onClick = onFocus,
                    )
                }
            }

            // ── Body ────────────────────────────────────────────────────────
            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    state.loading -> Unit // loading skeleton deferred to P8

                    state.mode == TodayMode.Timeline ->
                        EmptyState(
                            icon = Icons.Outlined.CalendarToday,
                            title = "Timeline coming soon",
                            subtitle = "Switch to List to see today's tasks.",
                            modifier = Modifier.padding(top = spacing.xxl),
                        )

                    state.groups.isEmpty() ->
                        EmptyState(
                            icon = Icons.AutoMirrored.Outlined.ViewList,
                            title = "Nothing planned today",
                            subtitle = "Tap + to capture, or Plan my day.",
                            modifier = Modifier.padding(top = spacing.xxl),
                        )

                    else -> TodayListContent(
                        groups = state.groups,
                        onToggleComplete = { id ->
                            viewModel.toggleComplete(id)
                            scope.launch {
                                val result = snackbar.showSnackbar(
                                    message = "Task completed",
                                    actionLabel = "Undo",
                                    duration = SnackbarDuration.Short,
                                )
                                if (result == SnackbarResult.ActionPerformed) {
                                    viewModel.toggleComplete(id)
                                }
                            }
                        },
                        onDefer = viewModel::defer,
                        onDelete = viewModel::delete,
                        onClick = onOpenTask,
                        onReorder = viewModel::reorder,
                    )
                }
            }
        }
    }
}
