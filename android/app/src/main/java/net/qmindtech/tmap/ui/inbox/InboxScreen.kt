package net.qmindtech.tmap.ui.inbox

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.qmindtech.tmap.ui.components.EmptyState
import net.qmindtech.tmap.ui.components.TaskRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
  onOpenTask: (taskId: String?) -> Unit,
  viewModel: InboxViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  var sheetOpen by remember { mutableStateOf(false) }
  Scaffold(
    topBar = { TopAppBar(title = { Text("Inbox") }) },
    floatingActionButton = {
      FloatingActionButton(onClick = { sheetOpen = true }) {
        Icon(Icons.Filled.Add, contentDescription = "Quick add")
      }
    },
  ) { padding ->
    if (!state.loading && state.items.isEmpty()) {
      EmptyState(
        icon = Icons.Filled.Inbox,
        title = "Inbox zero",
        subtitle = "Capture anything with the + button.",
        modifier = Modifier.padding(padding),
      )
    } else {
      LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
        items(state.items, key = { it.task.id }) { item ->
          TaskRow(
            task = item.task,
            projectName = item.projectName,
            onClick = { onOpenTask(item.task.id) },
            onToggleDone = { viewModel.toggleDone(item.task) },
          )
        }
      }
    }
    if (sheetOpen) {
      QuickAddSheet(onDismiss = { sheetOpen = false }, onSubmit = { viewModel.quickAdd(it) })
    }
  }
}
