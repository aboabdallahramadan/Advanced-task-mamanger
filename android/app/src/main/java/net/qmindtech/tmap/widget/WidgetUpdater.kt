package net.qmindtech.tmap.widget

import android.content.Context
import androidx.glance.appwidget.updateAll

/**
 * Refreshes all four Glance widgets from their single Room data source. Called:
 *   (1) by [net.qmindtech.tmap.data.sync.PullRunner] after a successful pull (remote → widget), and
 *   (2) by [net.qmindtech.tmap.data.repository.TaskRepositoryImpl] after a today-affecting write
 *       (local check-off/edit → optimistic widget refresh).
 * The manifest's periodic update interval serves as a fallback for missed wakes.
 *
 * No-op safe: if a widget type has no placed instances, GlanceAppWidgetManager.getGlanceIds
 * returns an empty list and nothing is updated.
 */
object WidgetUpdater {

    /**
     * Triggers a re-render of every placed instance of all four widget types.
     * Each widget re-reads Room in its own [androidx.glance.appwidget.GlanceAppWidget.provideGlance]
     * — no network I/O happens here. Suspend — call from a coroutine.
     */
    suspend fun updateAll(context: Context) {
        TodayAgendaWidget().updateAll(context)
        QuickCaptureWidget().updateAll(context)
        UpNextFocusWidget().updateAll(context)
        ProgressStreakWidget().updateAll(context)
    }
}
