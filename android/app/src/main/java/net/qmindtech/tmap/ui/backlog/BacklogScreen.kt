package net.qmindtech.tmap.ui.backlog

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.qmindtech.tmap.ui.components.EmptyState
import net.qmindtech.tmap.ui.components.TaskRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BacklogScreen(
  onOpenTask: (taskId: String?) -> Unit,
  viewModel: BacklogViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  Scaffold(topBar = { TopAppBar(title = { Text("Backlog") }) }) { padding ->
    if (!state.loading && state.items.isEmpty()) {
      EmptyState(
        icon = Icons.Filled.Layers,
        title = "Backlog is empty",
        subtitle = "Unplanned tasks land here.",
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
  }
}
