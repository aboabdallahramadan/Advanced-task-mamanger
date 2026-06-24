package net.qmindtech.tmap.ui.focus

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import net.qmindtech.tmap.data.repository.ProjectRepository
import net.qmindtech.tmap.data.repository.TaskRepository
import net.qmindtech.tmap.util.Clock
import javax.inject.Inject

/**
 * Maps the @Singleton FocusController's [FocusState] to [FocusUiState] and owns the back-to-back
 * session queue (the controller itself is queue-agnostic, spec §6.5). The bound task's title is
 * resolved from the task repo; the project name comes through the controller's [FocusState.project]
 * snapshot (set at start).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FocusViewModel @Inject constructor(
    private val controller: FocusController,
    private val taskRepo: TaskRepository,
    projectRepo: ProjectRepository,
    private val clock: Clock,
) : ViewModel() {

    private val queue = MutableStateFlow<List<String>>(emptyList())

    val uiState: StateFlow<FocusUiState> =
        combine(
            controller.state,
            controller.state.flatMapLatest { s ->
                if (s.taskId == null) flowOf(null) else taskRepo.observe(s.taskId)
            },
            queue,
        ) { state, task, q ->
            FocusUiState(
                phase = state.phase,
                taskTitle = task?.title,
                project = state.project,
                progress = state.elapsedFraction,
                remainingLabel = mmss(state.remainingSeconds),
                ofLabel = "of ${mmss(state.lengthMin * 60)}",
                completedSessions = state.completedSessions,
                totalSessions = state.totalSessions,
                queuedCount = q.size,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, FocusUiState())

    fun start(taskId: String?, project: String, lengthMin: Int = 25, queue: List<String> = emptyList()) {
        this.queue.value = queue
        controller.start(taskId, project, lengthMin)
    }

    fun pause() = controller.pause()
    fun resume() = controller.resume()
    fun end() = controller.end()

    /** Pop the queue head and begin a fresh interval for it (project snapshot reused). */
    fun advance() {
        val (next, rest) = advanceQueue(queue.value)
        queue.value = rest
        if (next != null) controller.start(next, controller.state.value.project, controller.state.value.lengthMin)
    }

    fun currentTaskIdForTest(): String? = controller.state.value.taskId
}
