package net.qmindtech.tmap.ui.alltasks

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChecklistRtl
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.qmindtech.tmap.ui.components.EmptyState
import net.qmindtech.tmap.ui.components.TaskRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllTasksScreen(
  onOpenTask: (taskId: String?) -> Unit,
  viewModel: AllTasksViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  var sortMenu by remember { mutableStateOf(false) }
  var groupMenu by remember { mutableStateOf(false) }

  Scaffold(
    topBar = {
      TopAppBar(title = { Text("All Tasks (${state.totalCount})") })
    },
  ) { padding ->
    Column(modifier = Modifier.fillMaxSize().padding(padding)) {
      OutlinedTextField(
        value = state.filter.search,
        onValueChange = viewModel::setSearch,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        singleLine = true,
        placeholder = { Text("Search tasks…") },
      )
      Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        TaskFilter.NON_ARCHIVED_STATUSES.forEach { st ->
          FilterChip(
            selected = st in state.filter.statuses,
            onClick = {
              val next = state.filter.statuses.toMutableSet()
              if (!next.remove(st)) next.add(st)
              viewModel.setStatuses(next)
            },
            label = { Text(st.name) },
          )
        }
        FilterChip(
          selected = state.filter.showArchived,
          onClick = { viewModel.setShowArchived(!state.filter.showArchived) },
          label = { Text("Archived") },
        )
        TextButton(onClick = { sortMenu = true }) { Text("Sort: ${state.filter.sortField}") }
        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
          SortField.entries.forEach { f ->
            DropdownMenuItem(text = { Text(f.name) }, onClick = { viewModel.setSort(f); sortMenu = false })
          }
        }
        TextButton(onClick = { groupMenu = true }) { Text("Group: ${state.filter.groupBy}") }
        DropdownMenu(expanded = groupMenu, onDismissRequest = { groupMenu = false }) {
          GroupBy.entries.forEach { g ->
            DropdownMenuItem(text = { Text(g.name) }, onClick = { viewModel.setGroupBy(g); groupMenu = false })
          }
        }
        TextButton(onClick = viewModel::clearFilters) {
          Icon(Icons.Filled.Clear, contentDescription = null)
          Text("Clear")
        }
      }

      if (!state.loading && state.totalCount == 0) {
        EmptyState(
          icon = Icons.Filled.ChecklistRtl,
          title = "No tasks match your filters",
          subtitle = "Try adjusting your filters or search.",
          actionLabel = "Clear filters",
          onAction = viewModel::clearFilters,
        )
      } else {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
          state.groups.forEach { group ->
            if (state.filter.groupBy != GroupBy.None) {
              item(key = "header-${group.key}") {
                Text(
                  "${group.label} (${group.items.size})",
                  style = MaterialTheme.typography.titleSmall,
                  modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                )
              }
            }
            items(group.items, key = { "${group.key}-${it.task.id}" }) { item ->
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
  }
}
