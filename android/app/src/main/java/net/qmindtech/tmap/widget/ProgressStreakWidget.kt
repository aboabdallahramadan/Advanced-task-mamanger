package net.qmindtech.tmap.widget

import android.content.Context
import android.content.Intent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import kotlin.math.roundToInt

/**
 * Progress & Streak Glance widget (P8.9). Fixed 2×2 home-screen widget that shows:
 *   - An amber completion ring drawn as a [ProgressRingBitmap] with the day-progress % in the center
 *   - "🔥 N-day streak" + "X of Y done today" beside the ring
 *
 * Tap anywhere → deep-links to tmap://today (opens the app's Today tab).
 * Logged-out state: delegates to [SignedOutState] (shared chrome from P8.6).
 *
 * Ring note: Glance 1.1.x has no Canvas/arc modifier. The ring is rendered off-screen by
 * [ProgressRingBitmap.render] (Android Canvas + Paint) and handed to Glance as an [ImageProvider].
 * The sweep math in [ProgressRingBitmap.sweepDegrees] is pure and unit-tested (P8.9 test class).
 */
class ProgressStreakWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = widgetEntryPoint(context).widgetRepository().loadToday()
        val ring = ProgressRingBitmap.render(data.progress, sizePx = 160)

        provideContent {
            WidgetCard {
                if (!data.signedIn) {
                    SignedOutState()
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = GlanceModifier.fillMaxSize()
                            .clickable(
                                actionStartActivity(
                                    Intent(Intent.ACTION_VIEW, WidgetLinks.today()).apply {
                                        setPackage(context.packageName)
                                    },
                                ),
                            ),
                    ) {
                        // ── Completion ring with center "%" label ──────────────────────────────
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = GlanceModifier.size(58.dp),
                        ) {
                            Image(
                                provider = ImageProvider(ring),
                                contentDescription = "Today's progress ring",
                                modifier = GlanceModifier.size(58.dp),
                            )
                            Text(
                                text = "${(data.progress * 100).roundToInt()}%",
                                style = TextStyle(
                                    color = WidgetColors.textPrimary,
                                    fontSize = 14.sp,
                                ),
                            )
                        }
                        Spacer(modifier = GlanceModifier.width(12.dp))

                        // ── Streak + count labels ──────────────────────────────────────────────
                        Column {
                            Text(
                                text = "🔥 ${data.streak}-day streak",
                                style = TextStyle(
                                    color = WidgetColors.textPrimary,
                                    fontSize = 14.sp,
                                ),
                            )
                            Spacer(modifier = GlanceModifier.height(3.dp))
                            Text(
                                text = "${data.doneCount} of ${data.totalCount} done today",
                                style = TextStyle(
                                    color = WidgetColors.textSecondary,
                                    fontSize = 11.sp,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}
