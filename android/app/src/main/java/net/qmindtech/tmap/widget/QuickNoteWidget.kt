package net.qmindtech.tmap.widget

import android.content.Context
import android.content.Intent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
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
import net.qmindtech.tmap.R

/**
 * Quick-add Note Glance widget. A permanent 1-row note bar:
 *   - "✎" tile + "Add a note…" → [NoteCaptureTrampolineActivity] (typed overlay).
 *   - Mic icon at the end → [NoteCaptureTrampolineActivity] with voice=true (system recognizer).
 *
 * Mirrors [QuickCaptureWidget] (which captures tasks). Note capture works offline; the
 * [SignedOutState] affordance is shown only for visual consistency with the other widgets.
 */
class QuickNoteWidget : GlanceAppWidget() {

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
                            .clickable(actionStartActivity(buildNoteCaptureIntent(context, voice = false))),
                    ) {
                        // Amber "✎" tile
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = GlanceModifier
                                .size(26.dp)
                                .cornerRadius(8.dp)
                                .background(WidgetColors.accent),
                        ) {
                            Text(
                                text = "✎",
                                style = TextStyle(color = WidgetColors.onAccent, fontSize = 16.sp),
                            )
                        }
                        Spacer(modifier = GlanceModifier.width(11.dp))
                        // "Add a note…" hint — takes remaining width
                        Text(
                            text = "Add a note…",
                            modifier = GlanceModifier.defaultWeight(),
                            style = TextStyle(color = WidgetColors.textSecondary, fontSize = 13.sp),
                        )
                        // Mic icon — separate click target for voice capture
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = GlanceModifier
                                .size(28.dp)
                                .padding(2.dp)
                                .clickable(actionStartActivity(buildNoteCaptureIntent(context, voice = true))),
                        ) {
                            Image(
                                provider = ImageProvider(R.drawable.ic_mic),
                                contentDescription = "Add note by voice",
                                modifier = GlanceModifier.size(18.dp),
                                colorFilter = ColorFilter.tint(WidgetColors.textSecondary),
                            )
                        }
                    }
                }
            }
        }
    }

    companion object {
        /**
         * Builds the trampoline launch intent. `internal` so the launch-isolation flags can be
         * asserted in unit tests without a live widget host. Always targets
         * [NoteCaptureTrampolineActivity] with NEW_TASK + MULTIPLE_TASK so a tap lands in a fresh,
         * isolated task (never adopting the foreground app's task).
         */
        internal fun buildNoteCaptureIntent(context: Context, voice: Boolean): Intent =
            Intent(context, NoteCaptureTrampolineActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = WidgetLinks.noteCapture(voice = voice)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            }
    }
}
