package net.qmindtech.tmap.widget

import android.content.Context
import android.content.Intent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.Text
import androidx.glance.text.TextStyle

/**
 * Quick Capture Glance widget (P8.7). A permanent 1-row capture bar:
 *   - Amber "+" tile → [CaptureTrampolineActivity] with [WidgetLinks.capture] (normal capture).
 *   - Mic glyph at the end → [CaptureTrampolineActivity] with [WidgetLinks.capture(voice=true)]
 *     (launches system speech recognizer; fallback to empty capture if none installed).
 *
 * No sign-in gate is strictly required (capture works offline), but the [SignedOutState] affordance
 * is shown for consistency with the other P8 widgets when the user is not authenticated.
 */
class QuickCaptureWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = widgetEntryPoint(context).widgetRepository().loadToday()
        provideContent {
            WidgetCard {
                if (!data.signedIn) {
                    SignedOutState()
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = GlanceModifier
                            .fillMaxWidth()
                            .clickable(actionStartActivity(captureIntent(context, voice = false))),
                    ) {
                        // Amber "+" tile
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = GlanceModifier
                                .size(26.dp)
                                .cornerRadius(8.dp)
                                .background(WidgetColors.accent),
                        ) {
                            Text(
                                text = "+",
                                style = TextStyle(
                                    color = WidgetColors.onAccent,
                                    fontSize = 18.sp,
                                ),
                            )
                        }
                        Spacer(modifier = GlanceModifier.width(11.dp))
                        // "Add a task…" hint — takes remaining width
                        Text(
                            text = "Add a task…",
                            modifier = GlanceModifier.defaultWeight(),
                            style = TextStyle(
                                color = WidgetColors.textSecondary,
                                fontSize = 13.sp,
                            ),
                        )
                        // Mic glyph — separate click target for voice capture
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = GlanceModifier
                                .size(28.dp)
                                .padding(2.dp)
                                .clickable(actionStartActivity(captureIntent(context, voice = true))),
                        ) {
                            Text(
                                text = "🎙️",
                                style = TextStyle(
                                    color = WidgetColors.textTertiary,
                                    fontSize = 15.sp,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    private fun captureIntent(context: Context, voice: Boolean): Intent =
        Intent(context, CaptureTrampolineActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = WidgetLinks.capture(voice = voice)
        }
}
