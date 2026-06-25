package net.qmindtech.tmap.data.stats

import net.qmindtech.tmap.data.local.TaskStatus
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
}
