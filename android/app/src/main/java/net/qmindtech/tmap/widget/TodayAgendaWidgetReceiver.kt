package net.qmindtech.tmap.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * System entry point for the Today Agenda widget. The AppWidget host calls this receiver on
 * APPWIDGET_UPDATE and other lifecycle events; it delegates rendering to [TodayAgendaWidget].
 */
class TodayAgendaWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayAgendaWidget()
}
