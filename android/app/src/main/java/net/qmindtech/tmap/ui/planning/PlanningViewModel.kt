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
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

/** Compact date for "Everything else" locator hints, e.g. "Jun 30". Fixed locale for stable output. */
private val HINT_DATE = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)

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
      // One flow for the whole task pool; every section is derived from it in [project]. This keeps
      // the combine within its 5-arg limit and gives the PickToday picker the full pool (including
      // the "Everything else" tasks that live outside Inbox/Backlog/yesterday).
      taskRepo.observeAll(),
      projectRepo.observeAll(),
      settingsRepo.observe(),
      combine(step, picked, committed) { s, p, c -> Triple(s, p, c) },
    ) { allTasks, projects, settings, local ->
      project(allTasks, projects, settings, local.first, local.second, local.third)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlanningUiState())

  private fun project(
    allTasks: List<TaskEntity>,
    projects: List<ProjectEntity>,
    settings: List<SettingEntity>,
    step: PlanningStep,
    pickedIds: List<String>,
    committed: Boolean,
  ): PlanningUiState {
    val byId = projects.associateBy { it.id }
    fun item(t: TaskEntity, hint: String? = null): PlanItemUi {
      val p = t.projectId?.let { byId[it] }
      return PlanItemUi(
        id = t.id, title = t.title, projectName = p?.name,
        projectColor = parseProjectColor(p?.color), durationMinutes = t.durationMinutes,
        done = t.status == TaskStatus.Done, added = pickedIds.contains(t.id),
        hint = hint,
      )
    }

    val yesterdayTasks = allTasks.filter { it.plannedDate == yesterday }
    val inboxTasks = allTasks.filter { it.status == TaskStatus.Inbox }
    val backlogTasks = allTasks.filter { it.status == TaskStatus.Backlog }
    val (done, undone) = yesterdayTasks.partition { it.status == TaskStatus.Done }

    // "Everything else": actionable tasks that live outside the sections above — Planned for other
    // days, Scheduled, or undated — so they can be pulled into today. Excludes anything already
    // shown (carry-over/inbox/backlog), anything already planned for today, and Done/Archived.
    val shownIds = (undone + inboxTasks + backlogTasks).mapTo(mutableSetOf()) { it.id }
    val everythingElse = allTasks.filter {
      it.status != TaskStatus.Done &&
        it.status != TaskStatus.Archived &&
        it.id !in shownIds &&
        it.plannedDate != today
    }

    // Group "Everything else" by project: project order (projects arrive ordered by rank from the
    // DAO), "No Project" last and only when non-empty. Item order within a group is preserved.
    val elseByProject = everythingElse.groupBy { it.projectId }
    val everythingElseGroups = buildList {
      for (p in projects) {
        val bucket = elseByProject[p.id]
        if (!bucket.isNullOrEmpty()) {
          add(
            PlanProjectGroupUi(
              projectId = p.id,
              projectName = p.name,
              projectColor = parseProjectColor(p.color),
              items = bucket.map { item(it, hint = hintFor(it)) },
            ),
          )
        }
      }
      val noProject = everythingElse.filter { it.projectId == null || byId[it.projectId] == null }
      if (noProject.isNotEmpty()) {
        add(
          PlanProjectGroupUi(
            projectId = null,
            projectName = null,
            projectColor = null,
            items = noProject.map { item(it, hint = hintFor(it)) },
          ),
        )
      }
    }

    // Capacity needs the entities behind the picked ids; the whole pool covers every pickable task.
    val pool = allTasks.associateBy { it.id }
    val pickedTasks = pickedIds.mapNotNull { pool[it] }
    return PlanningUiState(
      loading = false,
      step = step,
      yesterdayDone = done.map { item(it) },
      yesterdayUndone = undone.map { item(it) },
      inbox = inboxTasks.map { item(it) },
      carryOver = undone.map { item(it) },
      inboxPicks = inboxTasks.map { item(it) },
      backlogPicks = backlogTasks.map { item(it) },
      everythingElse = everythingElseGroups,
      pickedIds = pickedIds,
      plannedMinutes = capacityOf(pickedTasks),
      workdayMinutes = workdayMinutes(settings),
      committed = committed,
    )
  }

  /**
   * Short locator label for an "Everything else" row, e.g. "Planned · Jun 30" or "Scheduled · Jun 23".
   * Uses [TaskEntity.plannedDate] when present, else the local date of a [TaskEntity.scheduledStart];
   * undated tasks show just the verb. Fixed [Locale.ENGLISH] so the ritual reads consistently.
   */
  private fun hintFor(t: TaskEntity): String {
    val verb = if (t.status == TaskStatus.Scheduled) "Scheduled" else "Planned"
    val date = t.plannedDate ?: t.scheduledStart?.atZone(clock.zone())?.toLocalDate()
    return if (date != null) "$verb · ${date.format(HINT_DATE)}" else verb
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
