package net.qmindtech.tmap.data.remote.dto

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class FocusDailyPlanDtosTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun `CreateFocusSessionRequest serializes the wire shape, omitting null id and taskId`() {
        val body = json.encodeToString(CreateFocusSessionRequest.serializer(),
            CreateFocusSessionRequest(
                project = "العمل", startedAt = "2026-06-18T09:00:00Z",
                endedAt = "2026-06-18T09:25:00Z", minutes = 25, date = "2026-06-18",
            ))
        assertEquals(
            """{"project":"العمل","startedAt":"2026-06-18T09:00:00Z","endedAt":"2026-06-18T09:25:00Z","minutes":25,"date":"2026-06-18"}""",
            body,
        )
    }

    @Test
    fun `UpsertDailyPlanRequest serializes ordered plannedTaskIds`() {
        val body = json.encodeToString(UpsertDailyPlanRequest.serializer(),
            UpsertDailyPlanRequest(plannedTaskIds = listOf("a", "b"), plannedMinutes = 120))
        assertEquals(
            """{"plannedTaskIds":["a","b"],"plannedMinutes":120}""",
            body,
        )
    }

    @Test
    fun `DailyPlanResponse decodes a date-keyed plan and tolerates absent timestamps`() {
        val wire = """{"date":"2026-06-18","committedAt":"2026-06-18T07:00:00Z",
            "plannedTaskIds":["a","b","c"],"plannedMinutes":180}""".trimIndent()
        val r = json.decodeFromString(DailyPlanResponse.serializer(), wire)
        assertEquals("2026-06-18", r.date)
        assertEquals(listOf("a", "b", "c"), r.plannedTaskIds)
        assertEquals(180, r.plannedMinutes)
    }
}
