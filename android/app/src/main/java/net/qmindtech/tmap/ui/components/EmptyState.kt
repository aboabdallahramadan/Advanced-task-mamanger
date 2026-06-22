package net.qmindtech.tmap.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import net.qmindtech.tmap.ui.theme.LocalTmapColors
import net.qmindtech.tmap.ui.theme.LocalTmapType

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = LocalTmapColors.current
    val type = LocalTmapType.current
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(40.dp))
        Text(
            title,
            style = type.heading,
            color = colors.textPrimary,
            modifier = Modifier.padding(top = 12.dp),
        )
        if (subtitle != null) {
            Text(
                subtitle,
                style = type.body,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        if (actionLabel != null && onAction != null) {
            SecondaryButton(
                text = actionLabel,
                onClick = onAction,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}
