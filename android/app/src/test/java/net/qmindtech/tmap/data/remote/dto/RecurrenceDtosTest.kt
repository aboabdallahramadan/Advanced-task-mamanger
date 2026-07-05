package net.qmindtech.tmap.data.remote.dto

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecurrenceDtosTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    @Test
    fun `create request serializes nested task and rule with PascalCase enums`() {
        val req = CreateRecurringTaskRequest(
            task = RecurringTaskInput(
                title = "Standup", notes = "", projectId = null, labels = emptyList(),
                source = "android", plannedDate = "2026-07-06", durationMinutes = 30,
                priority = null, reminderMinutes = 0, id = "task-1",
            ),
            rule = RecurrenceRuleInput(
                frequency = "Weekly", interval = 1, daysOfWeek = listOf(1, 3, 5),
                endType = "Never", endCount = null, endDate = null, id = "rule-1",
            ),
        )
        val s = json.encodeToString(CreateRecurringTaskRequest.serializer(), req)
        assertTrue(s, s.contains("\"task\""))
        assertTrue(s, s.contains("\"rule\""))
        assertTrue(s, s.contains("\"frequency\":\"Weekly\""))
        assertTrue(s, s.contains("\"daysOfWeek\":[1,3,5]"))
        assertTrue(s, s.contains("\"source\":\"android\""))
    }

    @Test
    fun `widened sync row deserializes full server shape`() {
        val payload = """
          {"id":"r1","frequency":"Daily","interval":1,"daysOfWeek":[],"endType":"Count",
           "endCount":5,"endDate":null,"generatedUntil":"2026-07-19",
           "createdAt":"2026-07-05T10:00:00+00:00","updatedAt":"2026-07-05T10:00:00+00:00",
           "changeSeq":42,"deletedAt":null}
        """.trimIndent()
        val row = json.decodeFromString(RecurrenceRuleSyncRow.serializer(), payload)
        assertEquals("Daily", row.frequency)
        assertEquals(5, row.endCount)
        assertEquals(42L, row.changeSeq)
        assertEquals(emptyList<Int>(), row.daysOfWeek)
    }

    @Test
    fun `delete envelope carries scope and fromDate`() {
        val s = json.encodeToString(
            RecurrenceDeletePayload.serializer(),
            RecurrenceDeletePayload(scope = "future", fromDate = "2026-07-10"),
        )
        assertTrue(s, s.contains("\"scope\":\"future\""))
        assertTrue(s, s.contains("\"fromDate\":\"2026-07-10\""))
    }
}
