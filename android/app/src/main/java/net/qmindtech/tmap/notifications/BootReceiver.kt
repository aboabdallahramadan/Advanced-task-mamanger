package net.qmindtech.tmap.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/**
 * Re-arms all reminder alarms after the device reboots (exact alarms do not survive a reboot).
 * Enqueues RearmWorker off the broadcast so the re-arm DB read runs on WorkManager, not the
 * (time-limited) broadcast thread.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val request = OneTimeWorkRequestBuilder<RearmWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            RearmWorker.WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }
}
