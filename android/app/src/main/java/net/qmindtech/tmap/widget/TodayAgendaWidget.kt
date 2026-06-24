package net.qmindtech.tmap.widget

import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.Text
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider

/**
 * Today Agenda Glance widget (P8.6). Resizable (SizeMode.Exact) — taller → more rows visible.
 * Reads today's tasks from [WidgetRepository.loadToday]; check-off via [ToggleTaskAction];
 * tap row → deep-link to tmap://task/{id}; logged-out → "Sign in to TMap" state.
 */
class TodayAgendaWidget : GlanceAppWidget() {

    /** Recompose when the widget is resized so additional rows appear as it grows taller. */
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = widgetEntryPoint(context).widgetRepository().loadToday()
        provideContent {
            WidgetCard {
                if (!data.signedIn) {
                    SignedOutState()
                } else {
                    Column(modifier = GlanceModifier.fillMaxSize()) {
                        // ── Header ──────────────────────────────────────────────────────────────
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = GlanceModifier.fillMaxWidth(),
                        ) {
                            // Amber accent dot
                            Box(
                                modifier = GlanceModifier
                                    .size(7.dp)
                                    .cornerRadius(4.dp)
                                    .background(WidgetColors.accent),
                            ) {}
                            Spacer(modifier = GlanceModifier.width(8.dp))
                            Text(
                                text = "Today",
                                style = TextStyle(
                                    color = WidgetColors.textPrimary,
                                    fontSize = 13.sp,
                                ),
                            )
                            Spacer(modifier = GlanceModifier.defaultWeight())
                            Text(
                                text = "${data.doneCount} of ${data.totalCount} · ${hoursLeft(data.minutesLeft)}",
                                style = TextStyle(
                                    color = WidgetColors.textSecondary,
                                    fontSize = 11.sp,
                                ),
                            )
                        }
                        Spacer(modifier = GlanceModifier.height(10.dp))

                        // ── Task rows ────────────────────────────────────────────────────────────
                        if (data.items.isEmpty()) {
                            Text(
                                text = "Nothing planned today",
                                style = TextStyle(
                                    color = WidgetColors.textSecondary,
                                    fontSize = 12.sp,
                                ),
                            )
                        } else {
                            data.items.forEachIndexed { index, item ->
                                TaskRow(item)
                                if (index < data.items.lastIndex) {
                                    Spacer(modifier = GlanceModifier.height(9.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Shared chrome (used by all P8 widgets) ──────────────────────────────────────────────────────

/**
 * Rounded dark card wrapping every widget body. Midnight Calm [WidgetColors.surface] background,
 * 22dp corner radius. Shared by all four P8 widget composables for a consistent home-screen look.
 */
@androidx.compose.runtime.Composable
internal fun WidgetCard(content: @androidx.compose.runtime.Composable () -> Unit) {
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(WidgetColors.surface)
            .cornerRadius(22.dp)
            .padding(14.dp),
    ) {
        content()
    }
}

/**
 * Shown when the user is not signed in. Tapping launches MainActivity so they can log in.
 */
@androidx.compose.runtime.Composable
internal fun SignedOutState() {
    Column(
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = GlanceModifier
            .fillMaxSize()
            .clickable(
                actionStartActivity(
                    Intent(Intent.ACTION_MAIN).apply {
                        setClassName("net.qmindtech.tmap", "net.qmindtech.tmap.MainActivity")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                ),
            ),
    ) {
        Text(
            text = "Sign in to TMap",
            style = TextStyle(
                color = WidgetColors.textPrimary,
                fontSize = 14.sp,
            ),
        )
        Spacer(modifier = GlanceModifier.height(4.dp))
        Text(
            text = "Tap to open",
            style = TextStyle(
                color = WidgetColors.textSecondary,
                fontSize = 11.sp,
            ),
        )
    }
}

// ── Task row ────────────────────────────────────────────────────────────────────────────────────

@androidx.compose.runtime.Composable
private fun TaskRow(item: WidgetTaskItem) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = GlanceModifier
            .fillMaxWidth()
            .clickable(
                actionStartActivity(
                    Intent(Intent.ACTION_VIEW, WidgetLinks.task(item.id)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                ),
            ),
    ) {
        CheckCircle(item)
        Spacer(modifier = GlanceModifier.width(11.dp))
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                text = item.title,
                maxLines = 1,
                style = TextStyle(
                    color = if (item.isDone) WidgetColors.textTertiary else WidgetColors.textPrimary,
                    fontSize = 13.sp,
                    textDecoration = if (item.isDone) TextDecoration.LineThrough else TextDecoration.None,
                ),
            )
            if (item.timeLabel != null) {
                Spacer(modifier = GlanceModifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = GlanceModifier
                            .size(7.dp)
                            .cornerRadius(4.dp)
                            .background(
                                ColorProvider(
                                    Color(item.projectColor ?: 0xFFE8A87CL),
                                ),
                            ),
                    ) {}
                    Spacer(modifier = GlanceModifier.width(6.dp))
                    Text(
                        text = item.timeLabel,
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

/**
 * Circular check control. Filled amber + ✓ when done; hollow ring when pending. Tapping toggles
 * the task via [ToggleTaskAction] (write-through → outbox → sync) then refreshes the widget.
 *
 * The hollow ring is emulated as a 20dp border-colored disc + 16dp surface-colored inset disc
 * because Glance 1.1.x has no stroke/border modifier. cornerRadius degrades to square on API <31.
 */
@androidx.compose.runtime.Composable
private fun CheckCircle(item: WidgetTaskItem) {
    val checkAction = actionRunCallback<ToggleTaskAction>(
        actionParametersOf(ToggleTaskAction.taskIdKey to item.id),
    )
    if (item.isDone) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = GlanceModifier
                .size(20.dp)
                .cornerRadius(10.dp)
                .background(WidgetColors.accent)
                .clickable(checkAction),
        ) {
            Text(
                text = "✓",
                style = TextStyle(
                    color = WidgetColors.onAccent,
                    fontSize = 11.sp,
                ),
            )
        }
    } else {
        // Hollow ring: outer border-colored disc, inner surface-colored disc
        Box(
            contentAlignment = Alignment.Center,
            modifier = GlanceModifier
                .size(20.dp)
                .cornerRadius(10.dp)
                .background(WidgetColors.border)
                .clickable(checkAction),
        ) {
            Box(
                modifier = GlanceModifier
                    .size(16.dp)
                    .cornerRadius(8.dp)
                    .background(WidgetColors.surface),
            ) {}
        }
    }
}

// ── Private helpers ─────────────────────────────────────────────────────────────────────────────

private fun hoursLeft(minutes: Int): String {
    if (minutes <= 0) return "0m left"
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h > 0 && m > 0 -> "${h}h ${m}m left"
        h > 0 -> "${h}h left"
        else -> "${m}m left"
    }
}
