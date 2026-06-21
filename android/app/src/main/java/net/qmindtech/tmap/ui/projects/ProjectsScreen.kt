package net.qmindtech.tmap.ui.projects

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.qmindtech.tmap.data.local.entities.ProjectEntity
import net.qmindtech.tmap.ui.components.EmptyState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(viewModel: ProjectsViewModel = hiltViewModel()) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  var creating by remember { mutableStateOf(false) }
  var editing by remember { mutableStateOf<ProjectEntity?>(null) }

  Scaffold(
    topBar = { TopAppBar(title = { Text("Projects") }) },
    floatingActionButton = {
      FloatingActionButton(onClick = { creating = true }) {
        Icon(Icons.Filled.Add, contentDescription = "New project")
      }
    },
  ) { padding ->
    if (!state.loading && state.rows.isEmpty()) {
      EmptyState(
        icon = Icons.Filled.Folder,
        title = "No projects yet",
        subtitle = "Create one to organize your tasks.",
        modifier = Modifier.padding(padding),
      )
    } else {
      LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
        itemsIndexed(state.rows, key = { _, r -> r.project.id }) { index, row ->
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clickable { editing = row.project }
              .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Text(row.project.emoji)
            Text(row.project.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text("${row.openTaskCount}", style = MaterialTheme.typography.labelMedium)
            IconButton(onClick = { viewModel.moveProject(index, index - 1) }, enabled = index > 0) {
              Icon(Icons.Filled.ArrowUpward, contentDescription = "Move up")
            }
            IconButton(
              onClick = { viewModel.moveProject(index, index + 1) },
              enabled = index < state.rows.lastIndex,
            ) {
              Icon(Icons.Filled.ArrowDownward, contentDescription = "Move down")
            }
          }
        }
      }
    }

    if (creating) {
      ProjectEditDialog(
        initial = null,
        onDismiss = { creating = false },
        onSave = { name, color, emoji -> viewModel.create(name, color, emoji); creating = false },
      )
    }
    editing?.let { proj ->
      ProjectEditDialog(
        initial = proj,
        onDismiss = { editing = null },
        onSave = { name, color, emoji -> viewModel.update(proj.id, name, color, emoji); editing = null },
        onDelete = { viewModel.delete(proj.id); editing = null },
      )
    }
  }
}
