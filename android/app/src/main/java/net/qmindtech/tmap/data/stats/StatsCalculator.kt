package net.qmindtech.tmap.data.stats

import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.local.entities.DailyPlanEntity
import net.qmindtech.tmap.data.local.entities.FocusSessionEntity
import net.qmindtech.tmap.data.local.entities.TaskEntity
import net.qmindtech.tmap.util.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject

/**
 * Pure, clock-injected derivation of the four You-screen / widget stats (spec §6.10). Every method
 * is a side-effect-free function of its input list + the injected [clock] — so each is exhaustively
 * unit-testable without Room or coroutines. The ViewModel (P9.3) feeds it Room snapshots; the
 * Progress/Streak widget (P8) reuses the same calculator over the same data.
 */
class StatsCalculator @Inject constructor(private val clock: Clock) {

    /** Fraction (0f..1f) of TODAY's planned, non-template tasks that are [TaskStatus.Done]. */
    fun todayProgress(tasks: List<TaskEntity>): Float {
        val today = clock.today()
        val plannedToday = tasks.filter { !it.isRecurrenceTemplate && it.plannedDate == today }
        if (plannedToday.isEmpty()) return 0f
        val done = plannedToday.count { it.status == TaskStatus.Done }
        return done.toFloat() / plannedToday.size.toFloat()
    }

    /** The Monday→Sunday ISO week (inclusive) containing [clock.today]. */
    private fun isoWeek(): ClosedRange<LocalDate> {
        val today = clock.today()
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return monday..monday.plusDays(6)
    }

    /** Count of [TaskStatus.Done] tasks whose completedAt date (in clock.zone()) is in this ISO week. */
    fun doneThisWeek(tasks: List<TaskEntity>): Int {
        val week = isoWeek()
        return tasks.count { t ->
            t.status == TaskStatus.Done &&
                t.completedAt?.atZone(clock.zone())?.toLocalDate()?.let { it in week } == true
        }
    }

    /** Sum of `minutes` over non-tombstoned focus sessions dated within this ISO week. */
    fun focusMinutesThisWeek(sessions: List<FocusSessionEntity>): Int {
        val week = isoWeek()
        return sessions
            .filter { it.deletedAt == null && it.date in week }
            .sumOf { it.minutes }
    }

    /**
     * Consecutive "active" days ending at today, with a today-grace: an empty current day does not
     * break a real streak — if today is inactive we anchor at yesterday. A day is active when it has
     * a live committed DailyPlan OR a Done task completed on that date (in clock.zone()).
     */
    fun dayStreak(tasks: List<TaskEntity>, plans: List<DailyPlanEntity>): Int {
        val active = buildSet {
            plans.forEach { if (it.deletedAt == null) add(it.date) }
            tasks.forEach { t ->
                if (t.status == TaskStatus.Done) {
                    t.completedAt?.atZone(clock.zone())?.toLocalDate()?.let { add(it) }
                }
            }
        }
        val today = clock.today()
        val anchor = when {
            today in active -> today
            today.minusDays(1) in active -> today.minusDays(1)
            else -> return 0
        }
        var count = 0
        var day = anchor
        while (day in active) {
            count++
            day = day.minusDays(1)
        }
        return count
    }
}
