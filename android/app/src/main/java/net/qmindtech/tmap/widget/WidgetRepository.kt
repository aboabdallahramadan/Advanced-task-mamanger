package net.qmindtech.tmap.widget

import net.qmindtech.tmap.data.auth.TokenStore
import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.local.dao.ProjectDao
import net.qmindtech.tmap.data.local.dao.TaskDao
import net.qmindtech.tmap.data.local.entities.ProjectEntity
import net.qmindtech.tmap.data.local.entities.TaskEntity
import net.qmindtech.tmap.util.Clock
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/** A single row as the widgets render it (no Compose / no Android types). */
data class WidgetTaskItem(
    val id: String,
    val title: String,
    val timeLabel: String?,
    val projectColor: Long?,
    val isDone: Boolean,
)

/** Everything the four widgets need from one Room read. */
data class WidgetTodayData(
    val signedIn: Boolean,
    val items: List<WidgetTaskItem>,
    val doneCount: Int,
    val totalCount: Int,
    val minutesLeft: Int,
    val progress: Float,
    val streak: Int,
    val nextTask: WidgetTaskItem?,
)

/**
 * Widget-side data provider. Reads the SAME Room DB the app uses, via the existing DAOs, with NO
 * network (spec §8). Built for one-shot reads from a Glance `provideGlance` coroutine / worker.
 *
 * Pure shaping ([buildItems], [minutesLeft]) is in the companion so it is unit-tested on the JVM;
 * the streak/progress math is delegated to [WidgetStats] (P9 will replace it with StatsCalculator).
 */
class WidgetRepository @Inject constructor(
    private val taskDao: TaskDao,
    private val projectDao: ProjectDao,
    private val tokenStore: TokenStore,
    private val clock: Clock,
) {
    suspend fun loadToday(zone: ZoneId = ZoneId.systemDefault()): WidgetTodayData {
        val signedIn = tokenStore.readRefreshToken() != null
        if (!signedIn) {
            return WidgetTodayData(false, emptyList(), 0, 0, 0, 0f, 0, null)
        }
        val today = clock.now().atZone(zone).toLocalDate()
        val tasks = taskDao.getByPlannedDate(today)
        val projectsById = projectDao.getAll().associateBy { it.id }
        val items = buildItems(tasks, projectsById, zone)
        val counted = tasks.filter { it.status != TaskStatus.Archived }
        val doneCount = counted.count { it.status == TaskStatus.Done }
        val completion = taskDao.completionByDate()
            .associate { it.date to (it.anyDone == 1) }
        return WidgetTodayData(
            signedIn = true,
            items = items,
            doneCount = doneCount,
            totalCount = counted.size,
            minutesLeft = minutesLeft(tasks),
            progress = WidgetStats.todayProgress(tasks),
            streak = WidgetStats.dayStreak(completion, today),
            nextTask = items.firstOrNull { !it.isDone },
        )
    }

    companion object {
        private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        /** Today rows in List ordering (rank, nulls last), with a HH:mm label + project color. */
        fun buildItems(
            tasks: List<TaskEntity>,
            projectsById: Map<String, ProjectEntity>,
            zone: ZoneId,
        ): List<WidgetTaskItem> =
            tasks
                .filter { it.status != TaskStatus.Archived }
                .sortedWith(compareBy(nullsLast()) { it.rank })
                .map { t ->
                    WidgetTaskItem(
                        id = t.id,
                        title = t.title,
                        timeLabel = t.scheduledStart
                            ?.atZone(zone)?.toLocalTime()?.format(TIME_FMT),
                        projectColor = t.projectId
                            ?.let { projectsById[it]?.color }
                            ?.let(::parseColor),
                        isDone = t.status == TaskStatus.Done,
                    )
                }

        /** Remaining planned minutes = sum of durations of not-done, non-archived today tasks. */
        fun minutesLeft(tasks: List<TaskEntity>): Int =
            tasks
                .filter { it.status != TaskStatus.Done && it.status != TaskStatus.Archived }
                .sumOf { it.durationMinutes ?: 0 }

        /** "#RRGGBB" → 0xFFRRGGBB Long; null on any malformed value (widget falls back to accent). */
        private fun parseColor(hex: String): Long? = runCatching {
            val clean = hex.removePrefix("#")
            if (clean.length != 6) return null
            0xFF000000L or clean.toLong(16)
        }.getOrNull()
    }
}
