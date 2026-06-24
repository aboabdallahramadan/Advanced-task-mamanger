package net.qmindtech.tmap.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * System entry point for the Quick Capture widget. The AppWidget host calls this receiver on
 * APPWIDGET_UPDATE and other lifecycle events; it delegates rendering to [QuickCaptureWidget].
 */
class QuickCaptureWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuickCaptureWidget()
}
