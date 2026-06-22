package net.qmindtech.tmap.ui.browse

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.qmindtech.tmap.ui.components.Chip
import net.qmindtech.tmap.ui.components.EmptyState
import net.qmindtech.tmap.ui.components.FilterChip
import net.qmindtech.tmap.ui.components.PriorityDisplay
import net.qmindtech.tmap.ui.components.SectionLabel
import net.qmindtech.tmap.ui.components.SegmentedControl
import net.qmindtech.tmap.ui.components.SheetScaffold
import net.qmindtech.tmap.ui.components.StatusDisplay
import net.qmindtech.tmap.ui.components.TaskCard
import net.qmindtech.tmap.ui.projects.ProjectsScreen
import net.qmindtech.tmap.ui.theme.LocalTmapColors
import net.qmindtech.tmap.ui.theme.LocalTmapShapes
import net.qmindtech.tmap.ui.theme.LocalTmapSpacing
import net.qmindtech.tmap.ui.theme.LocalTmapType

private val SEGMENTS = listOf("All Tasks", "Backlog", "Projects")

private enum class BrowseSheet { Filter, Sort, Group }

@Composable
fun BrowseScreen(
  onOpenTask: (taskId: String) -> Unit,
  onOpenProject: (projectId: String) -> Unit,
  viewModel: BrowseViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val colors = LocalTmapColors.current
  val spacing = LocalTmapSpacing.current
  val type = LocalTmapType.current
  val shapes = LocalTmapShapes.current
  var sheet by remember { mutableStateOf<BrowseSheet?>(null) }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .padding(horizontal = spacing.screenH),
  ) {
    // Screen title
    Text(
      text = "Browse",
      style = type.heading,
      color = colors.textPrimary,
      modifier = Modifier.padding(top = spacing.lg, bottom = spacing.xs),
    )

    // Search well — surfaceInset container, well corners (12 dp), magnifier leading icon
    OutlinedTextField(
      value = state.filter.search,
      onValueChange = viewModel::setSearch,
      modifier = Modifier
        .fillMaxWidth()
        .padding(bottom = spacing.xs)
        .semantics { contentDescription = "Search tasks" },
      singleLine = true,
      leadingIcon = {
        Icon(
          imageVector = Icons.Filled.Search,
          contentDescription = "Search",
          tint = colors.textSecondary,
        )
      },
      placeholder = {
        Text(
          text = "Search title, notes, project…",
          style = type.body,
          color = colors.textTertiary,
        )
      },
      textStyle = type.body.copy(color = colors.textPrimary),
      colors = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = colors.surfaceInset,
        unfocusedContainerColor = colors.surfaceInset,
        focusedBorderColor = colors.borderStrong,
        unfocusedBorderColor = colors.borderSubtle,
        cursorColor = colors.accent,
        focusedTextColor = colors.textPrimary,
        unfocusedTextColor = colors.textPrimary,
      ),
      shape = RoundedCornerShape(shapes.well),
    )

    // Segment control: All Tasks / Backlog / Projects
    SegmentedControl(
      options = SEGMENTS,
      selectedIndex = state.segment.ordinal,
      onSelect = { viewModel.setSegment(BrowseSegment.entries[it]) },
    )

    // Filter / Sort / Group chip row (hidden for Projects segment)
    if (state.segment != BrowseSegment.Projects) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .horizontalScroll(rememberScrollState())
          .padding(vertical = spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
      ) {
        Chip(
          label = "Filters" + filterBadge(state.activeFilterCount),
          onClick = { sheet = BrowseSheet.Filter },
          selected = state.activeFilterCount > 0,
          modifier = Modifier.semantics { contentDescription = "Open filters" },
        )
        Chip(
          label = "Sort: ${state.filter.sortField.name}",
          onClick = { sheet = BrowseSheet.Sort },
          modifier = Modifier.semantics { contentDescription = "Open sort options" },
        )
        Chip(
          label = "Group: ${state.filter.groupBy.name}",
          onClick = { sheet = BrowseSheet.Group },
          modifier = Modifier.semantics { contentDescription = "Open group options" },
        )
      }
    }

    // Content area
    when (state.segment) {
      BrowseSegment.Projects -> {
        // Projects segment: render existing ProjectsScreen.
        // ProjectsScreen manages its own FAB + edit dialogs.
        ProjectsScreen()
      }
      else -> {
        if (!state.loading && state.totalCount == 0) {
          EmptyState(
            icon = Icons.Filled.ViewAgenda,
            title = "No tasks match",
            subtitle = "Adjust your filters or search.",
            actionLabel = if (state.activeFilterCount > 0) "Clear filters" else null,
            onAction = if (state.activeFilterCount > 0) viewModel::clearFilters else null,
          )
        } else {
          LazyColumn(modifier = Modifier.fillMaxSize()) {
            state.groups.forEach { group ->
              if (state.filter.groupBy != GroupBy.None) {
                item(key = "header-${group.key}") {
                  SectionLabel(
                    text = "${group.label} (${group.items.size})",
                    modifier = Modifier.padding(top = spacing.md, bottom = spacing.base),
                  )
                }
              }
              items(group.items, key = { "${group.key}-${it.task.id}" }) { item ->
                TaskCard(
                  task = item.ui,
                  onToggleComplete = { viewModel.toggleDone(item.task) },
                  onClick = { onOpenTask(item.task.id) },
                  modifier = Modifier.padding(vertical = spacing.base),
                )
              }
            }
          }
        }
      }
    }
  }

  // Bottom-sheet pickers
  when (sheet) {
    BrowseSheet.Filter -> FilterSheet(state = state, vm = viewModel, onDismiss = { sheet = null })
    BrowseSheet.Sort -> SortSheet(state = state, vm = viewModel, onDismiss = { sheet = null })
    BrowseSheet.Group -> GroupSheet(state = state, vm = viewModel, onDismiss = { sheet = null })
    null -> {}
  }
}

// ────────────────────────────────────────────────────────────────────────────
// Helpers
// ────────────────────────────────────────────────────────────────────────────

private fun filterBadge(count: Int): String = if (count > 0) " ($count)" else ""

// ────────────────────────────────────────────────────────────────────────────
// Filter sheet
// ────────────────────────────────────────────────────────────────────────────

@Composable
private fun FilterSheet(state: BrowseUiState, vm: BrowseViewModel, onDismiss: () -> Unit) {
  val spacing = LocalTmapSpacing.current

  SheetScaffold(onDismiss = onDismiss, title = "Filter") {
    // Status
    SectionLabel(text = "Status", modifier = Modifier.padding(top = spacing.xs, bottom = spacing.base))
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
      TaskFilter.NON_ARCHIVED_STATUSES.forEach { st ->
        FilterChip(
          label = StatusDisplay.label(st),
          selected = st in state.filter.statuses,
          onClick = {
            val next = state.filter.statuses.toMutableSet()
            if (!next.remove(st)) next.add(st)
            vm.setStatuses(next)
          },
        )
      }
      FilterChip(
        label = "Archived",
        selected = state.filter.showArchived,
        onClick = { vm.setShowArchived(!state.filter.showArchived) },
      )
    }

    // Priority
    SectionLabel(text = "Priority", modifier = Modifier.padding(top = spacing.md, bottom = spacing.base))
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
      TaskFilter.ALL_PRIORITIES.forEach { p ->
        FilterChip(
          label = PriorityDisplay.label(p),
          selected = p in state.filter.priorities,
          onClick = {
            val next = state.filter.priorities.toMutableSet()
            if (!next.remove(p)) next.add(p)
            vm.setPriorities(next)
          },
        )
      }
    }

    // Project (shown only when there are projects)
    if (state.projects.isNotEmpty()) {
      SectionLabel(
        text = "Project",
        modifier = Modifier.padding(top = spacing.md, bottom = spacing.base),
      )
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
      ) {
        state.projects.forEach { proj ->
          FilterChip(
            label = proj.name,
            selected = proj.id in (state.filter.projectIds ?: emptySet()),
            onClick = {
              val next = (state.filter.projectIds ?: emptySet()).toMutableSet()
              if (!next.remove(proj.id)) next.add(proj.id)
              vm.setProjectIds(if (next.isEmpty()) null else next)
            },
          )
        }
      }
    }
  }
}

// ────────────────────────────────────────────────────────────────────────────
// Sort sheet
// ────────────────────────────────────────────────────────────────────────────

@Composable
private fun SortSheet(state: BrowseUiState, vm: BrowseViewModel, onDismiss: () -> Unit) {
  val spacing = LocalTmapSpacing.current

  SheetScaffold(onDismiss = onDismiss, title = "Sort by") {
    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
      SortField.entries.forEach { f ->
        val isSelected = state.filter.sortField == f
        val dirLabel = if (isSelected) {
          if (state.filter.sortDirection == SortDirection.Asc) " ↑" else " ↓"
        } else ""
        Chip(
          label = f.name + dirLabel,
          selected = isSelected,
          onClick = { vm.setSort(f) },
          modifier = Modifier.fillMaxWidth(),
        )
      }
    }
  }
}

// ────────────────────────────────────────────────────────────────────────────
// Group sheet
// ────────────────────────────────────────────────────────────────────────────

@Composable
private fun GroupSheet(state: BrowseUiState, vm: BrowseViewModel, onDismiss: () -> Unit) {
  val spacing = LocalTmapSpacing.current

  SheetScaffold(onDismiss = onDismiss, title = "Group by") {
    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
      GroupBy.entries.forEach { g ->
        Chip(
          label = g.name,
          selected = state.filter.groupBy == g,
          onClick = { vm.setGroupBy(g); onDismiss() },
          modifier = Modifier.fillMaxWidth(),
        )
      }
    }
  }
}
