package net.qmindtech.tmap.ui.taskeditor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.ui.components.PriorityDisplay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskEditorScreen(
  onClose: () -> Unit,
  viewModel: TaskEditorViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  var newSubtask by remember { mutableStateOf("") }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(if (state.isEdit) "Edit Task" else "New Task") },
        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") } },
        actions = {
          if (state.isEdit) {
            IconButton(onClick = { viewModel.markDone() }) { Icon(Icons.Filled.Done, contentDescription = "Mark done") }
            IconButton(onClick = { viewModel.delete(onClose) }) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
          }
          IconButton(onClick = { viewModel.save(onClose) }, enabled = state.title.isNotBlank()) {
            Icon(Icons.Filled.Check, contentDescription = "Save")
          }
        },
      )
    },
  ) { padding ->
    Column(
      modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      OutlinedTextField(
        value = state.title,
        onValueChange = viewModel::onTitleChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Title") },
        singleLine = true,
      )
      OutlinedTextField(
        value = state.notes,
        onValueChange = viewModel::onNotesChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Notes") },
      )

      Text("Status")
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(TaskStatus.Inbox, TaskStatus.Backlog, TaskStatus.Planned, TaskStatus.Scheduled, TaskStatus.Done).forEach { st ->
          FilterChip(selected = state.status == st, onClick = { viewModel.onStatusChange(st) }, label = { Text(st.name) })
        }
      }

      Text("Priority")
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = state.priority == null, onClick = { viewModel.onPriorityChange(null) }, label = { Text("None") })
        listOf(1, 2, 3, 4).forEach { p ->
          FilterChip(selected = state.priority == p, onClick = { viewModel.onPriorityChange(p) }, label = { Text(PriorityDisplay.label(p)) })
        }
      }

      Text("Project")
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = state.projectId == null, onClick = { viewModel.onProjectChange(null) }, label = { Text("No project") })
        state.projects.forEach { p ->
          FilterChip(selected = state.projectId == p.id, onClick = { viewModel.onProjectChange(p.id) }, label = { Text("${p.emoji} ${p.name}") })
        }
      }

      Text("Reminder")
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(null to "None", 0 to "At start", 5 to "5m", 10 to "10m", 15 to "15m", 30 to "30m").forEach { (m, label) ->
          FilterChip(selected = state.reminderMinutes == m, onClick = { viewModel.onReminderChange(m) }, label = { Text(label) })
        }
      }

      if (state.isEdit) {
        Text("Subtasks")
        state.subtasks.forEach { sub ->
          SubtaskRow(
            subtask = sub,
            onToggle = { viewModel.toggleSubtask(sub) },
            onRename = { viewModel.renameSubtask(sub, it) },
            onDelete = { viewModel.deleteSubtask(sub.id) },
          )
        }
        OutlinedTextField(
          value = newSubtask,
          onValueChange = { newSubtask = it },
          modifier = Modifier.fillMaxWidth(),
          label = { Text("Add a subtask") },
          singleLine = true,
          keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
          keyboardActions = KeyboardActions(onDone = {
            viewModel.addSubtask(newSubtask); newSubtask = ""
          }),
        )
      }

      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.isEdit) OutlinedButton(onClick = { viewModel.markDone() }) { Text("Mark done") }
        Button(onClick = { viewModel.save(onClose) }, enabled = state.title.isNotBlank()) {
          Text(if (state.isEdit) "Update" else "Create")
        }
      }
    }
  }
}
