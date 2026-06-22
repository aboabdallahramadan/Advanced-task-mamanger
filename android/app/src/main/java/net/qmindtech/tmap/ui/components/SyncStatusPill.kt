package net.qmindtech.tmap.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.qmindtech.tmap.data.sync.SyncStatus
import net.qmindtech.tmap.ui.theme.LocalTmapColors
import net.qmindtech.tmap.ui.theme.LocalTmapShapes
import net.qmindtech.tmap.ui.theme.LocalTmapType

data class SyncPillContent(val label: String, val showRetry: Boolean, val visible: Boolean)

/** Pure mapping from sync state to pill content (mirrors desktop pill quiet-ok behavior). */
fun syncPillContent(status: SyncStatus, pendingCount: Int): SyncPillContent {
    val pendingSuffix = if (pendingCount > 0) " · $pendingCount pending" else ""
    return when (status) {
        is SyncStatus.Idle ->
            if (pendingCount == 0) SyncPillContent("", showRetry = false, visible = false)
            else SyncPillContent("Synced$pendingSuffix", showRetry = false, visible = true)
        is SyncStatus.Syncing -> SyncPillContent("Syncing…$pendingSuffix", showRetry = false, visible = true)
        is SyncStatus.Offline -> SyncPillContent("Offline$pendingSuffix", showRetry = true, visible = true)
        is SyncStatus.Error -> SyncPillContent(status.message, showRetry = true, visible = true)
    }
}

@Composable
fun SyncStatusPill(
    status: SyncStatus,
    pendingCount: Int,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val content = syncPillContent(status, pendingCount)
    if (!content.visible) return
    val colors = LocalTmapColors.current
    val shapes = LocalTmapShapes.current
    val type = LocalTmapType.current
    Row(
        modifier = modifier
            .background(colors.surfaceInset, RoundedCornerShape(shapes.pill))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val dotColor = when (status) {
            is SyncStatus.Idle -> colors.success
            is SyncStatus.Error, is SyncStatus.Offline -> colors.danger
            is SyncStatus.Syncing -> colors.accent
        }
        Box(modifier = Modifier.size(8.dp).background(dotColor, CircleShape))
        Text(content.label, style = type.meta, color = colors.textSecondary)
        if (content.showRetry) {
            Icon(
                Icons.Filled.Refresh,
                contentDescription = "Retry sync",
                tint = colors.accent,
                modifier = Modifier
                    .size(16.dp)
                    .clickable(onClick = onRetry),
            )
        }
    }
}
