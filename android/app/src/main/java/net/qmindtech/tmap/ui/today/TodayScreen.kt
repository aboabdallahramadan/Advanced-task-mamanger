package net.qmindtech.tmap.ui.today

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
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

// NOTE: This screen is a temporary stub that keeps the build green until P1.4/P1.5 rebuild it
// against the full TodayUiState (groups, progress, greeting, etc.).
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
  onOpenTask: (taskId: String?) -> Unit,
  viewModel: TodayViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  // Flatten all grouped tasks into a single list for the interim screen.
  val allTasks = state.groups.flatMap { it.tasks }
  Scaffold(
    topBar = { TopAppBar(title = { Text("Today") }) },
    floatingActionButton = {
      FloatingActionButton(onClick = { onOpenTask(null) }) {
        Icon(Icons.Filled.Add, contentDescription = "New task")
      }
    },
  ) { padding ->
    if (!state.loading && allTasks.isEmpty()) {
      EmptyState(
        icon = Icons.Filled.Today,
        title = "Nothing scheduled today",
        subtitle = "Plan a task to see it here.",
        modifier = Modifier.padding(padding),
      )
    } else {
      LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
        items(allTasks, key = { it.id }) { taskUi ->
          TaskRow(
            task = taskUi,
            projectName = taskUi.projectName,
            onClick = { onOpenTask(taskUi.id) },
            onToggleDone = { viewModel.toggleComplete(taskUi.id) },
          )
        }
      }
    }
  }
}
