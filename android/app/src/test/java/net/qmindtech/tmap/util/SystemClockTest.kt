package net.qmindtech.tmap.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class SystemClockTest {

    @Test
    fun `now is close to the current wall clock`() {
        val before = Instant.now()
        val n = SystemClock().now()
        val after = Instant.now()
        assertTrue("now() must be within [before, after]", !n.isBefore(before) && !n.isAfter(after))
    }

    @Test
    fun `today reflects the configured zone`() {
        // A far-eastern zone: when it is just after midnight there, the UTC date can still be the previous day.
        val zone = ZoneId.of("Pacific/Kiritimati") // UTC+14, the earliest civil date on Earth
        val expected = LocalDate.now(zone)
        assertEquals(expected, SystemClock(zone).today())
    }

    @Test
    fun `default zone is the system default`() {
        val expected = LocalDate.now(ZoneId.systemDefault())
        assertEquals(expected, SystemClock().today())
    }
}
