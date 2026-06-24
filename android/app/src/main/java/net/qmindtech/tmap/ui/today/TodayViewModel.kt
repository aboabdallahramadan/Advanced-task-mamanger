package net.qmindtech.tmap.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.local.entities.TaskEntity
import net.qmindtech.tmap.data.repository.ProjectRepository
import net.qmindtech.tmap.data.repository.TaskEdit
import net.qmindtech.tmap.data.repository.TaskRepository
import net.qmindtech.tmap.ui.components.toUi
import net.qmindtech.tmap.util.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject

@HiltViewModel
class TodayViewModel @Inject constructor(
  private val taskRepo: TaskRepository,
  private val projectRepo: ProjectRepository,
  private val clock: Clock,
) : ViewModel() {

  private val mode = MutableStateFlow(TodayMode.List)

  private val todayTasksFlow = taskRepo.observeToday(clock.today())

  val uiState: StateFlow<TodayUiState> =
    combine(
      todayTasksFlow,
      projectRepo.observeAll(),
      mode,
    ) { tasks, projects, m ->
      val projectsById = projects.associateBy { it.id }
      val sorted = tasks.sortedWith(
        compareBy<TaskEntity>({ it.rank ?: "zzzzzz" }, { it.scheduledStart ?: Instant.MAX }, { it.createdAt }),
      )
      val starts = sorted.associate { it.id to it.scheduledStart?.atZone(clock.zone())?.toLocalTime() }
      val uis = sorted.map { it.toUi(projectsById[it.projectId], zone = clock.zone()) }
      val nowTime = clock.now().atZone(clock.zone()).toLocalTime()
      // Build timeline blocks: only tasks that have a scheduled start time.
      val timelineBlocks = sorted.mapNotNull { task ->
        val startTime = task.scheduledStart?.atZone(clock.zone())?.toLocalTime() ?: return@mapNotNull null
        val ui = task.toUi(projectsById[task.projectId], zone = clock.zone())
        TimelineBlock(ui = ui, start = startTime, durationMin = task.durationMinutes ?: 60)
      }
      TodayUiState(
        loading = false,
        dateEyebrow = eyebrowFor(clock.today()),
        greeting = greetingFor(nowTime),
        groups = groupToday(uis, starts),
        timelineBlocks = timelineBlocks,
        progress = computeProgress(tasks),
        mode = m,
      )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayUiState())

  fun setMode(mode: TodayMode) { this.mode.value = mode }

  fun toggleComplete(taskId: String) {
    viewModelScope.launch {
      val task = todayTasksFlow.first().firstOrNull { it.id == taskId }
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

  fun timeblock(taskId: String, start: LocalTime) {
    viewModelScope.launch {
      val task = todayTasksFlow.first().firstOrNull { it.id == taskId } ?: return@launch
      val duration = task.durationMinutes ?: 60
      val startInstant = clock.today().atTime(start).atZone(clock.zone()).toInstant()
      val endInstant = startInstant.plus(Duration.ofMinutes(duration.toLong()))
      taskRepo.update(
        taskId,
        TaskEdit(
          scheduledStart = startInstant,
          scheduledEnd = endInstant,
          durationMinutes = duration,
        ),
      )
    }
  }
}
