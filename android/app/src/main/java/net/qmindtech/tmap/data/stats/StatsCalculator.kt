package net.qmindtech.tmap.data.stats

import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.local.entities.DailyPlanEntity
import net.qmindtech.tmap.data.local.entities.FocusSessionEntity
import net.qmindtech.tmap.data.local.entities.TaskEntity
import net.qmindtech.tmap.util.Clock
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
}
