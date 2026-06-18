package net.qmindtech.tmap.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The single time abstraction injected everywhere — never java.time.Clock. */
interface Clock {
    fun now(): Instant
    fun today(): LocalDate
}

/** Production clock; [today] is the calendar date in [zone] (the user's zone). */
class SystemClock(private val zone: ZoneId = ZoneId.systemDefault()) : Clock {
    override fun now(): Instant = Instant.now()
    override fun today(): LocalDate = LocalDate.now(zone)
}
