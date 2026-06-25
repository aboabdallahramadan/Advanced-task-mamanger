package net.qmindtech.tmap.ui.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.local.entities.ProjectEntity
import net.qmindtech.tmap.data.repository.ProjectRepository
import net.qmindtech.tmap.data.repository.TaskEdit
import net.qmindtech.tmap.data.repository.TaskRepository
import net.qmindtech.tmap.ui.components.TaskUi
import net.qmindtech.tmap.ui.components.toUi
import net.qmindtech.tmap.util.Clock
import javax.inject.Inject

data class InboxUiState(
    val loading: Boolean = true,
    val tasks: List<TaskUi> = emptyList(),
    val count: Int = 0,
    val projects: List<ProjectEntity> = emptyList(),
)

@HiltViewModel
class InboxViewModel @Inject constructor(
    private val taskRepo: TaskRepository,
    private val projectRepo: ProjectRepository,
    private val clock: Clock,
) : ViewModel() {

    val uiState: StateFlow<InboxUiState> = combine(
        taskRepo.observeByStatus(TaskStatus.Inbox),
        projectRepo.observeAll(),
    ) { tasks, projects ->
        val projectsById = projects.associateBy { it.id }
        val uis = tasks.map { it.toUi(projectsById[it.projectId], zone = clock.zone()) }
        InboxUiState(
            loading = false,
            tasks = uis,
            count = uis.size,
            projects = projects,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InboxUiState())

    fun schedule(taskId: String) {
        viewModelScope.launch {
            taskRepo.update(taskId, TaskEdit(status = TaskStatus.Planned, plannedDate = clock.today()))
        }
    }

    fun backlog(taskId: String) {
        viewModelScope.launch {
            taskRepo.update(taskId, TaskEdit(status = TaskStatus.Backlog))
        }
    }

    fun assignProject(taskId: String, projectId: String) {
        viewModelScope.launch {
            taskRepo.update(taskId, TaskEdit(projectId = projectId))
        }
    }

    fun delete(taskId: String) {
        viewModelScope.launch {
            taskRepo.delete(taskId)
        }
    }
}
