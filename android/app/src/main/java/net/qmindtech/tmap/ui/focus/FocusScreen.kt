package net.qmindtech.tmap.ui.focus

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.qmindtech.tmap.ui.components.EmptyState
import net.qmindtech.tmap.ui.components.EmptySurface
import net.qmindtech.tmap.ui.components.emptyCopyFor
import net.qmindtech.tmap.ui.components.ProgressRing
import net.qmindtech.tmap.ui.theme.LocalTmapColors
import net.qmindtech.tmap.ui.theme.LocalTmapSpacing
import net.qmindtech.tmap.ui.theme.LocalTmapType

/**
 * Immersive focus mode screen (spec §6.5 / task P6.7).
 *
 * Full-screen destination reached via [net.qmindtech.tmap.ui.navigation.Route.Focus]. Renders the
 * amber [ProgressRing] (P0.11) with the mm:ss / "of mm:ss" center label, the bound task title +
 * project dot, session dots row, and the three icon controls (✓ mark-done, ❚❚/▶ pause/resume, ✕ end).
 *
 * The foreground [FocusService] is started/stopped by the NavHost destination's [DisposableEffect]
 * in [net.qmindtech.tmap.ui.navigation.MainScaffold], not here, so the timer survives backgrounding
 * independently of the screen lifecycle.
 *
 * RTL: uses [Arrangement.spacedBy] (symmetric); no left/right hard-coded offsets.
 * A11y: all icon-only controls carry a [contentDescription].
 */
@Composable
fun FocusScreen(
    taskId: String?,
    onExit: () -> Unit,
    viewModel: FocusViewModel = hiltViewModel(),
) {
    val colors = LocalTmapColors.current
    val type = LocalTmapType.current
    val spacing = LocalTmapSpacing.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Begin the interval once on first entry (when the controller is idle). The project name is
    // resolved lazily from the controller snapshot; default to 25-minute pomodoro.
    LaunchedEffect(taskId) {
        if (state.phase == FocusPhase.Idle) {
            viewModel.start(
                taskId = taskId,
                project = state.project.ifBlank { "Focus" },
                lengthMin = 25,
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(colors.focusBgTop, colors.focusBgBottom)),
            ),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = spacing.xl, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Status-bar spacer so content starts below the system bar.
            Spacer(Modifier.height(34.dp))

            // ── Eyebrow ──────────────────────────────────────────────────────
            Text(
                text = "FOCUSING ON",
                style = type.label,
                color = colors.accent,
            )

            Spacer(Modifier.height(12.dp))

            // ── Task title ───────────────────────────────────────────────────
            Text(
                text = state.taskTitle ?: state.project.ifBlank { "Focus session" },
                style = type.heading,
                color = colors.textPrimary,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(8.dp))

            // ── Project dot + name ───────────────────────────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(colors.accent),
                )
                Text(
                    text = state.project,
                    style = type.body,
                    color = colors.textSecondary,
                )
            }

            Spacer(Modifier.height(30.dp))

            // ── Amber progress ring ──────────────────────────────────────────
            ProgressRing(
                progress = state.progress,
                modifier = Modifier.size(190.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = state.remainingLabel,
                        style = type.display.copy(fontSize = type.display.fontSize * 0.95f),
                        color = colors.textPrimary,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = state.ofLabel,
                        style = type.meta,
                        color = colors.textTertiary,
                    )
                }
            }

            Spacer(Modifier.height(26.dp))

            // ── Session dots ─────────────────────────────────────────────────
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(state.totalSessions) { i ->
                    Box(
                        Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(
                                if (i < state.completedSessions) colors.accent
                                else colors.surfaceRaised,
                            ),
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            Text(
                text = "Session ${state.completedSessions + 1} of ${state.totalSessions}",
                style = type.meta,
                color = colors.textTertiary,
            )

            Spacer(Modifier.weight(1f))

            // ── Controls row: ✓ · ❚❚/▶ · ✕ ──────────────────────────────────
            Row(
                horizontalArrangement = Arrangement.spacedBy(22.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Mark-done (✓)
                Box(
                    modifier = Modifier
                        .size(50.dp)
                        .clip(CircleShape)
                        .background(colors.surfaceRaised)
                        .border(1.dp, colors.borderStrong, CircleShape)
                        .clickable { viewModel.markDone(); onExit() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = "Mark task done",
                        tint = colors.textSecondary,
                    )
                }

                // Primary pause/resume toggle — larger amber circle.
                Box(
                    modifier = Modifier
                        .size(62.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(listOf(colors.accent, colors.accentEnd)),
                        )
                        .clickable {
                            if (state.phase == FocusPhase.Paused) viewModel.resume()
                            else viewModel.pause()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (state.phase == FocusPhase.Paused) Icons.Filled.PlayArrow
                        else Icons.Filled.Pause,
                        contentDescription = if (state.phase == FocusPhase.Paused) "Resume" else "Pause",
                        tint = colors.onAccent,
                    )
                }

                // End (✕)
                Box(
                    modifier = Modifier
                        .size(50.dp)
                        .clip(CircleShape)
                        .background(colors.surfaceRaised)
                        .border(1.dp, colors.borderStrong, CircleShape)
                        .clickable { viewModel.end(); onExit() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "End focus",
                        tint = colors.textSecondary,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // ── Queued-tasks hint (only when > 0) ───────────────────────────
            if (state.queuedCount > 0) {
                Text(
                    text = "${state.queuedCount} more tasks queued for this session",
                    style = type.meta,
                    color = colors.textTertiary,
                    textAlign = TextAlign.Center,
                )
            } else if (state.taskTitle == null) {
                val copy = emptyCopyFor(EmptySurface.FocusQueue)
                EmptyState(
                    icon = Icons.Filled.PlayArrow,
                    title = copy.title,
                    subtitle = copy.subtitle,
                )
            }
        }
    }
}
