package net.qmindtech.tmap.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * System entry point for the Up Next / Focus widget (P8.8). The AppWidget host calls this receiver
 * on APPWIDGET_UPDATE and other lifecycle events; it delegates rendering to [UpNextFocusWidget].
 */
class UpNextFocusWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = UpNextFocusWidget()
}
