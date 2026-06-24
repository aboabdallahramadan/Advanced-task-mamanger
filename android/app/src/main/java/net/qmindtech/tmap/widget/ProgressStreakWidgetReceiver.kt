package net.qmindtech.tmap.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * System entry point for the Progress & Streak widget (P8.9). The AppWidget host calls this
 * receiver on APPWIDGET_UPDATE and other lifecycle events; it delegates rendering to
 * [ProgressStreakWidget].
 */
class ProgressStreakWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ProgressStreakWidget()
}
