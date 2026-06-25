package net.qmindtech.tmap.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.qmindtech.tmap.data.sync.SyncStatus
import net.qmindtech.tmap.ui.components.PrimaryButton
import net.qmindtech.tmap.ui.components.SyncStatusPill
import net.qmindtech.tmap.ui.theme.LocalTmapColors
import net.qmindtech.tmap.ui.theme.LocalTmapShapes
import net.qmindtech.tmap.ui.theme.LocalTmapSpacing
import net.qmindtech.tmap.ui.theme.LocalTmapType
import net.qmindtech.tmap.ui.theme.TmapBackground
import net.qmindtech.tmap.ui.you.YouViewModel

/** Formats workday minutes as "Xh Ym" (e.g. "6h 0m", "7h 30m"). */
private fun formatWorkdayLabel(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return "${h}h ${m}m"
}

@Composable
fun DataSyncSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val colors = LocalTmapColors.current
    val shapes = LocalTmapShapes.current
    val spacing = LocalTmapSpacing.current
    val type = LocalTmapType.current

    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val youVm: YouViewModel = hiltViewModel()
    val youState by youVm.uiState.collectAsStateWithLifecycle()

    TmapBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.screenH),
        ) {
            Spacer(Modifier.height(spacing.lg))

            // Back header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onBack)
                    .semantics { contentDescription = "Back" }
                    .padding(vertical = spacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    tint = colors.textPrimary,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    text = "Data & Sync",
                    style = type.heading,
                    color = colors.textPrimary,
                )
            }

            Spacer(Modifier.height(spacing.lg))

            // Sync status card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surface, RoundedCornerShape(shapes.card))
                    .border(1.dp, colors.borderSubtle, RoundedCornerShape(shapes.card))
                    .padding(horizontal = spacing.lg, vertical = spacing.md),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Sync status",
                        style = type.body.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.textPrimary,
                    )
                    SyncStatusPill(
                        status = youState.syncStatus,
                        pendingCount = youState.pendingCount,
                        onRetry = youVm::onRetrySync,
                    )
                }
                val lastSyncLabel: String = when (val s = youState.syncStatus) {
                    is SyncStatus.Idle -> if (youState.pendingCount == 0) "Up to date" else "—"
                    is SyncStatus.Syncing -> "Syncing…"
                    is SyncStatus.Offline -> "Offline"
                    is SyncStatus.Error -> s.message
                }
                Text(
                    text = "Last sync: $lastSyncLabel",
                    style = type.meta,
                    color = colors.textSecondary,
                )
            }

            Spacer(Modifier.height(spacing.lg))

            // Force sync button
            PrimaryButton(
                text = "Force sync now",
                onClick = viewModel::forceSync,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(spacing.xl))

            // Workday capacity stepper
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surface, RoundedCornerShape(shapes.card))
                    .border(1.dp, colors.borderSubtle, RoundedCornerShape(shapes.card))
                    .padding(horizontal = spacing.lg, vertical = spacing.md),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                Text(
                    text = "Workday capacity",
                    style = type.body.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.textPrimary,
                )
                Text(
                    text = "Used to plan your day's capacity.",
                    style = type.meta,
                    color = colors.textSecondary,
                )
                HorizontalDivider(color = colors.borderSubtle, thickness = 1.dp)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = formatWorkdayLabel(state.workdayMinutes),
                        style = type.body.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.textPrimary,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                    ) {
                        TextButton(
                            onClick = { viewModel.onWorkdayMinutesChange(state.workdayMinutes - 30) },
                            enabled = !state.loading && state.workdayMinutes >= 30,
                            modifier = Modifier.semantics { contentDescription = "Decrease workday capacity" },
                        ) { Text("−", style = type.heading, color = colors.accent) }
                        TextButton(
                            onClick = { viewModel.onWorkdayMinutesChange(state.workdayMinutes + 30) },
                            enabled = !state.loading && state.workdayMinutes <= 1410,
                            modifier = Modifier.semantics { contentDescription = "Increase workday capacity" },
                        ) { Text("+", style = type.heading, color = colors.accent) }
                    }
                }
            }

            Spacer(Modifier.height(spacing.xl))

            PrimaryButton(
                text = "Save",
                onClick = viewModel::save,
                enabled = !state.loading,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(spacing.xl))
        }
    }
}
