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
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.Text
import androidx.glance.text.TextStyle

/**
 * Up Next / Focus Glance widget (P8.8). Fixed 2×2 home-screen widget that shows the next
 * incomplete task from today — "UP NEXT" amber label, title, optional project·time meta — and an
 * amber "◉ Focus" button that deep-links to tmap://focus/{taskId} (or tmap://focus if no task).
 *
 * Live countdown: P6's FocusController / FocusSessionRepository are not built in this worktree.
 * The [focusRemainingLabel] field is always `null` here, keeping the widget in the static Up-Next
 * layout. Once P6 ships, the focus foreground service should call WidgetUpdater on each minute
 * tick and populate focusRemainingLabel from FocusSessionRepository.  See TODO(P6) below.
 *
 * Logged-out state: delegates to [SignedOutState] (shared chrome, same as other P8 widgets).
 * No next task: shows "All clear" in place of the title, button still fires tmap://focus.
 */
class UpNextFocusWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = widgetEntryPoint(context).widgetRepository().loadToday()

        // TODO(P6): when FocusController / FocusSessionRepository exist, read the remaining
        //  focus-session duration here and assign it (e.g. "18 min left") to focusRemainingLabel.
        //  The focus foreground service should also tick WidgetUpdater every minute to re-render.
        //  Until P6, this is always null and the widget always shows the static Up-Next layout.
        val focusRemainingLabel: String? = null

        provideContent {
            WidgetCard {
                if (!data.signedIn) {
                    SignedOutState()
                } else {
                    UpNextContent(context, data.nextTask, focusRemainingLabel)
                }
            }
        }
    }
}

// ── Up-Next body ─────────────────────────────────────────────────────────────────────────────────

@androidx.compose.runtime.Composable
private fun UpNextContent(context: Context, next: WidgetTaskItem?, focusRemainingLabel: String?) {
    Column(modifier = GlanceModifier.fillMaxSize()) {
        // "UP NEXT" / "FOCUSING" amber label
        Text(
            text = if (focusRemainingLabel != null) "FOCUSING" else "UP NEXT",
            style = TextStyle(
                color = WidgetColors.accent,
                fontSize = 10.sp,
            ),
        )
        Spacer(modifier = GlanceModifier.height(7.dp))

        // Primary text: countdown label OR task title OR "All clear"
        Text(
            text = focusRemainingLabel ?: (next?.title ?: "All clear"),
            maxLines = 2,
            style = TextStyle(
                color = WidgetColors.textPrimary,
                fontSize = 14.sp,
            ),
        )

        // Project · time meta row (only when we have a next task and a time label)
        if (next?.timeLabel != null) {
            Spacer(modifier = GlanceModifier.height(3.dp))
            Text(
                text = next.timeLabel,
                style = TextStyle(
                    color = WidgetColors.textSecondary,
                    fontSize = 11.sp,
                ),
            )
        }

        Spacer(modifier = GlanceModifier.defaultWeight())

        // Amber "◉ Focus" button — deep-links to tmap://focus/{id} or tmap://focus
        Box(
            contentAlignment = Alignment.Center,
            modifier = GlanceModifier
                .fillMaxWidth()
                .cornerRadius(11.dp)
                .background(WidgetColors.accent)
                .padding(vertical = 8.dp)
                .clickable(
                    actionStartActivity(
                        Intent(Intent.ACTION_VIEW, WidgetLinks.focus(next?.id)).apply {
                            setPackage(context.packageName)
                        },
                    ),
                ),
        ) {
            Text(
                text = "◉ Focus",
                style = TextStyle(
                    color = WidgetColors.onAccent,
                    fontSize = 12.sp,
                ),
            )
        }
    }
}
