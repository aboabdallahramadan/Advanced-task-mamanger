package net.qmindtech.tmap.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.local.entities.TaskEntity
import net.qmindtech.tmap.data.repository.ProjectRepository
import net.qmindtech.tmap.data.repository.TaskEdit
import net.qmindtech.tmap.data.repository.TaskRepository
import net.qmindtech.tmap.ui.components.toUi
import net.qmindtech.tmap.util.Clock
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class TodayViewModel @Inject constructor(
  private val taskRepo: TaskRepository,
  private val projectRepo: ProjectRepository,
  private val clock: Clock,
) : ViewModel() {

  private val mode = MutableStateFlow(TodayMode.List)

  // Snapshot of today's raw entities so action handlers can read current status without re-querying.
  // Kept in sync via an eager internal collection (independent of uiState subscribers).
  private val todayTasksFlow = taskRepo.observeToday(clock.today())
  @Volatile private var lastTasks: List<TaskEntity> = emptyList()

  init {
    viewModelScope.launch {
      todayTasksFlow.collect { lastTasks = it }
    }
  }

  val uiState: StateFlow<TodayUiState> =
    combine(
      todayTasksFlow,
      projectRepo.observeAll(),
      mode,
    ) { tasks, projects, m ->
      lastTasks = tasks
      val projectsById = projects.associateBy { it.id }
      val sorted = tasks.sortedWith(
        compareBy<TaskEntity>({ it.rank ?: "zzzzzz" }, { it.scheduledStart ?: Instant.MAX }, { it.createdAt }),
      )
      val starts = sorted.associate { it.id to it.scheduledStart?.atZone(clock.zone())?.toLocalTime() }
      val uis = sorted.map { it.toUi(projectsById[it.projectId]) }
      val nowTime = clock.now().atZone(clock.zone()).toLocalTime()
      TodayUiState(
        loading = false,
        dateEyebrow = eyebrowFor(clock.today()),
        greeting = greetingFor(nowTime),
        groups = groupToday(uis, starts),
        progress = computeProgress(tasks),
        mode = m,
      )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayUiState())

  fun setMode(mode: TodayMode) { this.mode.value = mode }

  fun toggleComplete(taskId: String) {
    val task = lastTasks.firstOrNull { it.id == taskId }
    viewModelScope.launch {
      if (task?.status == TaskStatus.Done) {
        taskRepo.update(taskId, TaskEdit(status = TaskStatus.Planned))
      } else {
        taskRepo.markDone(taskId)
      }
    }
  }

  fun defer(taskId: String) {
    viewModelScope.launch { taskRepo.defer(taskId, clock.today().plusDays(1)) }
  }

  fun delete(taskId: String) {
    viewModelScope.launch { taskRepo.delete(taskId) }
  }

  fun reorder(orderedIds: List<String>) {
    viewModelScope.launch { taskRepo.reorder(orderedIds) }
  }

  fun moveToDay(taskId: String, date: LocalDate) {
    viewModelScope.launch { taskRepo.moveToDay(taskId, date) }
  }
}
