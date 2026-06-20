package net.qmindtech.tmap.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import net.qmindtech.tmap.data.local.TaskStatus

@Composable
fun StatusChip(status: TaskStatus, modifier: Modifier = Modifier) {
  val tint: Long = when (status) {
    TaskStatus.Inbox -> 0xFF64748BL
    TaskStatus.Backlog -> 0xFF94A3B8L
    TaskStatus.Planned -> 0xFF6366F1L
    TaskStatus.Scheduled -> 0xFF3B82F6L
    TaskStatus.Done -> 0xFF22C55EL
    TaskStatus.Archived -> 0xFF475569L
  }
  AssistChip(
    onClick = {},
    enabled = false,
    label = { Text(StatusDisplay.label(status)) },
    colors = AssistChipDefaults.assistChipColors(disabledLabelColor = Color(tint)),
    modifier = modifier.padding(end = 4.dp),
  )
}
