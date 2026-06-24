package net.qmindtech.tmap.ui.focus

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import net.qmindtech.tmap.notifications.FocusNotification
import javax.inject.Inject

/**
 * Foreground service that keeps the process alive while a focus interval runs so the timer survives
 * backgrounding (spec §6.5). It owns no timer state — it observes the @Singleton [FocusController]
 * the screen drives, re-posting the silent ongoing notification each tick, and stops itself when the
 * controller is no longer active.
 */
@AndroidEntryPoint
class FocusService : Service() {

    @Inject lateinit var controller: FocusController

    private val scope = CoroutineScope(SupervisorJob())
    private var collectJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    // POST_NOTIFICATIONS is declared in the manifest; this runs inside a foreground service whose
    // notification was already shown via startForeground — tick-updates don't need a runtime gate.
    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val initial = controller.state.value
        startForegroundCompat(
            FocusNotification.build(this, focusTitle(initial), mmss(initial.remainingSeconds)),
        )
        if (collectJob == null) {
            collectJob = controller.state.onEach { s ->
                if (!s.isActive) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@onEach
                }
                NotificationManagerCompat.from(this).notify(
                    FocusNotification.NOTIFICATION_ID,
                    FocusNotification.build(this, focusTitle(s), mmss(s.remainingSeconds)),
                )
            }.launchIn(scope)
        }
        return START_STICKY
    }

    private fun startForegroundCompat(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                FocusNotification.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(FocusNotification.NOTIFICATION_ID, notification)
        }
    }

    private fun focusTitle(s: FocusState): String =
        if (s.project.isNotBlank()) "Focusing · ${s.project}" else "Focusing"

    override fun onDestroy() {
        collectJob?.cancel()
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            val i = Intent(context, FocusService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FocusService::class.java))
        }
    }
}
