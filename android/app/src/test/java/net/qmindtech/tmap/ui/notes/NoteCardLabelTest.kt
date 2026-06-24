package net.qmindtech.tmap.ui.notes

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

/**
 * Unit tests for [noteEditedLabel] — pure helper, no Android dependencies.
 *
 * All instants and the zone are fixed (UTC) so results are deterministic on every JVM.
 *
 *   now = 2026-06-20 10:00:00 UTC  (Saturday)
 *   yesterday = 2026-06-19  (Friday)
 *
 * Branch coverage:
 *   1. < 1 min  → "just now"
 *   2. 1–59 min → "Xm ago"
 *   3. same calendar day, ≥ 1 h → "Xh ago"
 *   4. previous calendar day → "yesterday"
 *   5. same year, older → "MMM d"   e.g. "Jun 5"
 *   6. different year    → "MMM d, yyyy"
 */
class NoteCardLabelTest {

    private val zone = ZoneOffset.UTC
    private val now = Instant.parse("2026-06-20T10:00:00Z")

    @Test fun just_now_when_less_than_one_minute() {
        val updatedAt = now.minusSeconds(30)
        assertEquals("just now", noteEditedLabel(updatedAt, now, zone))
    }

    @Test fun just_now_when_exact_same_instant() {
        assertEquals("just now", noteEditedLabel(now, now, zone))
    }

    @Test fun minutes_ago_at_one_minute() {
        val updatedAt = now.minusSeconds(60)
        assertEquals("1m ago", noteEditedLabel(updatedAt, now, zone))
    }

    @Test fun minutes_ago_at_45_minutes() {
        val updatedAt = now.minusSeconds(45 * 60)
        assertEquals("45m ago", noteEditedLabel(updatedAt, now, zone))
    }

    @Test fun minutes_ago_at_59_minutes() {
        val updatedAt = now.minusSeconds(59 * 60)
        assertEquals("59m ago", noteEditedLabel(updatedAt, now, zone))
    }

    @Test fun hours_ago_at_exactly_one_hour() {
        // 60 min = same calendar day in UTC, so → hours branch
        val updatedAt = now.minusSeconds(60 * 60)
        assertEquals("1h ago", noteEditedLabel(updatedAt, now, zone))
    }

    @Test fun hours_ago_at_two_hours() {
        val updatedAt = now.minusSeconds(2 * 60 * 60)
        assertEquals("2h ago", noteEditedLabel(updatedAt, now, zone))
    }

    @Test fun yesterday_when_previous_calendar_day() {
        // 2026-06-19 23:00 UTC is yesterday relative to now (2026-06-20)
        val updatedAt = Instant.parse("2026-06-19T23:00:00Z")
        assertEquals("yesterday", noteEditedLabel(updatedAt, now, zone))
    }

    @Test fun yesterday_start_of_day() {
        val updatedAt = Instant.parse("2026-06-19T00:00:00Z")
        assertEquals("yesterday", noteEditedLabel(updatedAt, now, zone))
    }

    @Test fun same_year_older_gives_mmm_d() {
        // 2026-06-05 — same year as now (2026), but two weeks ago
        val updatedAt = Instant.parse("2026-06-05T08:00:00Z")
        assertEquals("Jun 5", noteEditedLabel(updatedAt, now, zone))
    }

    @Test fun different_year_gives_mmm_d_yyyy() {
        val updatedAt = Instant.parse("2025-11-03T12:00:00Z")
        assertEquals("Nov 3, 2025", noteEditedLabel(updatedAt, now, zone))
    }
}
