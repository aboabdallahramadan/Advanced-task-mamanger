package net.qmindtech.tmap.ui.notes

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.qmindtech.tmap.ui.components.ProjectDot
import net.qmindtech.tmap.ui.theme.LocalTmapColors
import net.qmindtech.tmap.ui.theme.LocalTmapShapes
import net.qmindtech.tmap.ui.theme.LocalTmapSpacing
import net.qmindtech.tmap.ui.theme.LocalTmapType
import net.qmindtech.tmap.ui.theme.TmapTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Reusable note card for both Pinned and Recent sections of NotesScreen.
 *
 * Pinned variant: surfaceRaised bg + 2dp accent start-edge bar + 📌 glyph (tap = unpin).
 * Recent variant: surface bg + no accent bar; long-press on card acts as non-gesture "pin" path (a11y §9).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NoteCard(
    note: NoteCardUi,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalTmapColors.current
    val shapes = LocalTmapShapes.current
    val spacing = LocalTmapSpacing.current
    val type = LocalTmapType.current

    val cardShape = RoundedCornerShape(shapes.card)
    val bgColor = if (note.pinned) colors.surfaceRaised else colors.surface

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(bgColor)
            .border(width = 1.dp, color = colors.borderSubtle, shape = cardShape)
            .combinedClickable(
                onClick = onClick,
                // Long-press on a recent card is the non-gesture "pin" equivalent (a11y §9).
                onLongClick = if (!note.pinned) onTogglePin else null,
                onLongClickLabel = if (!note.pinned) "Pin note" else null,
            ),
    ) {
        // 2dp accent start-edge bar (pinned only, full card height, RTL-safe: leftmost in LTR).
        if (note.pinned) {
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .matchParentSize()
                    .background(colors.accent)
                    .align(Alignment.CenterStart),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Spacer matching the accent bar width to push content clear of it.
            if (note.pinned) Spacer(modifier = Modifier.width(2.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = spacing.md, vertical = spacing.md),
            ) {
                // ── Title row ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = note.title,
                        style = type.body.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                        ),
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (note.pinned) {
                        Spacer(modifier = Modifier.width(spacing.xs))
                        // Tappable 📌 glyph — contentDescription satisfies icon-only a11y rule.
                        Text(
                            text = "📌",
                            style = type.meta,
                            modifier = Modifier
                                .semantics {
                                    contentDescription = "Unpin note"
                                    role = Role.Button
                                }
                                .clickable(onClick = onTogglePin),
                        )
                    }
                }

                // ── Snippet (2 lines, ellipsised, textBody color) ──
                if (note.snippet.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(7.dp))
                    Text(
                        text = note.snippet,
                        style = type.meta.copy(fontSize = 12.5f.sp, lineHeight = 18.sp),
                        color = colors.textBody,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // ── Meta row: optional project dot + "Project · edited Xh ago" in tertiary ──
                val editedLabel = noteEditedLabel(note.updatedAt, Instant.now())
                val metaText = buildString {
                    if (!note.projectName.isNullOrBlank()) {
                        append(note.projectName)
                        append(" · ")
                    }
                    append(editedLabel)
                }
                Spacer(modifier = Modifier.height(9.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    if (note.projectColor != null) {
                        ProjectDot(colorArgb = note.projectColor, size = 7.dp)
                    }
                    Text(
                        text = metaText,
                        style = type.label,
                        color = colors.textTertiary,
                    )
                }
            }

            // Non-gesture pin control for recent cards (a11y §9: swipe actions need non-gesture eq.).
            // Accessible button that shows "⋯" — tap triggers onTogglePin.
            if (!note.pinned) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .semantics {
                            contentDescription = "Pin note"
                            role = Role.Button
                        }
                        .clickable(onClick = onTogglePin),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "⋯",
                        style = type.body,
                        color = colors.textTertiary,
                    )
                }
            }
        }
    }
}

/**
 * Formats a note's [updatedAt] into a human-readable edited-time label.
 *
 * Rules (matches the mockup "edited 2h ago" / "yesterday" / "Jun 19"):
 *   < 1 min               → "just now"
 *   < 60 min              → "Xm ago"
 *   same calendar day     → "Xh ago"
 *   previous calendar day → "yesterday"
 *   same year             → "MMM d"       e.g. "Jun 19"
 *   else                  → "MMM d, yyyy"
 *
 * [now] is injectable for deterministic behaviour in tests.
 */
fun noteEditedLabel(
    updatedAt: Instant,
    now: Instant,
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val minutesAgo = ChronoUnit.MINUTES.between(updatedAt, now).coerceAtLeast(0)
    if (minutesAgo < 1) return "just now"
    if (minutesAgo < 60) return "${minutesAgo}m ago"

    val updatedDate = updatedAt.atZone(zone).toLocalDate()
    val nowDate = now.atZone(zone).toLocalDate()

    if (updatedDate == nowDate) {
        val hoursAgo = ChronoUnit.HOURS.between(updatedAt, now).coerceAtLeast(1)
        return "${hoursAgo}h ago"
    }
    if (updatedDate == nowDate.minusDays(1)) return "yesterday"

    val sameYear = updatedDate.year == nowDate.year
    val pattern = if (sameYear) "MMM d" else "MMM d, yyyy"
    return updatedDate.format(DateTimeFormatter.ofPattern(pattern))
}

// ---------------------------------------------------------------------------
// Previews — compile-gate also exercises rendering
// ---------------------------------------------------------------------------

@Preview(showBackground = true, backgroundColor = 0xFF141519, name = "NoteCard – pinned")
@Composable
private fun PreviewNoteCardPinned() {
    TmapTheme {
        NoteCard(
            note = NoteCardUi(
                id = "1",
                title = "Q3 strategy brain-dump",
                snippet = "Three bets for next quarter: double down on onboarding, ship the mobile app, " +
                    "and tighten the weekly planning ritual…",
                projectColor = 0xFF6EA8FEL,
                projectName = "Work",
                updatedAt = Instant.now().minusSeconds(7_200),
                pinned = true,
            ),
            onClick = {},
            onTogglePin = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF141519, name = "NoteCard – recent")
@Composable
private fun PreviewNoteCardRecent() {
    TmapTheme {
        NoteCard(
            note = NoteCardUi(
                id = "2",
                title = "Books to read",
                snippet = "Thinking in Systems, The Beginning of Infinity, Seeing Like a State…",
                projectColor = 0xFFC9A0FFL,
                projectName = "Ideas",
                updatedAt = Instant.now().minusSeconds(90_000),
                pinned = false,
            ),
            onClick = {},
            onTogglePin = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF141519, name = "NoteCard – no project")
@Composable
private fun PreviewNoteCardNoProject() {
    TmapTheme {
        NoteCard(
            note = NoteCardUi(
                id = "3",
                title = "Morning pages",
                snippet = "Slept well. Feeling clear about the week ahead — first priority is finishing the onboarding flow.",
                projectColor = null,
                projectName = null,
                updatedAt = Instant.now().minusSeconds(300),
                pinned = false,
            ),
            onClick = {},
            onTogglePin = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}
