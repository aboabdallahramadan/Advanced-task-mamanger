package net.qmindtech.tmap.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives the fired exact alarm and posts the reminder notification.
 * Body implemented + tested in P7.4; the extra keys + action are declared here so
 * AlarmReminderScheduler (P7.3) can build the target PendingIntent.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Implemented in P7.4 (notification post + tap deep-link).
    }

    companion object {
        const val ACTION_FIRE = "net.qmindtech.tmap.action.REMINDER_FIRE"
        const val EXTRA_TASK_ID = "extra_task_id"
        const val EXTRA_TITLE = "extra_title"
    }
}
