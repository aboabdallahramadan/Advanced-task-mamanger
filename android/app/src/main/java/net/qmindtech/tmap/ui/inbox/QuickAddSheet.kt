package net.qmindtech.tmap.ui.inbox

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickAddSheet(onDismiss: () -> Unit, onSubmit: (title: String) -> Unit) {
  var text by remember { mutableStateOf("") }
  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
      Text("Add to inbox")
      OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        singleLine = true,
        placeholder = { Text("What needs doing?") },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
          if (text.isNotBlank()) { onSubmit(text); onDismiss() }
        }),
      )
      Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Button(
          onClick = { if (text.isNotBlank()) { onSubmit(text); onDismiss() } },
          enabled = text.isNotBlank(),
        ) { Text("Add") }
      }
    }
  }
}
