package net.qmindtech.tmap.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import net.qmindtech.tmap.data.local.entities.TaskEntity
import net.qmindtech.tmap.util.Clock
import java.time.ZoneId

/**
 * Arms / cancels per-task exact alarms (spec §6). One alarm per task id; re-arming replaces it
 * because the PendingIntent uses a stable per-task request code (FLAG_UPDATE_CURRENT).
 *
 *  - arm(task): compute trigger via ReminderTrigger; no-op if null (done/deleted-shape/no-anchor)
 *    or in the past (relative to clock.now()). Exact via setExactAndAllowWhileIdle when permitted;
 *    inexact setAndAllowWhileIdle fallback when exact alarms are denied (Android 12+ policy).
 *  - cancel(taskId): cancels the matching PendingIntent + AlarmManager entry.
 */
class AlarmReminderScheduler(
    private val context: Context,
    private val alarmManager: AlarmManager,
    private val clock: Clock,
) : ReminderScheduler {

    private val zone: ZoneId get() = clock.zone()

    override fun arm(task: TaskEntity) {
        val triggerAt = ReminderTrigger.computeTriggerAt(task, zone) ?: return
        if (!triggerAt.isAfter(clock.now())) return // past or exactly-now → drop

        val pi = pendingIntent(task.id, task.title, create = true)!! // create=true always returns non-null
        val triggerMs = triggerAt.toEpochMilli()
        if (canScheduleExact()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
        } else {
            // Exact alarms denied (Android 12+). Inexact still fires, just not to-the-minute.
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
        }
    }

    override fun cancel(taskId: String) {
        val pi = pendingIntent(taskId, title = null, create = false) ?: return
        alarmManager.cancel(pi)
        pi.cancel()
    }

    /** API < 31: exact alarms are always allowed. API 31+: gated by the SCHEDULE_EXACT_ALARM grant. */
    override fun canScheduleExact(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) alarmManager.canScheduleExactAlarms() else true

    /**
     * Stable per-task PendingIntent. Request code = taskId.hashCode() so re-arm replaces and cancel
     * matches. For cancel we use FLAG_NO_CREATE: a null return means nothing was scheduled.
     */
    private fun pendingIntent(taskId: String, title: String?, create: Boolean): PendingIntent? {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_FIRE
            putExtra(AlarmReceiver.EXTRA_TASK_ID, taskId)
            if (title != null) putExtra(AlarmReceiver.EXTRA_TITLE, title)
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or
            (if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE)
        return PendingIntent.getBroadcast(context, taskId.hashCode(), intent, flags)
    }
}
