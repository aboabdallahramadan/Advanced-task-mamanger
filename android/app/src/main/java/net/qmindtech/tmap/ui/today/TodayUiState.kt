package net.qmindtech.tmap.ui.today

import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.local.entities.ProjectEntity
import net.qmindtech.tmap.data.local.entities.TaskEntity
import net.qmindtech.tmap.ui.components.TaskUi
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

// ---------------------------------------------------------------------------
// Shared list-item type used by TodayViewModel and other list screens.
// ---------------------------------------------------------------------------

data class TaskListItem(val task: TaskEntity, val projectName: String?)

fun timeOrderToday(tasks: List<TaskEntity>, projects: List<ProjectEntity>): List<TaskListItem> {
  val names = projects.associate { it.id to it.name }
  val far = Instant.MAX
  return tasks
    .sortedWith(
      compareBy<TaskEntity> { it.scheduledStart ?: far }
        .thenBy { it.plannedDate ?: LocalDate.MAX }
        .thenBy { it.createdAt }
    )
    .map { TaskListItem(it, it.projectId?.let { pid -> names[pid] }) }
}

enum class TodaySection { Morning, Afternoon, Evening, Other }

data class TodayGroup(val section: TodaySection, val tasks: List<TaskUi>)

data class TodayProgress(val done: Int, val total: Int, val minutesLeft: Int) {
  val fraction: Float get() = if (total == 0) 0f else done.toFloat() / total
}

enum class TodayMode { List, Timeline }

data class TodayUiState(
  val loading: Boolean = true,
  val dateEyebrow: String = "",
  val greeting: String = "",
  val groups: List<TodayGroup> = emptyList(),
  val progress: TodayProgress = TodayProgress(0, 0, 0),
  val mode: TodayMode = TodayMode.List,
)

private const val DEFAULT_TASK_MINUTES = 30

private fun sectionFor(start: LocalTime?): TodaySection = when {
  start == null -> TodaySection.Other
  start.hour < 12 -> TodaySection.Morning
  start.hour < 17 -> TodaySection.Afternoon
  else -> TodaySection.Evening
}

fun groupToday(tasks: List<TaskUi>, scheduledStarts: Map<String, LocalTime?>): List<TodayGroup> {
  val buckets = LinkedHashMap<TodaySection, MutableList<TaskUi>>()
  for (t in tasks) {
    val section = sectionFor(scheduledStarts[t.id])
    buckets.getOrPut(section) { mutableListOf() }.add(t)
  }
  return listOf(TodaySection.Morning, TodaySection.Afternoon, TodaySection.Evening, TodaySection.Other)
    .mapNotNull { sec -> buckets[sec]?.let { TodayGroup(sec, it) } }
}

fun computeProgress(tasks: List<TaskEntity>): TodayProgress {
  val done = tasks.count { it.status == TaskStatus.Done }
  val minutesLeft = tasks
    .filter { it.status != TaskStatus.Done }
    .sumOf { it.durationMinutes ?: DEFAULT_TASK_MINUTES }
  return TodayProgress(done = done, total = tasks.size, minutesLeft = minutesLeft)
}

fun greetingFor(time: LocalTime): String = when {
  time.hour < 12 -> "Good morning"
  time.hour < 18 -> "Good afternoon"
  else -> "Good evening"
}

private val EYEBROW_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.getDefault())

fun eyebrowFor(date: LocalDate): String = date.format(EYEBROW_FMT).uppercase(Locale.getDefault())
