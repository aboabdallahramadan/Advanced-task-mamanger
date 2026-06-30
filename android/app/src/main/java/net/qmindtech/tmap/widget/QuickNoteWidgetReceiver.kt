package net.qmindtech.tmap.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * System entry point for the Quick-add Note widget. The AppWidget host calls this receiver on
 * APPWIDGET_UPDATE and other lifecycle events; it delegates rendering to [QuickNoteWidget].
 */
class QuickNoteWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuickNoteWidget()
}
