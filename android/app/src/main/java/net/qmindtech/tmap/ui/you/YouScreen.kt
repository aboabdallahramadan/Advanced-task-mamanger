package net.qmindtech.tmap.ui.you

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.qmindtech.tmap.ui.components.SectionLabel
import net.qmindtech.tmap.ui.components.StatTile
import net.qmindtech.tmap.ui.components.SyncStatusPill
import net.qmindtech.tmap.ui.theme.LocalTmapColors
import net.qmindtech.tmap.ui.theme.LocalTmapShapes
import net.qmindtech.tmap.ui.theme.LocalTmapSpacing
import net.qmindtech.tmap.ui.theme.LocalTmapType

@Composable
fun YouScreen(
    onOpenSettings: (SettingsEntry) -> Unit,
    viewModel: YouViewModel = hiltViewModel(),
) {
    val colors = LocalTmapColors.current
    val shapes = LocalTmapShapes.current
    val spacing = LocalTmapSpacing.current
    val type = LocalTmapType.current

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.screenH),
        verticalArrangement = Arrangement.spacedBy(spacing.lg),
    ) {
        Spacer(Modifier.height(spacing.lg))

        // ── Profile row ───────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Avatar circle with 135° amber gradient + initials
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(colors.accent, colors.accentEnd),
                            start = androidx.compose.ui.geometry.Offset(0f, Float.POSITIVE_INFINITY),
                            end = androidx.compose.ui.geometry.Offset(
                                Float.POSITIVE_INFINITY,
                                0f,
                            ),
                        ),
                    )
                    .semantics { contentDescription = "Profile avatar" },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = uiState.profile.initials,
                    style = type.title.copy(fontWeight = FontWeight.Bold),
                    color = colors.onAccent,
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(spacing.base),
            ) {
                Text(
                    text = uiState.profile.displayName,
                    style = type.heading,
                    color = colors.textPrimary,
                )
                Text(
                    text = uiState.profile.email,
                    style = type.meta,
                    color = colors.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                SyncStatusPill(
                    status = uiState.syncStatus,
                    pendingCount = uiState.pendingCount,
                    onRetry = viewModel::onRetrySync,
                )
            }
        }

        // ── Stat tiles row ────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            StatTile(
                value = "🔥 ${uiState.dayStreak}",
                label = "DAY STREAK",
                modifier = Modifier.weight(1f),
            )
            StatTile(
                value = "✓ ${uiState.doneThisWeek}",
                label = "DONE THIS WK",
                modifier = Modifier.weight(1f),
            )
            StatTile(
                value = "◷ ${uiState.focusHoursLabel}",
                label = "FOCUS THIS WK",
                modifier = Modifier.weight(1f),
            )
        }

        // ── Settings section ──────────────────────────────────────────────────
        Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            SectionLabel(text = "Settings")

            // Grouped settings card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(shapes.card))
                    .background(colors.surface)
                    .border(1.dp, colors.borderSubtle, RoundedCornerShape(shapes.card)),
            ) {
                uiState.settingsEntries.forEachIndexed { index, entry ->
                    val (emoji, label) = settingsEntryMeta(entry)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenSettings(entry) }
                            .padding(horizontal = spacing.lg, vertical = spacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(spacing.md),
                    ) {
                        Text(
                            text = emoji,
                            style = type.body,
                        )
                        Text(
                            text = label,
                            style = type.body,
                            color = colors.textPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.NavigateNext,
                            contentDescription = null,
                            tint = colors.textTertiary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    // Divider between rows only (not after the last)
                    if (index < uiState.settingsEntries.lastIndex) {
                        HorizontalDivider(
                            color = colors.borderSubtle,
                            thickness = 1.dp,
                            modifier = Modifier.padding(horizontal = spacing.lg),
                        )
                    }
                }
            }
        }

        // ── Sign out card ─────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(shapes.well))
                .background(colors.surfaceInset)
                .border(1.dp, colors.borderSubtle, RoundedCornerShape(shapes.well))
                .clickable { viewModel.onSignOut() }
                .padding(horizontal = spacing.lg, vertical = spacing.md)
                .semantics { contentDescription = "Sign out" },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Logout,
                contentDescription = null,
                tint = colors.danger,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(spacing.xs))
            Text(
                text = "Sign out",
                style = type.body.copy(fontWeight = FontWeight.SemiBold),
                color = colors.danger,
            )
        }

        Spacer(Modifier.height(spacing.lg))
    }
}

/** Maps each [SettingsEntry] to its display (emoji, label) pair. */
private fun settingsEntryMeta(entry: SettingsEntry): Pair<String, String> = when (entry) {
    SettingsEntry.Notifications -> "🔔" to "Notifications & reminders"
    SettingsEntry.Appearance -> "🎨" to "Appearance"
    SettingsEntry.Account -> "👤" to "Account"
    SettingsEntry.DataAndSync -> "☁" to "Data & sync"
    SettingsEntry.About -> "ℹ" to "About"
}
