package net.qmindtech.tmap.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun PriorityBadge(priority: Int?, modifier: Modifier = Modifier) {
  val argb = PriorityDisplay.colorArgb(priority)
  if (argb == null) {
    Text("—", style = MaterialTheme.typography.labelSmall, modifier = modifier)
  } else {
    Box(modifier = modifier.size(10.dp).background(Color(argb), CircleShape))
  }
}
