package net.qmindtech.tmap.widget

import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.local.entities.TaskEntity
import java.time.LocalDate

/**
 * Pure widget stats. P9 will ship a richer `StatsCalculator`; these two functions intentionally
 * mirror its `todayProgress(tasks)` / `dayStreak(...)` so the swap is a one-line change in
 * [WidgetRepository]. No Android dependencies → unit-testable on the JVM.
 */
object WidgetStats {

    /** Fraction (0f..1f) of today's non-archived tasks that are Done. 0f when the day is empty. */
    fun todayProgress(tasks: List<TaskEntity>): Float {
        val counted = tasks.filter { it.status != TaskStatus.Archived }
        if (counted.isEmpty()) return 0f
        val done = counted.count { it.status == TaskStatus.Done }
        return done.toFloat() / counted.size.toFloat()
    }

    /**
     * Longest unbroken run of completed days ending at [today] (or [today]-1 if nothing is done
     * today yet — an in-progress day must not zero the streak). [completionByDate] maps a day to
     * whether at least one planned task was completed that day.
     */
    fun dayStreak(completionByDate: Map<LocalDate, Boolean>, today: LocalDate): Int {
        val anchor = when {
            completionByDate[today] == true -> today
            completionByDate[today.minusDays(1)] == true -> today.minusDays(1)
            else -> return 0
        }
        var streak = 0
        var cursor = anchor
        while (completionByDate[cursor] == true) {
            streak++
            cursor = cursor.minusDays(1)
        }
        return streak
    }
}
