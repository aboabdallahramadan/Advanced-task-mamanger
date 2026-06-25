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
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
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
import net.qmindtech.tmap.ui.components.PrimaryButton
import net.qmindtech.tmap.ui.components.SecondaryButton
import net.qmindtech.tmap.ui.theme.LocalTmapColors
import net.qmindtech.tmap.ui.theme.LocalTmapShapes
import net.qmindtech.tmap.ui.theme.LocalTmapSpacing
import net.qmindtech.tmap.ui.theme.LocalTmapType
import net.qmindtech.tmap.ui.theme.TmapBackground
import net.qmindtech.tmap.ui.you.YouViewModel

@Composable
fun AccountSettingsScreen(
    onBack: () -> Unit,
    viewModel: net.qmindtech.tmap.ui.you.YouViewModel = hiltViewModel(),
) {
    val colors = LocalTmapColors.current
    val shapes = LocalTmapShapes.current
    val spacing = LocalTmapSpacing.current
    val type = LocalTmapType.current

    val settingsVm: SettingsViewModel = hiltViewModel()
    val settingsState by settingsVm.uiState.collectAsStateWithLifecycle()
    val youState by viewModel.uiState.collectAsStateWithLifecycle()

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
                    text = "Account",
                    style = type.heading,
                    color = colors.textPrimary,
                )
            }

            Spacer(Modifier.height(spacing.lg))

            // Email display card (read-only)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surface, RoundedCornerShape(shapes.card))
                    .border(1.dp, colors.borderSubtle, RoundedCornerShape(shapes.card))
                    .padding(horizontal = spacing.lg, vertical = spacing.md),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                Text(
                    text = "Signed in as",
                    style = type.label,
                    color = colors.textTertiary,
                )
                Text(
                    text = youState.profile.email.ifBlank { "—" },
                    style = type.body.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.textPrimary,
                )
            }

            Spacer(Modifier.height(spacing.lg))

            // Timezone field
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surface, RoundedCornerShape(shapes.card))
                    .border(1.dp, colors.borderSubtle, RoundedCornerShape(shapes.card))
                    .padding(horizontal = spacing.lg, vertical = spacing.md),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                Text(
                    text = "Time zone",
                    style = type.label,
                    color = colors.textTertiary,
                )
                OutlinedTextField(
                    value = settingsState.timeZoneId,
                    onValueChange = settingsVm::onTimeZoneChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("IANA time zone ID", style = type.meta, color = colors.textTertiary) },
                    singleLine = true,
                    enabled = !settingsState.loading,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = colors.textPrimary,
                        unfocusedTextColor = colors.textPrimary,
                        focusedBorderColor = colors.accent,
                        unfocusedBorderColor = colors.borderSubtle,
                        focusedContainerColor = colors.surfaceInset,
                        unfocusedContainerColor = colors.surfaceInset,
                        cursorColor = colors.accent,
                    ),
                )
            }

            Spacer(Modifier.height(spacing.xl))

            PrimaryButton(
                text = "Save",
                onClick = settingsVm::save,
                enabled = !settingsState.loading,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(spacing.xl))

            // Sign out (danger affordance)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surfaceInset, RoundedCornerShape(shapes.well))
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
                Spacer(Modifier.size(spacing.xs))
                Text(
                    text = "Sign out",
                    style = type.body.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.danger,
                )
            }

            Spacer(Modifier.height(spacing.xl))
        }
    }
}
