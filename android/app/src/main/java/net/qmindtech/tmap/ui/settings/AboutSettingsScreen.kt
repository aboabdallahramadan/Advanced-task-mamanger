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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.qmindtech.tmap.BuildConfig
import net.qmindtech.tmap.ui.theme.LocalTmapColors
import net.qmindtech.tmap.ui.theme.LocalTmapShapes
import net.qmindtech.tmap.ui.theme.LocalTmapSpacing
import net.qmindtech.tmap.ui.theme.LocalTmapType
import net.qmindtech.tmap.ui.theme.TmapBackground

@Composable
fun AboutSettingsScreen(onBack: () -> Unit) {
    val colors = LocalTmapColors.current
    val shapes = LocalTmapShapes.current
    val spacing = LocalTmapSpacing.current
    val type = LocalTmapType.current

    TmapBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
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
                    text = "About",
                    style = type.heading,
                    color = colors.textPrimary,
                )
            }

            Spacer(Modifier.height(spacing.lg))

            // App info card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surface, RoundedCornerShape(shapes.card))
                    .border(1.dp, colors.borderSubtle, RoundedCornerShape(shapes.card))
                    .padding(horizontal = spacing.lg, vertical = spacing.xl),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                Text(
                    text = "TMap",
                    style = type.title.copy(fontWeight = FontWeight.Bold),
                    color = colors.textPrimary,
                )
                Text(
                    text = "Version ${BuildConfig.VERSION_NAME}",
                    style = type.meta,
                    color = colors.textTertiary,
                )
                Spacer(Modifier.height(spacing.xs))
                Text(
                    text = "Daily planning, time blocking, and focus — all in one place.",
                    style = type.body,
                    color = colors.textBody,
                )
                Spacer(Modifier.height(spacing.xs))
                Text(
                    text = "Backend",
                    style = type.label,
                    color = colors.textTertiary,
                )
                Text(
                    text = "api-tasks.qmindtech.net",
                    style = type.meta,
                    color = colors.textSecondary,
                )
            }
        }
    }
}
