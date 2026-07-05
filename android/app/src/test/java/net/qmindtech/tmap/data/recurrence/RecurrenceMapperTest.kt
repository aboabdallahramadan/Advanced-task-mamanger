package net.qmindtech.tmap.data.recurrence

import net.qmindtech.tmap.data.sync.Mappers.toEntity
import net.qmindtech.tmap.data.remote.dto.RecurrenceRuleSyncRow
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class RecurrenceMapperTest {

    @Test
    fun `weekly draft keeps days, wire enums are PascalCase`() {
        val d = RecurrenceDraft(
            frequency = RecurrenceFrequency.Weekly, interval = 2, daysOfWeek = listOf(1, 3),
            endType = RecurrenceEndType.Count, endCount = 5, endDate = null,
        )
        val input = RecurrenceMapper.ruleInput(d, id = "r1")
        assertEquals("Weekly", input.frequency)
        assertEquals(listOf(1, 3), input.daysOfWeek)
        assertEquals(5, input.endCount)
        assertEquals(null, input.endDate)
        assertEquals("r1", input.id)
    }

    @Test
    fun `daily draft clears daysOfWeek and endType-gated fields`() {
        val d = RecurrenceDraft(
            frequency = RecurrenceFrequency.Daily, interval = 1, daysOfWeek = listOf(2, 4),
            endType = RecurrenceEndType.Date, endCount = 9, endDate = LocalDate.parse("2026-08-01"),
        )
        val input = RecurrenceMapper.ruleInput(d, id = "r2")
        assertEquals("Daily", input.frequency)
        assertEquals(emptyList<Int>(), input.daysOfWeek)   // daily => []
        assertEquals(null, input.endCount)                 // not Count => null
        assertEquals("2026-08-01", input.endDate)          // Date => sent
    }

    @Test
    fun `interval below 1 is clamped`() {
        val d = RecurrenceDraft(RecurrenceFrequency.Daily, 0, emptyList(), RecurrenceEndType.Never, null, null)
        assertEquals(1, RecurrenceMapper.ruleInput(d, "r3").interval)
    }

    @Test
    fun `sync row maps to entity`() {
        val row = RecurrenceRuleSyncRow(
            id = "r4", frequency = "Weekly", interval = 3, daysOfWeek = listOf(0, 6),
            endType = "Date", endCount = null, endDate = "2026-12-31", generatedUntil = "2026-07-19",
            createdAt = "2026-07-05T10:00:00+00:00", updatedAt = "2026-07-05T10:00:00+00:00",
            changeSeq = 12L, deletedAt = null,
        )
        val e = row.toEntity()
        assertEquals("Weekly", e.frequency)
        assertEquals(listOf(0, 6), e.daysOfWeek)
        assertEquals(LocalDate.parse("2026-12-31"), e.endDate)
        assertEquals(12L, e.changeSeq)
    }
}
