package net.qmindtech.tmap.widget

import android.content.Context
import androidx.glance.appwidget.updateAll

/**
 * Convenience wrapper to trigger a re-render of all pinned TodayAgendaWidget instances.
 * Called from [ToggleTaskAction] after a check-off write so the widget reflects the new state
 * immediately without waiting for the 30-minute periodic update.
 */
object WidgetUpdater {
    /** Re-renders every TodayAgendaWidget on the home screen. Suspend — call from a coroutine. */
    suspend fun updateAll(context: Context) {
        TodayAgendaWidget().updateAll(context)
    }
}
