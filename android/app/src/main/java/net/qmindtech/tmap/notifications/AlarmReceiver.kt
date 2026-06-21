package net.qmindtech.tmap.notifications

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import net.qmindtech.tmap.MainActivity

/**
 * Posts a reminder notification when an armed alarm fires. Tapping it deep-links to the task editor
 * (scheme tmap://task/{taskId}) by launching MainActivity; P5's NavHost binds that Uri to the
 * Routes.TaskEditor route. Notification id = taskId.hashCode() so per-task posts don't collide.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return
        val rawTitle = intent.getStringExtra(EXTRA_TITLE)
        val title = if (rawTitle.isNullOrBlank()) "Task reminder" else rawTitle

        NotificationChannels.ensureCreated(context)

        val contentIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = deepLinkUri(taskId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            context,
            taskId.hashCode(),
            contentIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(context, NotificationChannels.REMINDERS_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText("Reminder")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        val nm = context.getSystemService(NotificationManager::class.java)
        nm.notify(taskId.hashCode(), notification)
    }

    companion object {
        const val ACTION_FIRE = "net.qmindtech.tmap.action.REMINDER_FIRE"
        const val EXTRA_TASK_ID = "extra_task_id"
        const val EXTRA_TITLE = "extra_title"
        const val DEEPLINK_SCHEME = "tmap"
        const val DEEPLINK_HOST = "task"

        /** tmap://task/{taskId} — bound to Routes.TaskEditor by the NavHost (P5). */
        fun deepLinkUri(taskId: String): Uri =
            Uri.Builder().scheme(DEEPLINK_SCHEME).authority(DEEPLINK_HOST).appendPath(taskId).build()
    }
}
