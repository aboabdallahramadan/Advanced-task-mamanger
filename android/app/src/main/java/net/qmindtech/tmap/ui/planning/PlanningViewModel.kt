package net.qmindtech.tmap.ui.planning

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
import net.qmindtech.tmap.data.local.entities.ProjectEntity
import net.qmindtech.tmap.data.local.entities.SettingEntity
import net.qmindtech.tmap.data.local.entities.TaskEntity
import net.qmindtech.tmap.data.repository.DailyPlanRepository
import net.qmindtech.tmap.data.repository.ProjectRepository
import net.qmindtech.tmap.data.repository.SettingsRepository
import net.qmindtech.tmap.data.repository.TaskEdit
import net.qmindtech.tmap.data.repository.TaskRepository
import net.qmindtech.tmap.ui.components.parseProjectColor
import net.qmindtech.tmap.util.Clock
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class PlanningViewModel @Inject constructor(
  private val taskRepo: TaskRepository,
  private val projectRepo: ProjectRepository,
  private val dailyPlanRepo: DailyPlanRepository,
  private val settingsRepo: SettingsRepository,
  private val clock: Clock,
) : ViewModel() {

  private val today: LocalDate = clock.today()
  private val yesterday: LocalDate = today.minusDays(1)

  // Local ritual state not derived from Room: current step, the ordered pick set, the committed flag.
  private val step = MutableStateFlow(PlanningStep.Reflect)
  private val picked = MutableStateFlow<List<String>>(emptyList())
  private val committed = MutableStateFlow(false)

  val uiState: StateFlow<PlanningUiState> =
    combine(
      taskRepo.observeToday(yesterday),
      taskRepo.observeByStatus(TaskStatus.Inbox),
      projectRepo.observeAll(),
      settingsRepo.observe(),
      combine(step, picked, committed) { s, p, c -> Triple(s, p, c) },
    ) { yTasks, inboxTasks, projects, settings, local ->
      project(yTasks, inboxTasks, projects, settings, local.first, local.second, local.third)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlanningUiState())

  private fun project(
    yesterdayTasks: List<TaskEntity>,
    inboxTasks: List<TaskEntity>,
    projects: List<ProjectEntity>,
    settings: List<SettingEntity>,
    step: PlanningStep,
    pickedIds: List<String>,
    committed: Boolean,
  ): PlanningUiState {
    val byId = projects.associateBy { it.id }
    fun item(t: TaskEntity): PlanItemUi {
      val p = t.projectId?.let { byId[it] }
      return PlanItemUi(
        id = t.id, title = t.title, projectName = p?.name,
        projectColor = parseProjectColor(p?.color), durationMinutes = t.durationMinutes,
        done = t.status == TaskStatus.Done, added = pickedIds.contains(t.id),
      )
    }
    val (done, undone) = yesterdayTasks.partition { it.status == TaskStatus.Done }
    // The capacity sum needs the actual entities behind the picked ids (carry-over + inbox).
    val pool = (yesterdayTasks + inboxTasks).associateBy { it.id }
    val pickedTasks = pickedIds.mapNotNull { pool[it] }
    return PlanningUiState(
      loading = false,
      step = step,
      yesterdayDone = done.map(::item),
      yesterdayUndone = undone.map(::item),
      inbox = inboxTasks.map(::item),
      carryOver = undone.map(::item),
      inboxPicks = inboxTasks.map(::item),
      pickedIds = pickedIds,
      plannedMinutes = capacityOf(pickedTasks),
      workdayMinutes = workdayMinutes(settings),
      committed = committed,
    )
  }

  fun next() { step.value = step.value.next() }
  fun back() { step.value = step.value.back() }

  fun toggleAdd(taskId: String) {
    picked.value = if (picked.value.contains(taskId)) {
      picked.value - taskId
    } else {
      picked.value + taskId
    }
  }

  fun scheduleFromInbox(taskId: String) {
    viewModelScope.launch {
      taskRepo.update(taskId, TaskEdit(status = TaskStatus.Planned, plannedDate = today))
    }
    if (!picked.value.contains(taskId)) picked.value = picked.value + taskId
  }

  fun sendToBacklog(taskId: String) {
    viewModelScope.launch { taskRepo.update(taskId, TaskEdit(status = TaskStatus.Backlog)) }
  }

  fun assignProject(taskId: String, projectId: String) {
    viewModelScope.launch { taskRepo.update(taskId, TaskEdit(projectId = projectId)) }
  }

  fun deleteTask(taskId: String) {
    viewModelScope.launch { taskRepo.delete(taskId) }
    picked.value = picked.value - taskId
  }

  fun assignTime(taskId: String, start: Instant, end: Instant) {
    viewModelScope.launch {
      taskRepo.update(
        taskId,
        TaskEdit(scheduledStart = start, scheduledEnd = end, status = TaskStatus.Scheduled),
      )
    }
  }

  fun commit(onDone: () -> Unit = {}) {
    val ids = picked.value
    val minutes = uiState.value.plannedMinutes
    viewModelScope.launch {
      ids.forEach { id ->
        taskRepo.update(id, TaskEdit(status = TaskStatus.Planned, plannedDate = today))
      }
      // DailyPlanRepository.upsert stamps committedAt internally (write-through → outbox, offline-safe).
      dailyPlanRepo.upsert(today, ids, minutes)
      committed.value = true
      onDone()
    }
  }
}
