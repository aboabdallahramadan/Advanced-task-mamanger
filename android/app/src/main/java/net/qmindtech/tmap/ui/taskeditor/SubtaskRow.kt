package net.qmindtech.tmap.ui.taskeditor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import net.qmindtech.tmap.data.local.entities.SubtaskEntity

@Composable
fun SubtaskRow(
  subtask: SubtaskEntity,
  onToggle: () -> Unit,
  onRename: (String) -> Unit,
  onDelete: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var text by remember(subtask.id, subtask.title) { mutableStateOf(subtask.title) }
  Row(
    modifier = modifier.fillMaxWidth().padding(vertical = 2.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Checkbox(checked = subtask.completed, onCheckedChange = { onToggle() })
    OutlinedTextField(
      value = text,
      onValueChange = { text = it },
      modifier = Modifier.weight(1f),
      singleLine = true,
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
      keyboardActions = KeyboardActions(onDone = { onRename(text) }),
    )
    IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete subtask") }
  }
}
