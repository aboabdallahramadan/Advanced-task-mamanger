package net.qmindtech.tmap.data.stats

import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.local.entities.FocusSessionEntity
import net.qmindtech.tmap.testutil.FixedClock
import net.qmindtech.tmap.testutil.fakeTask
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class StatsCalculatorTest {
    // FixedClock.today() = the UTC date of `now` (see testutil.Fakes). 2026-06-18 is a Thursday.
    private val calc = StatsCalculator(FixedClock(Instant.parse("2026-06-18T12:00:00Z")))
    private val today = LocalDate.of(2026, 6, 18)

    @Test fun `todayProgress is the done-fraction of todays planned tasks`() {
        val tasks = listOf(
            fakeTask("a", plannedDate = today, status = TaskStatus.Done),
            fakeTask("b", plannedDate = today, status = TaskStatus.Done),
            fakeTask("c", plannedDate = today, status = TaskStatus.Planned),
            fakeTask("d", plannedDate = today, status = TaskStatus.Scheduled),
        )
        assertEquals(0.5f, calc.todayProgress(tasks), 0.0001f) // 2 of 4
    }

    @Test fun `todayProgress ignores tasks planned for other days`() {
        val tasks = listOf(
            fakeTask("a", plannedDate = today, status = TaskStatus.Done),
            fakeTask("y", plannedDate = LocalDate.of(2026, 6, 17), status = TaskStatus.Done),
            fakeTask("z", plannedDate = LocalDate.of(2026, 6, 19), status = TaskStatus.Planned),
        )
        assertEquals(1.0f, calc.todayProgress(tasks), 0.0001f) // only "a" is today, and it's done
    }

    @Test fun `todayProgress is zero when no task is planned today`() {
        val tasks = listOf(fakeTask("u", plannedDate = null, status = TaskStatus.Inbox))
        assertEquals(0.0f, calc.todayProgress(tasks), 0.0001f)
    }

    @Test fun `doneThisWeek counts Done tasks completed within the ISO week of today`() {
        val tasks = listOf(
            // inside the window
            fakeTask("mon", status = TaskStatus.Done, completedAt = Instant.parse("2026-06-15T00:00:00Z")),
            fakeTask("thu", status = TaskStatus.Done, completedAt = Instant.parse("2026-06-18T09:30:00Z")),
            fakeTask("sun", status = TaskStatus.Done, completedAt = Instant.parse("2026-06-21T23:59:59Z")),
            // just outside: previous Sunday and next Monday
            fakeTask("prevSun", status = TaskStatus.Done, completedAt = Instant.parse("2026-06-14T23:59:59Z")),
            fakeTask("nextMon", status = TaskStatus.Done, completedAt = Instant.parse("2026-06-22T00:00:00Z")),
            // Done but no completedAt, and not-Done — both excluded
            fakeTask("noStamp", status = TaskStatus.Done, completedAt = null),
            fakeTask("planned", status = TaskStatus.Planned, completedAt = Instant.parse("2026-06-18T10:00:00Z")),
        )
        assertEquals(3, calc.doneThisWeek(tasks))
    }

    @Test fun `doneThisWeek is zero when nothing completed this week`() {
        assertEquals(0, calc.doneThisWeek(emptyList()))
    }

    private fun session(id: String, date: LocalDate, minutes: Int, deletedAt: Instant? = null) =
        FocusSessionEntity(
            id = id, taskId = null, project = "Work",
            startedAt = date.atStartOfDay(java.time.ZoneOffset.UTC).toInstant(),
            endedAt = date.atStartOfDay(java.time.ZoneOffset.UTC).toInstant(),
            minutes = minutes, date = date,
            createdAt = Instant.parse("2026-06-18T00:00:00Z"),
            updatedAt = Instant.parse("2026-06-18T00:00:00Z"),
            changeSeq = 0L, deletedAt = deletedAt,
        )

    @Test fun `focusMinutesThisWeek sums minutes of sessions dated within the ISO week`() {
        val sessions = listOf(
            session("a", LocalDate.of(2026, 6, 15), 25),  // Monday, in
            session("b", LocalDate.of(2026, 6, 18), 50),  // Thursday, in
            session("c", LocalDate.of(2026, 6, 21), 30),  // Sunday, in
            session("prev", LocalDate.of(2026, 6, 14), 90), // previous Sunday, out
            session("next", LocalDate.of(2026, 6, 22), 90), // next Monday, out
        )
        assertEquals(105, calc.focusMinutesThisWeek(sessions)) // 25 + 50 + 30
    }

    @Test fun `focusMinutesThisWeek ignores tombstoned sessions and empty input`() {
        assertEquals(0, calc.focusMinutesThisWeek(emptyList()))
        val sessions = listOf(
            session("live", LocalDate.of(2026, 6, 18), 40),
            session("dead", LocalDate.of(2026, 6, 18), 999, deletedAt = Instant.parse("2026-06-18T10:00:00Z")),
        )
        assertEquals(40, calc.focusMinutesThisWeek(sessions))
    }
}
