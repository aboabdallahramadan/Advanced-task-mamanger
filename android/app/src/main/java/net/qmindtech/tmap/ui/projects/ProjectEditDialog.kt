package net.qmindtech.tmap.ui.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.qmindtech.tmap.data.local.entities.ProjectEntity

private val PROJECT_COLORS = listOf(
  "#6366f1", "#8b5cf6", "#ec4899", "#ef4444", "#f97316", "#eab308",
  "#22c55e", "#14b8a6", "#06b6d4", "#3b82f6", "#a855f7", "#f43f5e",
)
private val PROJECT_EMOJIS = listOf(
  "📁", "💼", "🚀", "🎯", "📚", "💡", "🔧", "🎨", "📊", "🏠", "💻", "📝", "⚡", "🌟", "🔥", "🎮",
)

private fun parseColor(hex: String): Color = Color(("ff" + hex.removePrefix("#")).toLong(16))

@Composable
fun ProjectEditDialog(
  initial: ProjectEntity?,
  onDismiss: () -> Unit,
  onSave: (name: String, color: String, emoji: String) -> Unit,
  onDelete: (() -> Unit)? = null,
) {
  var name by remember { mutableStateOf(initial?.name ?: "") }
  var color by remember { mutableStateOf(initial?.color ?: PROJECT_COLORS.first()) }
  var emoji by remember { mutableStateOf(initial?.emoji ?: PROJECT_EMOJIS.first()) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(if (initial == null) "New Project" else "Edit Project") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
          value = name,
          onValueChange = { name = it },
          modifier = Modifier.fillMaxWidth(),
          label = { Text("Project name") },
          singleLine = true,
        )
        Text("Icon")
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          PROJECT_EMOJIS.take(8).forEach { e ->
            Text(
              e,
              fontWeight = if (emoji == e) FontWeight.Bold else FontWeight.Normal,
              modifier = Modifier.clickable { emoji = e },
            )
          }
        }
        Text("Color")
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          PROJECT_COLORS.take(8).forEach { c ->
            Box(
              modifier = Modifier
                .size(28.dp)
                .background(parseColor(c), CircleShape)
                .border(if (color == c) 2.dp else 0.dp, Color.White, CircleShape)
                .clickable { color = c },
            )
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = { onSave(name, color, emoji) }, enabled = name.isNotBlank()) {
        Text(if (initial == null) "Create" else "Update")
      }
    },
    dismissButton = {
      Row {
        if (initial != null && onDelete != null) TextButton(onClick = onDelete) { Text("Delete") }
        TextButton(onClick = onDismiss) { Text("Cancel") }
      }
    },
  )
}
