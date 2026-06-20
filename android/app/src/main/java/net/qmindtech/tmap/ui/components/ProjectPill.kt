package net.qmindtech.tmap.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.qmindtech.tmap.data.local.entities.ProjectEntity

@Composable
fun ProjectPill(project: ProjectEntity?, modifier: Modifier = Modifier) {
  if (project == null) return
  Row(
    modifier = modifier.padding(end = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Text(project.emoji)
    Text(project.name, style = MaterialTheme.typography.labelMedium)
  }
}
