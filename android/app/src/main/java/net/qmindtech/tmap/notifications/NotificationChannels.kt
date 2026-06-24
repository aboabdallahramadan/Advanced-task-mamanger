package net.qmindtech.tmap.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/** Notification channels. minSdk 26 ⇒ a channel is always required to post notifications. */
object NotificationChannels {

    const val REMINDERS_ID = "task_reminders"
    const val FOCUS_ID = "focus_session"

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

    /** Silent, low-importance ongoing channel for the focus foreground service (spec §6.5). */
    fun ensureFocusChannelCreated(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            FOCUS_ID,
            "Focus session",
            NotificationManager.IMPORTANCE_LOW, // no sound / no peek — it is a status, not an alert
        ).apply {
            description = "Ongoing notification while a focus timer is running"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }
}
