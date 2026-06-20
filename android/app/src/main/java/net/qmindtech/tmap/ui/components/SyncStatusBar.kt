package net.qmindtech.tmap.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.qmindtech.tmap.data.sync.SyncStatus

@Composable
fun SyncStatusBar(
  status: SyncStatus,
  pendingCount: Int,
  onRetry: () -> Unit,
  modifier: Modifier = Modifier,
) {
  // Quiet-ok with nothing pending → render nothing (mirrors desktop pill).
  if (status is SyncStatus.Idle && pendingCount == 0) return
  val (icon, label) = when (status) {
    is SyncStatus.Idle -> Icons.Filled.CloudDone to "Synced"
    is SyncStatus.Syncing -> Icons.Filled.Sync to "Syncing…"
    is SyncStatus.Offline -> Icons.Filled.CloudOff to "Offline"
    is SyncStatus.Error -> Icons.Filled.Warning to status.message
  }
  Surface(modifier = modifier.fillMaxWidth(), tonalElevation = 2.dp) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
      Text(label, style = MaterialTheme.typography.labelMedium)
      if (pendingCount > 0) Text("$pendingCount pending", style = MaterialTheme.typography.labelSmall)
      if (status is SyncStatus.Error || status is SyncStatus.Offline) {
        TextButton(onClick = onRetry) {
          Icon(Icons.Filled.Refresh, contentDescription = "Retry", modifier = Modifier.size(16.dp))
          Text("Retry")
        }
      }
    }
  }
}
