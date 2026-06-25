package net.qmindtech.tmap.ui.settings

import android.app.AlarmManager
import android.content.Context
import android.os.Build
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.qmindtech.tmap.ui.components.PrimaryButton
import net.qmindtech.tmap.ui.permissions.ReminderPermissionGate
import net.qmindtech.tmap.ui.theme.LocalTmapColors
import net.qmindtech.tmap.ui.theme.LocalTmapShapes
import net.qmindtech.tmap.ui.theme.LocalTmapSpacing
import net.qmindtech.tmap.ui.theme.LocalTmapType
import net.qmindtech.tmap.ui.theme.TmapBackground

@Composable
fun NotificationsSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val colors = LocalTmapColors.current
    val shapes = LocalTmapShapes.current
    val spacing = LocalTmapSpacing.current
    val type = LocalTmapType.current
    val context = LocalContext.current

    val state by viewModel.uiState.collectAsStateWithLifecycle()

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
                    text = "Notifications & Reminders",
                    style = type.heading,
                    color = colors.textPrimary,
                )
            }

            Spacer(Modifier.height(spacing.lg))

            // Notifications toggle card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surface, RoundedCornerShape(shapes.card))
                    .border(1.dp, colors.borderSubtle, RoundedCornerShape(shapes.card))
                    .padding(horizontal = spacing.lg, vertical = spacing.md),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Reminder notifications",
                        style = type.body,
                        color = colors.textPrimary,
                    )
                    Switch(
                        checked = state.notificationsEnabled,
                        onCheckedChange = viewModel::onNotificationsToggle,
                        enabled = !state.loading,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = colors.onAccent,
                            checkedTrackColor = colors.accent,
                            uncheckedThumbColor = colors.textTertiary,
                            uncheckedTrackColor = colors.surfaceInset,
                        ),
                        modifier = Modifier.semantics { contentDescription = "Toggle reminder notifications" },
                    )
                }
            }

            Spacer(Modifier.height(spacing.lg))

            // Default reminder stepper
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surface, RoundedCornerShape(shapes.card))
                    .border(1.dp, colors.borderSubtle, RoundedCornerShape(shapes.card))
                    .padding(horizontal = spacing.lg, vertical = spacing.md),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                Text(
                    text = "Default reminder",
                    style = type.body.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.textPrimary,
                )
                Text(
                    text = "Remind ${state.defaultReminderMinutes} min before",
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
                        text = "Minutes before",
                        style = type.body,
                        color = colors.textBody,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                    ) {
                        TextButton(
                            onClick = { viewModel.onDefaultReminderChange(state.defaultReminderMinutes - 5) },
                            enabled = !state.loading && state.defaultReminderMinutes >= 5,
                            modifier = Modifier.semantics { contentDescription = "Decrease reminder minutes" },
                        ) { Text("−", style = type.heading, color = colors.accent) }
                        Text(
                            text = "${state.defaultReminderMinutes}",
                            style = type.body.copy(fontWeight = FontWeight.SemiBold),
                            color = colors.textPrimary,
                        )
                        TextButton(
                            onClick = { viewModel.onDefaultReminderChange(state.defaultReminderMinutes + 5) },
                            enabled = !state.loading && state.defaultReminderMinutes <= 1435,
                            modifier = Modifier.semantics { contentDescription = "Increase reminder minutes" },
                        ) { Text("+", style = type.heading, color = colors.accent) }
                    }
                }
            }

            Spacer(Modifier.height(spacing.lg))

            // Permission gate (in-context; fires LaunchedEffect on entry)
            ReminderPermissionGate(
                canScheduleExact = {
                    if (Build.VERSION.SDK_INT >= 31) {
                        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                        am.canScheduleExactAlarms()
                    } else {
                        true
                    }
                },
            )

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
