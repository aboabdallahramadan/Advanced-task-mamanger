package net.qmindtech.tmap.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/** Notification channels. minSdk 26 ⇒ a channel is always required to post notifications. */
object NotificationChannels {

    const val REMINDERS_ID = "task_reminders"

    /** Idempotent: re-creating an existing channel id is a no-op for the OS. */
    fun ensureCreated(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            REMINDERS_ID,
            "Task reminders",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Reminders for scheduled, planned, and due tasks"
        }
        nm.createNotificationChannel(channel)
    }
}
